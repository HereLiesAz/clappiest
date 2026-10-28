# Documentation

*The Clappiest* (`com.hereliesaz.clappiest`) finds a lost phone: clap a set number of times while the screen is off and it sounds an alarm.

| Document | Audience | Purpose |
|---|---|---|
| [USER_GUIDE.md](USER_GUIDE.md) | Users | Setup, use, battery settings, troubleshooting |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Developers | Components, lifecycle, threading, data |
| [DETECTION.md](DETECTION.md) | Developers | Clap and custom-sound algorithms, parameters, tuning |
| [PERMISSIONS.md](PERMISSIONS.md) | Developers, Play review | Every permission and why |
| [PRIVACY_POLICY.md](PRIVACY_POLICY.md) | Users, Play Console | Public privacy policy |
| [PLAY_STORE_LISTING.md](PLAY_STORE_LISTING.md) | Publisher | Listing copy, graphics, declarations, form answers |
| [RELEASE.md](RELEASE.md) | Publisher | Central release pipeline, secrets, versioning, recovery |
| [DEVELOPMENT.md](DEVELOPMENT.md) | Developers | Build, test, conventions |
| [TESTING.md](TESTING.md) | Developers, QA | On-device test plan |
| [KNOWN_ISSUES.md](KNOWN_ISSUES.md) | Everyone | Verified defects and gaps, ranked, with fixes |

## Status (2026-10-24)

| Area | State |
|---|---|
| Clap mode | Implemented. JVM-tested. Verifies that a sound actually sounds like a clap (measured on real recordings: 95% of claps kept, 72% fewer false matches for a 3-clap code). **Not yet verified on a device.** |
| Custom-sound mode | Hidden (`Features.CUSTOM_SOUND = false`) until rebuilt; see KNOWN_ISSUES #1. |
| Update popup | Implemented. Asks Google Play (no internet permission); Play-installed copies only. **Not yet verified on a device.** |
| GitHub Releases | Automatic on every merge to `main` touching the app. |
| Google Play | Builds reach `internal` and `alpha` as drafts. `beta` and `production` blocked until the store listing is complete. |
| Privacy policy | Drafted. Needs a public URL and a contact email. |

The copy in PLAY_STORE_LISTING.md omits custom-sound mode, which is hidden.
