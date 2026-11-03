# ChesterVPN Companion (Android)

A separate native Android companion module. It lives in `android-companion/` and is not wired into the existing web app. All additions are made on the `safe-app-shell` branch; the existing `main` branch and its files are left unchanged.

## Current features

- Native Jetpack Compose interface with Home, Activity, Settings, and About screens
- Local Android release, API-level, and app-version diagnostics
- Read-only Android network readiness summary via `ACCESS_NETWORK_STATE`
- Wi-Fi / cellular / Ethernet / Bluetooth transport classification
- Android metered-network flag
- Local activity history (up to 50 events), search, and clear action
- Persistent light/dark theme preference
- User-initiated support report generation, copy, and Android share sheet
- Unit tests for support-report contents and empty-log handling
- GitHub Actions debug APK build and test workflow

## Privacy and permissions

The manifest requests only `android.permission.ACCESS_NETWORK_STATE`.

It does **not** request Internet, VPN, location, phone, contacts, or storage permissions. The companion does not create tunnels, proxy traffic, intercept packets, change routes, or inspect destinations. It does not collect carrier names, SIM details, phone numbers, advertising identifiers, credentials, or network traffic. The network screen reads only Android's local connectivity state.

Support reports are generated only after a user action. Review the report before copying or sharing it. The app's activity history and theme preference remain in private app storage; clearing activity history removes those local events.

## Build configuration

- Minimum SDK: 26 (Android 8.0)
- Compile SDK: 36
- Target SDK: 35
- JDK: 17
- Android Gradle Plugin: 8.13.2
- Gradle: 8.13
- Kotlin: 2.3.21
- Compose BOM: 2026.09.00

The selected Kotlin Gradle plugin range supports Gradle 8.13 and AGP 8.13.2; AGP 8.13 requires Gradle 8.13 and JDK 17. See the official [Kotlin Gradle compatibility table](https://kotlinlang.org/docs/gradle-configure-project.html), [AGP 8.13 release notes](https://developer.android.com/build/releases/agp-8-13-0-release-notes), and [Gradle Java compatibility](https://docs.gradle.org/current/userguide/compatibility.html).

## Build locally

Open the `android-companion` directory in Android Studio with JDK 17 and Android SDK 36 installed, then build the `app` debug variant.

## GitHub Actions

The workflow at `.github/workflows/companion-apk.yml` runs on pushes to `safe-app-shell` affecting the companion module and can also be started manually from the Actions tab. It runs unit tests, assembles a debug APK, calculates a SHA-256 checksum, and uploads the artifact for 14 days.

The debug APK is for testing. It is not a production release and is not signed with a private release key. No signing credentials are stored in the repository.

## Scope

This is a companion utility, not a VPN client. Any future connection feature must use an explicitly authorized VPN service and must not circumvent carrier billing, package, or access controls. This repository addition does not implement or configure that functionality.
