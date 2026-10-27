# Architecture

Single-module Android app (`:app`), Kotlin, Jetpack Compose. `minSdk 26`, `targetSdk 37`. No network, no database. Third-party code: AndroidX/Compose, and Google's Play app-update library for the update check.

## Components

| File | Role |
|---|---|
| `MainActivity.kt` | Compose UI: mode, clap count, sensitivity, alarm sound, ARM switch, live visualizer. Records the custom-sound template. |
| `ListenerService.kt` | Foreground service (`microphone` type). Owns the microphone while armed. Screen-state receiver. Starts and stops the alarm, and runs the echo-cancelled, gap-and-window listening that lets two claps silence it. |
| `EchoGuard.kt` | Pure Kotlin. Decides whether echo cancellation hides the alarm from the microphone. |
| `ClapDetector.kt` | Pure Kotlin. Counts impulse claps in a PCM stream. |
| `ClapShape.kt` | Pure Kotlin. Decides whether a transient *sounds* like a clap (spectral centroid, low-band share, flatness; its own FFT). Used by `ClapDetector`. |
| `CustomSoundDetector.kt` | Pure Kotlin. Matches a recorded template against the stream. |
| `AlarmPlayer.kt` | Loops the chosen sound on the alarm stream at maximum volume; restores volume on stop. |
| `UpdateChecker.kt` | Asks Google Play (through the Play Store app) whether a newer version exists; opens the Play listing. |
| `RulesActivity.kt` | Compose screen "When to listen". Edits the rules, requests location only when a place or Wi-Fi rule is used (disclosure first). |
| `ListenRules.kt` | Pure Kotlin. `Rules`, the conditions they test, and `RuleEngine.evaluate` which returns `Listen` or `Pause(reason)`. Unknown conditions never pause. |
| `RulesCodec.kt` | Pure Kotlin. Stores `Rules` as `key=value` text in one preference; tolerant of damage. |
| `BodyEstimator.kt` | Pure Kotlin. Guesses whether the phone is on a person from accelerometer motion and the proximity sensor. |
| `ConditionWatcher.kt` | Android glue for the rules: battery, audio devices, Wi-Fi, location, motion sensors and the schedule alarm. Registers only what the rules need, and the costly sources only while the screen is off. |
| `LocationAccess.kt` | None / while-in-use / all-the-time location state. |
| `Snooze.kt` | Pure Kotlin. The "Later" rule. |
| `Prefs.kt` | `SharedPreferences` wrapper: clap count, sensitivity, trigger mode, custom sound bytes, alarm URI. |

## State machine

| State | Microphone | Notification |
|---|---|---|
| Disarmed | Released (live visualizer may use it while the app is open) | None |
| Armed, screen on | Released | "Listening in the dark" |
| Armed, screen off (Listening) | Open on `clap-listener`; partial wake lock held | "Listening in the dark" |
| Alarming | Open on `clap-dismiss` (if clap-to-silence is on); wake lock held | "Here I am" with **Stop**; alarm replaying with 1 s gaps, and muted windows if cancellation fails |

| From | Event | To |
|---|---|---|
| Disarmed | ARM | Armed (Listening if the screen is already off) |
| Armed, screen on | Screen off | Listening |
| Listening | Screen on | Armed, screen on |
| Listening | Detector match | Alarming |
| Alarming | Unlock (`USER_PRESENT`), **Stop**, or two claps in a window | Listening if the screen is off, else Armed, screen on |
| Any armed state | DISARM / service destroyed | Disarmed |

## Service lifecycle

1. `MainActivity` checks `RECORD_AUDIO` (and `POST_NOTIFICATIONS` on API 33+), then `ContextCompat.startForegroundService`.
2. `onStartCommand` calls `startForeground(..., FOREGROUND_SERVICE_TYPE_MICROPHONE)`, registers the screen receiver once, then restarts the detector so changed settings apply.
3. `START_NOT_STICKY`: Android 11+ forbids starting a microphone service from the background, so a system restart could not recover it anyway. The user re-arms.
4. `onDestroy` unregisters the receiver, stops watching conditions, stops listening, silences the alarm.

### Rules

`ListenerService.applyRules()` is the one place that decides whether the find-the-phone listener runs. It is called on screen on/off, when `ConditionWatcher` reports a change, after the schedule alarm fires, after a failed microphone retry, and after the alarm is silenced:

~~~
screen on            -> stop listening
screen off, rules ok -> start listening (no-op if already)
screen off, paused   -> stop listening, notification "Not listening / Resting: <reason>"
alarm sounding       -> nothing changes; rules never cut an alarm
~~~

The hours rule needs no wake lock while paused: one inexact `AlarmManager` alarm (`setAndAllowWhileIdle`) wakes the service at the next open or close, so it may be a few minutes late. The on-body rule holds a partial wake lock while the screen is off, because sensors do not report while the CPU sleeps; this costs about as much as listening. Places use network-provider location updates every 5 minutes with the screen off. Fixes older than 30 minutes or less accurate than 500 m are treated as unknown.

`ListenerService.running` is a Compose `mutableStateOf` read by the UI. It is process-local; it resets when the process dies.

## Threading

