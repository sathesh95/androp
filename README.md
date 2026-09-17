# Mac <-> Android Robust Clipboard Sync

A zero-knowledge, end-to-end encrypted (AES-256-GCM), ultra-lightweight clipboard synchronization system between macOS and Android.

---

## Highlights & Features

- **Apple Universal Clipboard Experience**: Instant sync from Mac to Android and Android to Mac.
- **Zero-Knowledge Security**: AES-256-GCM encryption with keys generated locally on Mac and exchanged via 1-time camera QR scan. Encryption keys never touch the internet.
- **Unkillable Background Persistence**:
  - **macOS**: `SMAppService` Launch-at-Login daemon.
  - **Android**: `AccessibilityService` (system-monitored, never killed by Android OS) + `ForegroundService` with `START_STICKY` + `BOOT_COMPLETED` restart.
- **Ultra-Lightweight**:
  - macOS app: ~10MB RAM, 0.0% CPU.
  - Android app: ~18MB RAM, < 0.1% Battery.
- **$0 Cloud Cost**: Cloudflare Worker WebSocket relay with Hibernation API.
- **Offline / Catch-Up Buffer**: Waking up a laptop or reconnecting a phone instantly catches up on the latest copied item without waiting for a new copy event.

---

## Directory Structure

```
.
├── relay/      # Cloudflare Worker E2EE WebSocket Relay ($0 / Hibernation)
├── macos/      # Native Swift 6 / SwiftUI Menu Bar Accessory (macOS 13, 14, 15 Sequoia)
└── android/    # Native Kotlin Android App (Jetpack, Accessibility, CameraX)
```

---

## Quick Start Guide

### 1. Deploy the Free Cloudflare Relay (Optional / Recommended)
```bash
cd relay
npm install
npx wrangler deploy
```
*Take note of the worker URL provided by wrangler (e.g. `wss://clipboard-sync-relay.<your-name>.workers.dev/ws`).*

### 2. Run / Build the macOS Menu Bar App
```bash
cd macos
./build_app.sh
```
*Run `ClipboardSync.app` or `swift run`. A menu bar icon will appear. Click it to reveal your 1-time setup QR code.*

### 3. Build & Install Android App
*Open the `android/` directory in Android Studio and hit **Run**, or build via command line (`./gradlew assembleDebug`).*
*On your phone:*
1. Open the app and grant Accessibility & Battery Optimization exemptions.
2. Tap **📷 Scan Mac Pairing QR Code** and scan the QR code on your Mac screen.
3. Done! Everything you copy on Mac will sync to Android and vice versa seamlessly.
