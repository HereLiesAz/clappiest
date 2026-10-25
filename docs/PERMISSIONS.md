# Permissions

Verified against the shipped 0.1.8 APK (`aapt2 dump permissions`). The app declares **no** `INTERNET` permission.

| Permission | Type | Why | Without it |
|---|---|---|---|
| `RECORD_AUDIO` | Runtime, dangerous | Listen for the clap code | App cannot function; UI shows "No microphone, no miracle." |
| `FOREGROUND_SERVICE` | Normal | Run `ListenerService` in the foreground | Service cannot start |
| `FOREGROUND_SERVICE_MICROPHONE` | Normal (API 34+) | Required for a foreground service of type `microphone` | Service crashes on API 34+ when it starts |
| `POST_NOTIFICATIONS` | Runtime (API 33+) | Show the mandatory foreground notification and the alarm notification | Notification hidden; the service still runs, but the user has no visible indicator or Stop control |
| `WAKE_LOCK` | Normal | Keep the CPU running while listening with the screen off | Android suspends the CPU and listening stops |
| `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Signature, auto-added by AndroidX | Protects the screen-state receiver registered with `RECEIVER_NOT_EXPORTED` | n/a |

Google's Play app-update library, which powers the update check, adds no permission; the built APK's permission list is exactly the table above. The check is made by the Play Store app on the phone, not over the network by this app.

## Declared features

`android.hardware.microphone` (implied by `RECORD_AUDIO`). The Play listing will therefore exclude devices without a microphone. Add `<uses-feature android:name="android.hardware.microphone" android:required="true"/>` to make this explicit.

## Not requested, and why

| Permission | Reason not needed |
|---|---|
| `INTERNET` / `ACCESS_NETWORK_STATE` | Nothing is sent or fetched |
| `READ_EXTERNAL_STORAGE` / `READ_MEDIA_AUDIO` | The system file picker (`OpenDocument`) grants access to the one chosen file |
| `RECEIVE_BOOT_COMPLETED` | A microphone foreground service cannot start from boot (Android 11+) |
| `USE_FULL_SCREEN_INTENT`, `SYSTEM_ALERT_WINDOW` | The alarm needs only sound and a notification |
| `ACCESS_NOTIFICATION_POLICY` | Alarm-category audio already passes Do Not Disturb by default |
| Location, contacts, camera, phone, SMS | Unrelated |

## Play Console mapping

- `RECORD_AUDIO` is a sensitive runtime permission, not a restricted one. No permission-declaration form applies.
- `FOREGROUND_SERVICE_MICROPHONE` requires the **Foreground service permissions** declaration; wording in [PLAY_STORE_LISTING.md](PLAY_STORE_LISTING.md).
- No Advertising ID declaration beyond answering "No".
