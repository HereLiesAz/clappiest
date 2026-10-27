# Known issues

Verified 2026-10-24 against `main` at 0.1.8 (`dedef62`) and the published APK; the entries marked fixed were changed afterwards and have not run on a device. Severity: **S1** feature does not work; **S2** wrong or risky behaviour; **S3** robustness or hygiene; **S4** polish.

## Unverified

Clap mode has never run on a physical device. Everything below that concerns it is from code reading and synthetic audio. The pipeline built and published 0.1.1–0.1.8. No CI runs the unit tests.

## Unverified design choices

- **Clap-to-silence timings** (`ALARM_LEAD_MS` 4 s, window 3 s, alarm 3.5 s, settle 400 ms) are estimates. Too short a window and the user cannot react; too long and the alarm is hard to follow to the phone. T28–T31.
- **The window mute depends on the speaker's tail ending inside the 400 ms settle.** A long reverberant room, or a slow audio route (Bluetooth), may leave residual sound that the detector treats as background or a clap.
- **Echo cancellation is device-dependent.** Android's echo canceller needs a reference of what the phone is playing; many phones only wire that up in call mode, so it may do nothing for an alarm. The app tests this itself ([DETECTION.md](DETECTION.md)); the test is untried on any device. If it wrongly says "cancelled", claps over the alarm are missed; if it wrongly says "not cancelled", the alarm pauses more than needed.
- **`VOICE_COMMUNICATION` may side-effect.** Its noise suppression and gain control can flatten claps, and on some phones a capture in this mode changes the speaker's processing or level. T39.
- **The update check works only for Google Play installs.** Play answers only for copies it installed ("owned" by the user's Google account). A copy installed from GitHub never shows the popup, and the check fails quietly. Seeing GitHub updates would need `INTERNET`, which the app deliberately does not have. T40, T43.
- **The popup does not name the new version**: Play reports a version code only. It does reflect what Play will actually offer this device (review, staged rollout), so it will not announce an update Play has not yet released.
- **A Google library is now in the app** (Play app-update 2.1.0, which brings `play-services-basement` and `play-services-tasks`). It adds no permission (T45).
- **The clap shape check keeps about 95% of claps, so a code is accepted with probability 0.95ⁿ**: 86% for 3 claps, 81% for 4, 77% for 5, if that rate holds on a phone. That is measured on applause in ESC-50, not on single claps near a phone microphone. Cupped-hand claps are lower-pitched and may fail it. T46–T52.
- **Sharp broadband sounds still pass the shape check**: can opening, clock ticks, glass, mouse clicks, keyboard typing (6–40% removed). No single-transient measure tried separates them from a clap. A 3-clap code still sees about 15 false matches per hour of that sound playing continuously. Longer codes are safer.
- **The similarity rule (centroids within 1.8×) is untested on real deliberate claps.** Real claps from one person should sound alike; if codes are rejected, raise `SIMILAR_RATIO`.
- **Clapping over a sounding alarm is not shape-checked** (`verifyShape = false`): the alarm's residue is mixed into the clap. The echo guard, two-claps rule and `fastFloor` are its only protection.
- **Percussive alarm sounds** can be heard as claps by the always-on detector; two in 1.2 s silence the alarm. T38.
- **Clap-to-silence is a short-pause alarm.** A user who finds the phone by following an unbroken tone gets gaps; the setting turns this off.

- **"When to listen" has never run on a device.** The rule logic, storage and body estimator are unit-tested on the JVM; sensors, location, the schedule alarm and the permission flow are not. T56–T70.
- **The on-body estimate is a guess.** Android gives apps no on-body sensor for phones, so it uses motion (spread of acceleration above 0.12 m/s² in a second, held for 45 s) and, for pockets, a covered proximity sensor plus body tremor (spread above 0.05 m/s², held for 90 s), the same accelerometer idea as Google's Extend Unlock on-body detection, which no app can query. Thresholds are untuned. Misreads: a phone between couch cushions with someone sitting on them looks pocketed; a phone in the pocket of someone perfectly still looks like a table; a phone on a moving train looks carried.
- **Sensors with the screen off need a wake lock** and may be throttled on some phones; with the on-body rule on, battery use is about that of listening. If sensors are throttled the rule reads "unknown" and listening continues.
- **Location with the screen off is best-effort.** Network-provider fixes every 5 minutes, and only if "Allow all the time" was granted; Android 12+ users may grant approximate location only (fine for 250 m places, poor for 100 m). A phone in Doze may deliver fixes late.
- **Reading a Wi-Fi network's name** uses `WifiManager.connectionInfo` (deprecated) because the replacement needs a network callback with location info; some Android versions return "unknown", in which case the rule is skipped.
- **The hours rule is inexact**: `setAndAllowWhileIdle` may fire minutes late in Doze.
- **Unknown means listen.** Every rule fails open, because a missed lost-phone is worse than listening where the user did not want it.
- **Background location is a Play review risk** ([PERMISSIONS.md](PERMISSIONS.md)).
- **Saved places are in Auto Backup** until `allowBackup` is turned off (issue 7).

## Reported, unconfirmed

### R1. Lock screen skipped when the screen turns on (user report, 2026-10-24)

Reported while the app was installed. **Not reproduced; cause unknown.**

Checked and clean: no `Keyguard*`, `showWhenLocked`, `turnScreenOn`, `FLAG_DISMISS_KEYGUARD`, full-screen intent, device-admin, accessibility or trust-agent code in the source; none of those in the shipped 0.1.8 manifest; the app requests no permission that can alter lock behaviour. The only screen-state work is a `SCREEN_ON`/`SCREEN_OFF`/`USER_PRESENT` receiver that starts and stops listening.

Plausible interactions, none proven: an OEM setting such as face unlock going straight to the home screen; Smart Lock or a trusted device; a voice or sensor unlock feature affected by an app holding the microphone while the screen is off; the **Stop** action on the alarm notification, which silences the alarm without unlocking (by design, and does not unlock the phone).

Decisive test: with the app force-stopped (or uninstalled), does it still happen? Record model, Android/OEM version, lock method, and whether it needs the app armed, an alarm to have played, or neither. See [TESTING.md](TESTING.md) T27.

## Issues

### 0. Steady background noise made the clap detector deaf (S1) — fixed

The background floor ignored frames louder than 3× itself, and an onset needs quiet before it (RMS under 2× the floor). After a quiet start the floor sits at its minimum of 50, so steady noise of RMS 180 or more (about −45 dBFS: a fan, a television, traffic) kept `quiet` false forever while the notification still read "Listening". Reproduced on the real `ClapDetector`: ambient RMS 170 worked, 180 and above never fired again, after 10 s or 120 s alike. Now the floor follows louder frames at 0.005 per frame (about 2 s), so it catches up with ambient noise (tests in `ClapDetectorTest`). A rejected loud sound (speech, a long hum) raises the floor briefly. Against ESC-50 the false-match count for a 3-clap code with the shape check went from 28 to 26 of 920 clips; for 2 claps from 56 to 59, for 4 from 14 to 22. T53.

### 1. Custom-sound detector cannot match a re-whistle (S1)

`CustomSoundDetector` correlates the raw waveform. A whistle is a near-pure tone, so correlation depends on phase and on pitch to a fraction of a percent. A human cannot repeat either.

Simulation: 1.5 s template (2.6 kHz whistle with 1% vibrato in light room noise); 300 re-whistles with random phase and timing; the exact Kotlin formula, 10 ms hop.

| Pitch variation (σ) | Detected at 0.55 | at 0.70 (default, s = 0.5) | at 0.85 |
|---|---|---|---|
| 0% (impossible in practice) | 30% | 25% | 11% |
| 0.5% | 1% | 0% | 0% |
| 2% | 0% | 0% | 0% |

**Fix.** Match spectrogram shape (log band energies, z-scored, correlated over the template length). Same simulation: 97–100% detection at ≤ 2% pitch variation; 0% false positives on 60 loud-noise trials and on whistles at 1.5, 1.8, 2.0, 3.2 and 3.5 kHz; 0.09 against a speech-like harmonic signal. Design in [DETECTION.md](DETECTION.md). Add JVM tests with synthetic re-whistles.

Until fixed: hide the mode, or label it Experimental, and keep it out of the Play listing.

### 2. Sensitivity is inverted in custom mode (S1)

`CustomSoundDetector.kt:12`: `threshold = 0.55 + clamp(0.3·sensitivity, 0, 0.35)`. Higher sensitivity raises the threshold, making matching stricter. Clap mode behaves the opposite. Use `threshold = 0.85 − 0.3·sensitivity` (or the tuned equivalent).

### 3. Template and detector hear different audio (S2)

`MainActivity.kt:441` records the template from `AudioSource.MIC`; detection (`MainActivity.kt:122`, `ListenerService.kt`) uses `VOICE_RECOGNITION`. The two apply different processing, so the template differs from live audio even for an identical sound. Record from the same source, via the same fallback list.

### 4. Custom mode can arm with nothing to listen for (S2)

With no recording, `ListenerService` builds a `CustomSoundDetector` over an empty array; `process()` returns `false` forever. The app shows "Listening" while it cannot trigger. Also: the template is a fixed 1.5 s with no onset trimming, no check that any sound occurred, no preview and no delete control. Block ARM in custom mode until a valid template exists; trim to the onset; add Delete.

### 5. The live visualizer outlives the screen (S2)

`MainActivity.kt:111` uses `DisposableEffect`, so the microphone thread runs until the activity is destroyed, not until it is stopped. Consequences: the microphone stays open while the app is in the background; it keeps running after you arm, so two `AudioRecord`s coexist; it never starts after permission is granted in-session because the effect keys exclude permission state. Worse, once a custom sound exists the effect's key `prefs.customSoundBytes` returns a freshly decoded `ByteArray` on every composition, and arrays compare by reference. Every visualizer update recomposes `Screen`, the key "changes", and the effect disposes and restarts: a new `AudioRecord` and thread per frame, plus a 64 KB Base64 decode on the main thread each time. Key on a stable value (a hash, or `hasCustomSound`). By code reading; confirm with T22.

Fix: tie it to `Lifecycle.Event.ON_START`/`ON_STOP`, key it on `armed` and permission, and stop it when arming. Also, `testThread` is assigned after `start()`, a small race.

### 6. The installed launcher icon is not the new artwork (S2)

`AndroidManifest.xml:12` still uses `@drawable/ic_clap`. The adaptive-icon mipmaps are present but unreferenced, so R8's resource shrinker removed them: the shipped APK's icon is `res/fL.xml` (the old vector) and contains no `mipmap` entries. Set `android:icon="@mipmap/ic_launcher"` and `android:roundIcon="@mipmap/ic_launcher_round"`. The 512 px Play icon is separate and correct.

### 7. Auto Backup includes the custom recording (S2)

`AndroidManifest.xml:11`: `allowBackup="true"` with no extraction rules, so `shared_prefs/clappiest.xml`, holding the Base64 recording, can be backed up to the user's Google Account. [PRIVACY_POLICY.md](PRIVACY_POLICY.md) discloses this. Preferable: `allowBackup="false"`, or `dataExtractionRules`/`fullBackupContent` excluding the preferences file; then delete the "Backups" section from the policy.

### 8. Silent failure when the microphone is unavailable or lost (S2) — fixed

A microphone that could not be opened, could not start, or died mid-run left the wake lock held and the notification reading "Listening in the dark"; `startRecording()` throwing took the whole app down. Now the listener thread reports back to the service, which releases the wake lock, shows "Can't reach the microphone. Trying again." and retries every 10 s while the screen is off. A match posted after listening was stopped or restarted is dropped. Not covered: a revoked permission still retries rather than stopping the service. T20, T53.

### 9. No permission rationale (S3)

The microphone prompt appears with no explanation. Add a one-screen rationale first: it listens only while armed and the screen is off, nothing is stored or sent. Improves trust and review outcomes.

### 10. Unused `ndkVersion` (S3)

`app/build.gradle.kts:50` pins NDK 29.0.14206865; the app has no native code. CI must download an NDK for nothing, and a missing one can fail the build. Remove it.

### 11. Tests are not run anywhere (S3)

The central release workflows build but do not test, and the repository has no CI. `CustomSoundDetector`, `Prefs` and service logic are untested. Add a CI workflow that runs `:app:testDebugUnitTest` (declare it with the central `ci-report` action; CI is the one workflow type a target repository may own) and tests for the custom detector.

### 12. Alarm may play through headphones at full volume (S3, safety)

`AlarmPlayer` forces the alarm stream to maximum regardless of output route. Check the active output (`AudioManager.getDevices`) and cap or route to the speaker when headphones are connected.

### 13. Controls lack accessibility semantics (S4)

The sensitivity `Slider`, the waveform and the clap-count readout carry no labels or state descriptions for TalkBack.

### 14. Stale central-run issues (S4)

GitHub issues #3 (Play package not found, resolved) and #5 (an earlier release failure) remain open. The central workflow never closes issues on success. Close them.

## Verified correct

- Foreground service declares `foregroundServiceType="microphone"` with `FOREGROUND_SERVICE_MICROPHONE`; start is user-initiated; `START_NOT_STICKY` matches the Android 11+ rule.
- Screen receiver is `RECEIVER_NOT_EXPORTED`; registered once; unregistered in `onDestroy`.
- `AlarmPlayer` restores the previous volume and falls back to the default alarm and ringtone. If no sound loads at all, it leaves the volume alone, and Stop, unlock and the notification still end the alarm.
- Release build uses R8 and produces `mapping.txt`; `versionCode`/`versionName` overrides work.
- No `INTERNET` permission in the shipped APK; permissions match [PERMISSIONS.md](PERMISSIONS.md).
- Alarm audio uses `USAGE_ALARM`, so silent and vibrate modes do not mute it.
- Play accepts uploads (versionCode 6 staged on `internal` and `alpha`).
