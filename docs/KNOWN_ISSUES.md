# Known issues

Verified 2026-10-24 against `main` at 0.1.8 (`dedef62`) and the published APK. Severity: **S1** feature does not work; **S2** wrong or risky behaviour; **S3** robustness or hygiene; **S4** polish.

## Unverified

Clap mode has never run on a physical device. Everything below that concerns it is from code reading and synthetic audio. The pipeline built and published 0.1.1–0.1.8. No CI runs the unit tests.

## Unverified design choices

- **Clap-to-silence timings** (`ALARM_LEAD_MS` 4 s, window 3 s, alarm 3.5 s, settle 400 ms) are estimates. Too short a window and the user cannot react; too long and the alarm is hard to follow to the phone. T28–T31.
- **The window mute depends on the speaker's tail ending inside the 400 ms settle.** A long reverberant room, or a slow audio route (Bluetooth), may leave residual sound that the detector treats as background or a clap.
- **Clap-to-silence is a short-pause alarm.** A user who finds the phone by following an unbroken tone gets gaps; the setting turns this off.

## Reported, unconfirmed

### R1. Lock screen skipped when the screen turns on (user report, 2026-10-24)

Reported while the app was installed. **Not reproduced; cause unknown.**

Checked and clean: no `Keyguard*`, `showWhenLocked`, `turnScreenOn`, `FLAG_DISMISS_KEYGUARD`, full-screen intent, device-admin, accessibility or trust-agent code in the source; none of those in the shipped 0.1.8 manifest; the app requests no permission that can alter lock behaviour. The only screen-state work is a `SCREEN_ON`/`SCREEN_OFF`/`USER_PRESENT` receiver that starts and stops listening.

Plausible interactions, none proven: an OEM setting such as face unlock going straight to the home screen; Smart Lock or a trusted device; a voice or sensor unlock feature affected by an app holding the microphone while the screen is off; the **Stop** action on the alarm notification, which silences the alarm without unlocking (by design, and does not unlock the phone).

Decisive test: with the app force-stopped (or uninstalled), does it still happen? Record model, Android/OEM version, lock method, and whether it needs the app armed, an alarm to have played, or neither. See [TESTING.md](TESTING.md) T27.

## Issues

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

### 8. Silent failure when the microphone is unavailable or lost (S2)

`ListenerService.kt:145` returns from the thread if no `AudioRecord` initialises, yet the notification still reads "Listening in the dark". A call or another app that takes the microphone, or a revoked permission, leaves the same false state. Surface the failure (notification text, retry with backoff, `AudioRecord` state callback), and stop the service if permission is gone.

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
- `AlarmPlayer` restores the previous volume and falls back to the default alarm and ringtone.
- Release build uses R8 and produces `mapping.txt`; `versionCode`/`versionName` overrides work.
- No `INTERNET` permission in the shipped APK; permissions match [PERMISSIONS.md](PERMISSIONS.md).
- Alarm audio uses `USAGE_ALARM`, so silent and vibrate modes do not mute it.
- Play accepts uploads (versionCode 6 staged on `internal` and `alpha`).
