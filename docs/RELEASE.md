# Release

Builds, signing and publishing run centrally in [HereLiesAz/workflows](https://github.com/HereLiesAz/workflows). This repository holds no signing keys and no build logic for release.

## Flow

~~~
merge to main (touching app/, gradle/, *.gradle.kts, gradle.properties, version.properties)
  → repository webhook → central gateway
      ├─ android-github-release.yml → signed APK → GitHub Release  (tag MAJOR.MINOR.PATCH)
      └─ android-play-release.yml   → signed AAB + R8 mapping.txt → Google Play
  → commit statuses on the merge commit; failures open/append a "Central run failing" issue
  → "chore(version)" commit [skip ci] records the new version
~~~

Documentation-only merges (`docs/**`, `README.md`) do not match the path filters and publish nothing.

## Files in this repo

| File | Purpose |
|---|---|
| `.github/workflows/android-github-release.yml` | Trigger contract (paths, branch). After sync it is a *tracker* that only hands off. |
| `.github/workflows/android-play-release.yml` | Same, for Play. |
| `.github/workflow-request.yml` | Lists `android-github-release` and `android-play-release` as requested. |
| `version.properties` | Canonical version state. The central sync rewrites it. |

**Do not hand-edit a tracker.** The central catalog admits a workflow by the SHA-256 of its registered source. If a trigger file's content changes (for example its path filters), the sync blocks it with *"New workflow policy blocked unsubmitted target-repository workflow(s)"* until the new hash and profile are added to `scripts/semantic_catalog.py` in the workflows repo (see PR #166 for the pattern).

## Central profile

In `scripts/semantic_catalog.py` for `com.hereliesaz.clappiest`:

| Setting | Play | GitHub |
|---|---|---|
| Signing | `pem-chain` | `pem-chain` |
| Build | `./gradlew :app:bundleRelease` | `./gradlew :app:assembleRelease` |
| Artifact | `app/build/outputs/bundle/release/*.aab` | `app/build/outputs/apk/release/*.apk` |
| R8 mapping | `app/build/outputs/mapping/release/mapping.txt` (required) | same |
| Tracks | `internal`, `alpha`, `beta` completed; `production` draft, existing releases preserved | n/a |

## Secrets

Environment `clappiest` in the **workflows** repository (never here):

| Secret | Purpose |
|---|---|
| `KEYSTORE_PRIVATE` | Upload key, PEM private key |
| `KEYSTORE_CHAIN` | Upload certificate chain, PEM |
| `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | Keystore assembly and signing |
| `PLAY_SERVICE_ACCOUNT_JSON` | Play Developer API access (publish, read version codes) |

Lose the upload key and you must request an upload-key reset in Play Console. Back it up offline.

## Versioning

- `version.properties`: `versionMajor.versionMinor.versionPatch.versionBuild` (see the file; the pipeline bumps it).
- Git tag and GitHub Release: `MAJOR.MINOR.PATCH`; asset name carries the full four-part version.
- **Play `versionCode` is chosen by the publisher:** the larger of the repository's recorded code and every code Play has ever accepted, plus one. `app/build.gradle.kts` honours `-PversionCodeOverride` / `-PversionNameOverride`; locally it derives `major·10⁶ + minor·10⁴ + patch·10² + build`.
- Never lower the version or reuse a code. Play rejects both.

## Run manually

Workflows repo → **Actions → Central workflow gateway → Run workflow**:

| Input | Value |
|---|---|
| `repository` | `clappiest` |
| `source_workflow_path` | `android-play-release.yml` or `android-github-release.yml` |
| `target_ref` | blank for `main` |

To re-register after changing trigger files: **Sync repository workflows** with `repository = clappiest`.

## Verify a release

1. GitHub: the Release exists, tag `X.Y.Z`, asset `Clappiest-X.Y.Z.B-release.apk`.
2. Play Console → Release → Testing → Internal/Closed: the new `versionCode` is present and `mapping.txt` is attached (Quality → Android vitals → Deobfuscation files).
3. Install the APK; check Settings → About shows the version.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `Package not found: com.hereliesaz.clappiest` | App not created in Play Console | Create the app with that exact package name and upload one build manually |
| *"is a draft app on Play … Only releases with status draft may be created"* | Draft app | Expected until the listing and declarations are complete; build is staged as a draft |
| `Play track 'beta' not published … Precondition check failed` | Track not set up or listing incomplete | Complete [PLAY_STORE_LISTING.md](PLAY_STORE_LISTING.md) |
| `New workflow policy blocked unsubmitted …` | Trigger file hash not in the catalog | Add the hash and profile in the workflows repo |
| `Required mapping.txt is missing or empty` | R8 disabled or build output path changed | Keep `isMinifyEnabled = true`; update `mapping_file` in the profile if paths change |
| `KEYSTORE_PRIVATE/KEYSTORE_RSA and KEYSTORE_CHAIN are required` | Secrets absent from the `clappiest` environment | Add them |
| `no failed step reported` on a GitHub release | Runner or infrastructure failure before a step ran | Re-run once from the gateway; a second failure is real |
| Stale "Central run failing" issue | The workflow appends to the issue on failure and never closes it on success | Close it manually after a passing run |
| Build fails on `ndkVersion` | The pinned NDK is missing on the runner; it is used to strip the native library Compose ships | Install that NDK version on the runner; do not remove the pin (KNOWN_ISSUES #10) |

## Rollback

Play does not allow deleting a published version code. Halt a bad rollout in Play Console and ship a higher version. For GitHub, mark the Release as pre-release or delete the asset; the tag stays (exact-build tags are immutable by policy).
