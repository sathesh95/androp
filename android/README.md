# Native Android Clipboard Sync App

A lightweight, background-resilient Android companion app designed to sync clipboard content with your Mac in real-time with zero battery impact.

## Architecture & Background Resilience
- **`AccessibilityService` (`ClipboardAccessibilityService`)**: Highest process priority in Android OS (`PERSISTENT_PROC`). Never killed by OEM battery optimizers, intercepts copy events instantly in the background across all apps.
- **`ForegroundService` (`SyncForegroundService`)**: Maintains WebSocket connection with `START_STICKY` and low-priority notification channel.
- **`BootReceiver`**: Automatically resumes clipboard sync when the phone restarts.
- **`CameraX` + ML Kit**: Rapid, 1-time setup via Mac screen QR code.
- **AES-256-GCM Hardware Keystore**: Hardware-backed cryptographic keys.

## Building the APK

### Option 1: Open in Android Studio
1. Open Android Studio.
2. Select **Open** and select the `/Users/nithin/Desktop/copyandroidmac/android` folder.
3. Click **Run 'app'** or **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

### Option 2: Build via Terminal (Command Line)
If you have the Android SDK configured:
```bash
cd android
./gradlew assembleDebug
```
The compiled APK will be located at:
`android/app/build/outputs/apk/debug/app-debug.apk`

## Quick Setup on Phone
1. Install the APK on your Android device.
2. Open **Clipboard Sync**.
3. Tap **Enable** next to Accessibility Service and toggle it on in Settings.
4. Tap **Whitelist** next to Battery Optimization.
5. Tap **📷 Scan Mac Pairing QR Code** and scan the QR code from your Mac menu bar.
6. Done! Everything you copy on Mac will instantly appear on your phone, and vice-versa.
