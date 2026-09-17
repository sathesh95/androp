# Native macOS Clipboard Sync Daemon

A pure native Swift 6 / SwiftUI menu bar accessory (`LSUIElement`) that synchronizes clipboard state in real-time with zero noticeable resource footprint.

## Features
- **Zero Overhead**: ~10–15 MB RAM, 0.0% CPU when idle.
- **Hardware-Accelerated AES-256-GCM**: Built on Apple's `CryptoKit` and Apple Silicon AES instructions.
- **Keychain Storage**: Room IDs and encryption keys are stored securely in the macOS Keychain.
- **Loop Prevention**: SHA-256 ring buffer prevents feedback echo loops.
- **Password Protection**: Automatically ignores passwords copied from 1Password, Bitwarden, and Apple Keychain.
- **Auto-Start**: Supports macOS `SMAppService` launch-at-login.

## Building & Running

### Option 1: Run directly from command line
```bash
cd macos
swift run
```

### Option 2: Build `.app` bundle for `/Applications`
```bash
cd macos
./build_app.sh
```
This produces `macos/build/ClipboardSync.app` which you can move to `/Applications`.
