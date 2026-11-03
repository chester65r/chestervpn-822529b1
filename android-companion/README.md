# ChesterVPN Companion (Android)

This is an isolated native Android companion module. It is intentionally separate from the existing web app and does not modify or remove any existing tunnel-related source files.

## Included

- Native Jetpack Compose interface
- Local device compatibility diagnostics
- User-initiated copy of a minimal technical report
- In-memory activity log with a clear action
- Android 8.0+ support (minSdk 26)
- Android 15-compatible target (targetSdk 35)
- GitHub Actions debug-APK build and unit-test workflow

## Explicit scope

This module is a companion app shell, not a VPN client. It does not implement tunneling, proxying, traffic interception, carrier-package workarounds, or connectivity features. It requests no network, VPN, location, phone, or contacts permissions. Diagnostics are generated locally and are not uploaded.

## Build

Open the `android-companion` directory in Android Studio with JDK 17 and Android SDK 36 installed, then run the `app` debug build variant.

The GitHub Actions workflow can also build the debug APK and publish it as a workflow artifact when triggered on the `safe-app-shell` branch.
