# Privacy Policy

**The Clappiest** (`com.hereliesaz.clappiest`)
Developer: HereLiesAz
Effective: 2026-10-24

## Summary

- The app listens through your microphone to hear your clap code. It analyses the sound in memory and discards it.
- It has **no internet permission**. It cannot send anything off your phone.
- No account, no ads, no analytics, no tracking, no third-party code that collects data.
- The developer collects nothing and holds nothing about you.

## What the app does with your microphone

When you tap **ARM**, and whenever the screen is off, the app listens through the microphone to detect the number of claps you set. While the alarm is sounding it also listens, in short pauses, for two claps so you can silence it (you can turn this off in the app). When the app is open and disarmed, the main screen listens briefly to draw a live waveform.

Audio is read in short pieces, measured, and discarded straight away. It is never written to storage and never transmitted.

Android shows a notification, and on recent versions a microphone indicator, whenever the app listens.

## What the app stores on your phone

Stored only in the app's private storage:

| Data | Why |
|---|---|
| Clap count, sensitivity, trigger mode | Remember your settings |
| Alarm sound choice (a reference to a system sound or a file you picked) | Play your chosen sound |
| **Custom sound recording** (about 1.5 seconds of audio), only if you record one in Custom Sound mode | Compare live sound against it |

The custom recording is the only audio the app ever saves. It stays on your phone and is used only for matching. Chosen alarm files are referenced, not copied.

## Backups

Android's Auto Backup may copy the app's stored data, including a custom sound recording if you made one, to your Google Account, according to your device's backup settings. That backup is handled by Google under Google's terms. The developer cannot access it. To avoid it, turn off backup in Android settings or do not record a custom sound.

## What the app does not do

- No network access, so no uploading, syncing or remote logging.
- No location, contacts, camera, calls, messages, or advertising ID.
- No analytics, crash-reporting or advertising libraries.
- No sale, sharing or disclosure of data. There is none to share.

## Permissions

| Permission | Used for |
|---|---|
| Microphone | Hearing your clap code |
| Notifications | The required "listening" notice and the alarm's Stop button |
| Foreground service (microphone) and wake lock | Listening reliably with the screen off |

Details: [PERMISSIONS.md](PERMISSIONS.md).

## Google Play

The app is distributed through Google Play. Google processes install and device information under its own privacy policy. The developer receives only aggregated statistics from Play Console.

## Retention and deletion

- Settings and the custom recording remain until you clear the app's storage or uninstall it.
- To remove them: Android Settings → Apps → The Clappiest → Storage → **Clear storage**, or uninstall.
- Because the developer holds no data about you, there is nothing to request, correct or delete from the developer.

## Your rights

Wherever privacy law (such as the GDPR, UK GDPR or CCPA/CPRA) gives you rights of access, correction, deletion, portability or objection, they have nothing to attach to here: the developer does not collect or hold personal data. The developer does not sell or share personal information and does not use it for profiling.

## Children

The app is not directed at children under 13 and does not knowingly collect data from anyone.

## Security

Data stays in Android's per-app sandbox. Nothing leaves the device, so there is no transmission to intercept.

## Changes

If this policy changes, the new version will be published at the same address with a new effective date. If a future version adds any data collection, the app will ask first and this policy will say so before that version ships.

## Contact

Questions: open an issue at https://github.com/HereLiesAz/clappiest/issues
Email: **[CONTACT EMAIL: required by Google Play; add before publishing]**