| Thread | Work |
|---|---|
| Main | UI, receiver callbacks, `onMatch()` (posted from the worker) |
| `clap-listener` | `AudioRecord.read` loop → detector. 16 kHz mono PCM16, 20 ms reads. Exits when `recordThread` no longer references it, on read error, or on match. |
| `clap-dismiss` | Runs for the life of a sounding alarm. Keeps draining the microphone and feeds a `ClapDetector(2 claps, finishOnTarget)` according to the mode above. Occupies `recordThread`, so normal listening cannot start meanwhile. |
| `live-test-listener` | Activity-owned visualizer loop. Independent of the service. |
| `sound-recorder` | One-shot 1.5 s template capture. |
| `update-check` | One call to the Play Store app, at launch, at most every 12 hours. Posts the result to the main thread. |

### Alarm replay gap, echo cancellation and listening windows

With clap-to-silence on, `AlarmPlayer.start(uri, REPLAY_GAP_MS, onPhase)` plays the sound once (not looped), reports `onPhase(false)` on completion, stays silent 1 s, restarts and reports `onPhase(true)`. The service tracks this as `gapQuiet`. The alarm is not heard in the gap, so the gap is a clean moment to hear a clap.

`openDismissRecorder()` opens the microphone from `VOICE_COMMUNICATION` (the source vendors tune echo cancellation for), then `VOICE_RECOGNITION`, then `MIC`, and attaches an `AcousticEchoCanceler` where the phone offers one. `mayCancel` is true for `VOICE_COMMUNICATION` or an enabled canceller.

The `clap-dismiss` thread runs one of three modes (`dismissMode`):

| Mode | When | Listening | Forced mute windows |
|---|---|---|---|
| `MODE_WINDOWS` | No possible cancellation, or it failed the test | Only in silent moments (gap or window), after a 400 ms settle | Every 3.5 s of sound: a 3 s muted window |
| `MODE_CALIBRATING` | Start, if `mayCancel` | As `MODE_WINDOWS` | One, at 4 s, to give `EchoGuard` a silent reference |
| `MODE_CONTINUOUS` | `EchoGuard` says cancelled | Always, with a `fastFloor` detector; 400 ms around each sound/silence change is ignored | None |

~~~
alarm start ──4.0 s── window (mute) ──3.0 s── unmute ── verdict ─┬─ CANCELLED     → CONTINUOUS (no more windows)
                                                                  └─ NOT_CANCELLED → WINDOWS (3.5 s sound / 3 s window, repeat)
~~~

`EchoGuard` compares the microphone's RMS while the alarm is audible to its RMS while silent (500 ms of each, after settling). A ratio of 2.5 or less counts as cancelled. `windowOpen`, `gapQuiet` and `stateSince` are written on the main thread and read by the dismiss thread. The window opens only if `dismissReady` (the microphone is recording); otherwise the alarm stays loud. A match posts `silence()` to the main thread. Muting uses `MediaPlayer.setVolume(0, 0)`. While the alarm sounds, a screen-on event and a settings refresh (`onStartCommand`) leave the dismiss listener alone.

`stopListening()` clears `recordThread`, joins up to 500 ms, then releases the wake lock.

## Audio source

`VOICE_RECOGNITION`, falling back to `MIC`. `MIC` applies noise suppression and automatic gain on most phones, which flattens clap transients. The custom-sound recorder currently uses `MIC`; see KNOWN_ISSUES #3.

## Alarm

`AlarmPlayer` stores the current alarm-stream volume, sets it to maximum, and loops via `MediaPlayer` with `USAGE_ALARM`. Fallback chain: chosen URI → default alarm → default ringtone. `stop()` restores the volume.

## Data at rest

`SharedPreferences` file `clappiest` in app-private storage:

| Key | Type | Content |
|---|---|---|
| `clapCount` | int | 2–10 |
| `sensitivity` | float | 0–1 |
| `clapToStop` | boolean | Two claps silence the alarm (default on) |
| `updateCheck` | boolean | Check for updates (default on) |
| `lastUpdateCheck` | long | Time of the last completed check |
| `snoozedKey`, `snoozedUntil` | string, long | "Later": the version code and when it stops being hidden (3 days) |
| `triggerMode` | string | `claps` or `custom` |
| `customSound` | string | Base64 of 1.5 s PCM16 mono audio (~64 KB) |
| `alarmUri` | string | URI of the chosen alarm sound |
| `rules` | string | The "When to listen" rules as `key=value` lines, including any saved place names and coordinates and Wi-Fi network names |

A chosen audio file keeps a persisted read grant (`takePersistableUriPermission`). The file is not copied.

## Update check

~~~
app opened → update-check thread
  → throttled (12 h since the last completed check)? stop
  → AppUpdateManager.appUpdateInfo (answered by the Play Store app, not by a request from this app)
  → UPDATE_AVAILABLE and not snoozed for that version code → popup
~~~

No `INTERNET` permission is needed or declared. Play answers only for copies it installed (owned by the user's Google account); otherwise the call throws, the check counts as failed, and the user sees nothing. Every merge to `main` publishes to Google Play and GitHub together, so an update on Play means one on GitHub too. Play reports a version code, not a name, so the popup does not name the version. **Update** opens `market://details`, then the web listing. The app never downloads or installs anything. A failed check is retried at the next launch.

## Build and release

Gradle Kotlin DSL with a version catalog (`gradle/libs.versions.toml`). R8 and resource shrinking on release. Versions derive from `version.properties`; the central publisher overrides them via `-PversionCodeOverride` / `-PversionNameOverride`. See [RELEASE.md](RELEASE.md).
