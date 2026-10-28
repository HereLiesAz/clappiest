# Permissions

Verified against the shipped 0.1.8 APK (`aapt2 dump permissions`); the "When to listen" permissions below were added afterwards and are not yet in a shipped APK. The app declares **no** `INTERNET` permission.

| Permission | Type | Why | Without it |
|---|---|---|---|
| `RECORD_AUDIO` | Runtime, dangerous | Listen for the clap code | App cannot function; UI shows "No microphone, no miracle." |
| `FOREGROUND_SERVICE` | Normal | Run `ListenerService` in the foreground | Service cannot start |
| `FOREGROUND_SERVICE_MICROPHONE` | Normal (API 34+) | Required for a foreground service of type `microphone` | Service crashes on API 34+ when it starts |
| `POST_NOTIFICATIONS` | Runtime (API 33+) | Show the mandatory foreground notification and the alarm notification | Notification hidden; the service still runs, but the user has no visible indicator or Stop control |
| `WAKE_LOCK` | Normal | Keep the CPU running while listening with the screen off | Android suspends the CPU and listening stops |
| `ACCESS_NETWORK_STATE` | Normal | Notice Wi-Fi connecting and disconnecting, for the Wi-Fi rule | The Wi-Fi rule is not re-checked when the network changes |
| `ACCESS_WIFI_STATE` | Normal | Read the connected network's name, for the Wi-Fi rule | The Wi-Fi rule cannot be checked |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Runtime, dangerous. **Optional**: asked only when the user adds a place or Wi-Fi rule | Check whether the phone is at a saved place; Android also requires it to read a Wi-Fi network's name | Place and Wi-Fi rules cannot be checked, and then never stop the app listening |
| `ACCESS_BACKGROUND_LOCATION` | Runtime, dangerous (API 29+). **Optional**, as above | Do those checks while the screen is off, which is the only time the app listens | Place and Wi-Fi rules only work while the app is on screen |
| `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Signature, auto-added by AndroidX | Protects the screen-state receiver registered with `RECEIVER_NOT_EXPORTED` | n/a |

Google's Play app-update library, which powers the update check, adds no permission; the built APK's permission list is exactly the table above. The check is made by the Play Store app on the phone, not over the network by this app.

## Declared features

`android.hardware.microphone` (implied by `RECORD_AUDIO`). The Play listing will therefore exclude devices without a microphone. Add `<uses-feature android:name="android.hardware.microphone" android:required="true"/>` to make this explicit.

Location, Wi-Fi, accelerometer and proximity are declared `android:required="false"`, because the location and Wi-Fi permissions would otherwise make Play hide the app from phones without them. The accelerometer and proximity sensors need no permission.

## Not requested, and why

| Permission | Reason not needed |
|---|---|
| `INTERNET` | Nothing is sent or fetched |
| `ACTIVITY_RECOGNITION`, `BODY_SENSORS` | The on-body rule reads the accelerometer and proximity sensors directly, which need no permission. `BODY_SENSORS` is for heart-rate style sensors and does not apply |
| `BLUETOOTH_CONNECT` | Bluetooth audio counts as headphones through `AudioManager`, which needs no permission |
| `SCHEDULE_EXACT_ALARM` | The hours rule uses an inexact alarm; it may switch a few minutes late |
| `READ_EXTERNAL_STORAGE` / `READ_MEDIA_AUDIO` | The system file picker (`OpenDocument`) grants access to the one chosen file |
| `RECEIVE_BOOT_COMPLETED` | A microphone foreground service cannot start from boot (Android 11+) |
| `USE_FULL_SCREEN_INTENT`, `SYSTEM_ALERT_WINDOW` | The alarm needs only sound and a notification |
| `ACCESS_NOTIFICATION_POLICY` | Alarm-category audio already passes Do Not Disturb by default |
| Contacts, camera, phone, SMS | Unrelated |

## Play Console mapping

- `RECORD_AUDIO` is a sensitive runtime permission, not a restricted one. No permission-declaration form applies.
- `FOREGROUND_SERVICE_MICROPHONE` requires the **Foreground service permissions** declaration; wording in [PLAY_STORE_LISTING.md](PLAY_STORE_LISTING.md).
- `ACCESS_BACKGROUND_LOCATION` is a **restricted** permission. Play Console requires the **Location permissions** declaration form, an in-app prominent disclosure (the app shows one before asking, in `RulesActivity`) and a demo video of the feature. Wording and video steps in [PLAY_STORE_LISTING.md](PLAY_STORE_LISTING.md). If that review is not worth it, remove the place and Wi-Fi rules and these three permissions; the other rules do not need them.
- No Advertising ID declaration beyond answering "No".
