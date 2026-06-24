# PiCap Android App

Kotlin Android client for the PiCap Raspberry Pi BLE service.

## Features

- Scan for nearby `PiCap` BLE devices
- Connect and read device status
- Trigger camera capture and OCR on the Pi
- View latest reading and stored history
- Live updates via BLE notifications
- Adjust OCR settings (mode, confidence, sensitivity) from the Settings tab
- **Regions** tab: drag calibration boxes over the two 15 Min Avg times on the live preview and save to the Pi

## Requirements

- Android 8.0+ (API 26)
- Phone with Bluetooth Low Energy
- PiCap service running on the Raspberry Pi (`python -m picap serve`)

## Open in Android Studio

1. Open Android Studio
2. **File → Open** and select the `android/` folder in this repository
3. Let Gradle sync complete
4. Connect a phone or start an emulator with BLE support (physical device recommended)
5. Run the `app` configuration

## Build from command line

```bash
cd android
./gradlew assembleDebug
```

On Windows:

```powershell
cd android
.\gradlew.bat assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Permissions

The app requests runtime permissions on first scan:

- Android 12+: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`
- Android 11 and below: `ACCESS_FINE_LOCATION` (required for BLE scanning)

## BLE integration

UUIDs match `config.example.yaml` in the parent project:

| Characteristic | UUID suffix | App usage |
|---|---|---|
| Service | `...7890` | Discovery |
| Config | `...7891` | read / write | Merge JSON config patch into Pi settings |
| Capture | `...7892` | Capture button |
| Latest | `...7893` | Latest reading card |
| History | `...7894` | History list |
| Status | `...7895` | Status card |

## Project layout

```
android/
  app/src/main/java/com/picap/mobile/
    ble/           # BLE client and UUIDs
    data/          # JSON models
    ui/            # Jetpack Compose screens
    MainActivity.kt
    PicapViewModel.kt
```

## Troubleshooting

- **Device not found**: Confirm the Pi is advertising as `PiCap` and Bluetooth is enabled on both devices.
- **Connection fails**: Run the PiCap service with sufficient BlueZ permissions; reboot Bluetooth on the Pi if needed.
- **Empty readings**: Trigger a capture from the app after connecting; verify OCR regions in `config.yaml` on the Pi.
