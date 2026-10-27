# Development

## Requirements

| Tool | Version |
|---|---|
| JDK | 21 |
| Android SDK | Platform 37, build-tools 35+ |
| Gradle | Wrapper (`./gradlew`) |
| AGP / Kotlin | 9.4.1 / 2.2.10 (`gradle/libs.versions.toml`) |

No NDK is installed by hand, but keep `ndkVersion` in `app/build.gradle.kts`: Compose ships a small native library (`libandroidx.graphics.path.so`) whose symbols the release build strips with the pinned NDK (KNOWN_ISSUES #10).

Create `local.properties` (git-ignored) with `sdk.dir=/path/to/Android/Sdk`.

## Commands

~~~
./gradlew :app:testDebugUnitTest      # JVM unit tests
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:assembleRelease        # R8-shrunk, unsigned unless keys are injected
./gradlew :app:bundleRelease          # AAB
~~~

Pass `-PversionCodeOverride=42 -PversionNameOverride=0.1.0.7` to mimic the publisher.

## Tests

`ClapDetectorTest` generates synthetic PCM (impulse-with-decay claps, a sustained tone, a reverberant clap) and asserts: exact count matches, too few/many do not, separated groups do not combine, sustained sound is ignored, repeats trigger again, distant reverberant claps trigger.

Gaps: no tests for `CustomSoundDetector`, `Prefs`, or service logic. `.github/workflows/ci.yml` runs the unit tests on pull requests and pushes to `main`; the central release workflows only build, so require that check in branch protection. See KNOWN_ISSUES #11.

## Conventions

- Kotlin official style, 4-space indent (`.editorconfig`). KDoc on every class and non-obvious function.
- Detectors stay free of Android types so they remain JVM-testable.
- The microphone is opened in exactly two places that should stay in step: `ListenerService` and the live visualizer. Both use `VOICE_RECOGNITION` first.
- Documentation changes with the code in the same PR. Markdown code blocks use tildes (`~~~`).
- `version.properties` is canonical; the central sync commits to it with `[skip ci]`. Do not bump it by hand.
- Release workflows live in the workflows repo; local files are trackers ([RELEASE.md](RELEASE.md)).

## Pull requests

Opening a PR does not publish. **Merging to `main` publishes** to GitHub and Play when `app/**`, `gradle/**`, `*.gradle.kts`, `gradle.properties` or `version.properties` change. Merge docs separately from code when you want no release.

## Build environment notes

- Resolve dependencies from Google Maven and Maven Central. If Central returns HTTP 429, retry; a mirror such as `https://maven-central.storage-download.googleapis.com/maven2/` also works as a temporary fallback.
- `gradle.properties` carries AGP 9 migration opt-outs (`android.newDsl=false`, `android.builtInKotlin=false`, …). They are temporary; remove them as AGP drops support.
