# Clappiest

Lost your phone in the dark? Clap. It screams back.

Android app (`com.hereliesaz.clappiest`). While armed and the screen is off, it listens for an exact number of claps and plays an alarm sound of your choosing at full alarm volume — silent mode notwithstanding.

**Documentation: [docs/](docs/README.md)** — user guide, architecture, detection, permissions, privacy policy, Play listing, release, testing, known issues.

## Use

1. Set **Claps** (2–10). The sequence must match exactly; four claps won't fire a three-clap setting.
2. Set **Sensitivity**. Higher hears softer claps and forgives more noise.
3. Pick a **Sound**: a system tone, or any audio file.
4. **ARM**. A persistent notification confirms it is listening.

Silence the alarm by unlocking the phone, tapping **Stop** on the notification, or clapping twice. The alarm pauses a second between replays, tries to cancel itself out of the microphone, and where that fails goes quiet every few seconds so the claps can be heard; turn this off with "Clap twice to silence the alarm".

## How it works

| File | Role |
|---|---|
| `ClapDetector.kt` | Pure-Kotlin impulse detector. A clap = sharp onset above the adaptive noise floor that decays within 120 ms. Claps 120–1200 ms apart form one sequence; the sequence is judged after 1.2 s of quiet. |
| `ListenerService.kt` | Microphone foreground service. Records only while the screen is off; stops when it turns on. On match: alarm + notification. |
| `AlarmPlayer.kt` | Looping `MediaPlayer` on the alarm stream at max volume; restores prior volume afterward. |
| `Prefs.kt` | Clap count, sensitivity, sound URI. |
| `MainActivity.kt` | Compose settings screen and arm switch. |

## Updates

On open, the app asks GitHub for the latest release (at most every 12 hours) and shows a popup when a newer version exists. **Update** opens Google Play or the GitHub release, whichever it was installed from. Switch it off with "Check for updates"; it then makes no network requests. This is the app's only network use; audio never leaves the phone.

## Limits

- Android forbids starting microphone services from the background, so after a reboot or the app being killed, open it and re-arm.
- Listening holds a partial wake lock while the screen is off. Battery will notice.

## Build

~~~
./gradlew :app:testDebugUnitTest :app:assembleDebug
~~~

Version state lives in `version.properties`.

## Release

Every push to `main` touching the app is built, signed and published centrally by `HereLiesAz/workflows`:

- `.github/workflows/android-github-release.yml` → GitHub Release with the signed APK.
- `.github/workflows/android-play-release.yml` → Google Play on the shared tracks.

Those files are trigger contracts only; the central sync replaces them with trackers. Signing and Play
credentials live in the `clappiest` environment of `HereLiesAz/workflows` (`KEYSTORE_PRIVATE`,
`KEYSTORE_CHAIN`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, `PLAY_SERVICE_ACCOUNT_JSON`).
The publisher passes `versionCodeOverride` / `versionNameOverride`; release builds run R8 because Play
uploads require `mapping.txt`.
