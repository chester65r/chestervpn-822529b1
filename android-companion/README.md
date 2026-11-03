# ChesterVPN Companion (Android)

A standalone native Android companion module. It lives in `android-companion/` and is intentionally separate from the existing web app. All lasting app source additions are on the `safe-app-shell` branch; the current `main` tree contains no companion module or handoff document.

## Features in this companion build

- Native Jetpack Compose interface with Home, Activity, Settings, and About screens
- Local Android release, API-level, and app-version diagnostics
- Read-only Android network readiness summary via `ACCESS_NETWORK_STATE`
- Wi-Fi / cellular / Ethernet / Bluetooth transport classification
- Android's metered-network flag
- Local activity history (up to 50 events), filter, and clear action
- Persistent light/dark theme preference
- User-initiated support report generation, copy, and Android share sheet
- Unit tests for diagnostics and support-report formatting
- GitHub Actions workflow for local-only scope verification, unit tests, lint, debug APK, SHA-256 checksum, and artifact upload

## Privacy and permissions

The manifest requests only `android.permission.ACCESS_NETWORK_STATE`.

`androidx.core:core-ktx:1.17.0` is an explicit dependency because the app calls `WindowCompat` directly; do not rely on it arriving only as a transitive dependency.

It does **not** request Internet, VPN, location, phone, contacts, or storage permissions. This companion does not create tunnels, proxy traffic, intercept packets, change routes, or inspect destinations. It does not collect carrier names, SIM details, phone numbers, advertising identifiers, credentials, or network traffic. The network panel reads only Android's local connectivity state.

Support reports are generated only after a user action. Review the report before copying or sharing it. Activity history and theme preference remain in private app storage. Android backup is disabled for this module.

## Build configuration

- Minimum SDK: 26 (Android 8.0)
- Compile SDK: 36
- Target SDK: 36 (Android 16)
- JDK: 17
- Android Gradle Plugin: 8.13.2
- Gradle: 8.13
- Kotlin: 2.3.21
- Compose BOM: 2026.06.01
- Activity Compose: 1.13.0
- AndroidX Core: 1.17.0

Target SDK 36 is selected to meet the Google Play requirement for new mobile apps and updates from August 31, 2026. Targeting API 36 also requires testing Android 16 edge-to-edge behavior; the activity enables edge-to-edge and uses Material 3 Scaffold/TopAppBar/NavigationBar insets.

Compose 1.12 targets API 37 and requires AGP 9.1.1; this project stays on API 36 / AGP 8.13.2, so it pins Compose BOM 2026.06.01 (Compose 1.11.x). Do not upgrade the BOM to 2026.08.00 or newer without also deliberately upgrading compileSdk and AGP. The Kotlin Gradle plugin 2.3.21 compatibility range includes AGP 8.13.2 and Gradle 8.13. AGP 8.13 requires Gradle 8.13. See the official [Kotlin Gradle compatibility table](https://kotlinlang.org/docs/gradle-configure-project.html), [Android Gradle Plugin compatibility table](https://developer.android.com/build/releases/about-agp), [Google Play target API requirement](https://developer.android.com/google/play/requirements/target-sdk), and [Compose BOM guide](https://developer.android.com/develop/ui/compose/bom).

## Build locally

Open the `android-companion` directory in Android Studio with JDK 17 and Android SDK 36 / Build Tools 36.0.0 installed. Before building, run `python3 scripts/verify_scope.py` from this directory. Then build the `app` debug variant.

The current project does not yet check in the Gradle Wrapper scripts/JAR. Until those are added, use a local Gradle 8.13 installation or Android Studio's configured Gradle distribution. Adding a checked-in Gradle Wrapper is a recommended next maintenance task.

## GitHub Actions

The workflow at `.github/workflows/companion-apk.yml` runs on relevant source/configuration pushes to `safe-app-shell`, pull requests targeting `main`, and manual dispatch. It first runs `scripts/verify_scope.py` to assert that the manifest still requests only `ACCESS_NETWORK_STATE`, disables cleartext and backup, and declares no Android VPN service. It then uses the hosted runner's preinstalled Android SDK, installs API 36 / Build Tools 36.0.0, runs unit tests and lint, assembles the debug APK, generates a SHA-256 checksum, and uploads the artifact for 14 days.

The debug APK is for testing. It is not a production release and is not signed with a private release key. No signing credentials are stored in the repository.

## Scope

This is a companion utility, not a VPN client. Any future connection feature must use an explicitly authorized service and must not circumvent carrier billing, package, or access controls. This module does not implement or configure tunneling functionality.
