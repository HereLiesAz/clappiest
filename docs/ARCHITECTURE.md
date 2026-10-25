# Architecture

Single-module Android app (`:app`), Kotlin, Jetpack Compose. `minSdk 26`, `targetSdk 37`. No network, no database, no third-party SDKs beyond AndroidX/Compose.

## Components

| File | Role |
|---|---|
| `MainActivity.kt` | Compose UI: mode, clap count, sensitivity, alarm sound, ARM switch, live visualizer. Records the custom-sound template. |
| `ListenerService.kt` | Foreground service (`microphone` type). Owns the microphone while armed. Screen-state receiver. Starts and stops the alarm, and runs the listening windows that let two claps silence it. |
| `ClapDetector.kt` | Pure Kotlin. Counts impulse claps in a PCM stream. |
| `CustomSoundDetector.kt` | Pure Kotlin. Matches a recorded template against the stream. |
| `AlarmPlayer.kt` | Loops the chosen sound on the alarm stream at maximum volume; restores volume on stop. |
| `Prefs.kt` | `SharedPreferences` wrapper: clap count, sensitivity, trigger mode, custom sound bytes, alarm URI. |

## State machine

| State | Microphone | Notification |
|---|---|---|
| Disarmed | Released (live visualizer may use it while the app is open) | None |
| Armed, screen on | Released | "Listening in the dark" |
| Armed, screen off (Listening) | Open on `clap-listener`; partial wake lock held | "Listening in the dark" |
| Alarming | Open on `clap-dismiss` during muted listening windows (if clap-to-silence is on); wake lock held | "Here I am" with **Stop**; alarm looping, pulsed |

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
4. `onDestroy` unregisters the receiver, stops listening, silences the alarm.

`ListenerService.running` is a Compose `mutableStateOf` read by the UI. It is process-local; it resets when the process dies.

## Threading

| Thread | Work |
|---|---|
| Main | UI, receiver callbacks, `onMatch()` (posted from the worker) |
| `clap-listener` | `AudioRecord.read` loop → detector. 16 kHz mono PCM16, 20 ms reads. Exits when `recordThread` no longer references it, on read error, or on match. |
| `clap-dismiss` | Runs for the life of a sounding alarm. Keeps draining the microphone; feeds a `ClapDetector(2 claps, finishOnTarget)` only while `windowOpen`. Occupies `recordThread`, so normal listening cannot start meanwhile. |
| `live-test-listener` | Activity-owned visualizer loop. Independent of the service. |
| `sound-recorder` | One-shot 1.5 s template capture. |

### Alarm pulse

On a match with clap-to-silence enabled, the main-thread `Handler` cycles `openWindow` → `closeWindow`:

~~~
alarm start ──4.0 s── mute, open window ──3.0 s── unmute, close window ──3.5 s── (repeat)
~~~

`openWindow` bumps `windowId` and sets `windowOpen`; the dismiss thread sees the new id, resets the detector and discards the first 400 ms so the speaker's tail is not mistaken for a clap. The window opens only if `dismissReady` (the microphone is recording); otherwise the alarm stays loud. A match posts `silence()` to the main thread. Muting uses `MediaPlayer.setVolume(0, 0)`, so the loop keeps its position. While the alarm sounds, a screen-on event and a settings refresh (`onStartCommand`) leave the dismiss listener alone.

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
| `triggerMode` | string | `claps` or `custom` |
| `customSound` | string | Base64 of 1.5 s PCM16 mono audio (~64 KB) |
| `alarmUri` | string | URI of the chosen alarm sound |

A chosen audio file keeps a persisted read grant (`takePersistableUriPermission`). The file is not copied.

## Build and release

Gradle Kotlin DSL with a version catalog (`gradle/libs.versions.toml`). R8 and resource shrinking on release. Versions derive from `version.properties`; the central publisher overrides them via `-PversionCodeOverride` / `-PversionNameOverride`. See [RELEASE.md](RELEASE.md).
