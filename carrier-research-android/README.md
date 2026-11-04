# ChesterVPN Carrier Research (Android)

A separate Android research client for authorized STC/Mobily network measurements and defensive policy testing. It is intentionally separate from the existing local-only companion module.

## Implemented in this stage

- Native Jetpack Compose application (Android 8.0+, target API 36).
- Live read-only Android network snapshot: active transport, Internet capability, Android validation, and metered state.
- Manual STC / Mobily / Other test label and a user-declared package profile.
- Deterministic defensive policy suite covering no-plan denial, social-only boundaries, subscriber-session binding, IPv6 policy consistency, and unauthorized relays.
- One HTTPS GET to a tester-owned /healthz endpoint after explicit authorization confirmation.
- Standard platform TLS validation, no redirects, no credentials, and a public DNS hostname requirement.
- Shareable evidence report that clearly separates live Android measurements from synthetic policy results.

## Deliberate boundaries

This is **not yet a VPN client** and does not create a tunnel, proxy traffic, alter routing, inspect packet contents, or circumvent carrier billing/package controls. The provider and package labels are user-selected and are not read from carrier subscriber systems. The policy suite is a local deterministic test suite, not proof of a carrier vulnerability.

The endpoint check is a single read-only HTTPS health request to a server the tester owns or is explicitly authorized to test. It does not scan destinations, follow redirects, send credentials, or upload collected SIM or subscriber identifiers.

## Build

Open carrier-research-android/ as a standalone Android project with:

- JDK 17
- Android SDK platform 36
- Android Build Tools 36.0.0
- Gradle 8.13
- Android Gradle Plugin 8.13.2
- Kotlin 2.3.21

Run:

    gradle --no-daemon -p carrier-research-android testDebugUnitTest lintDebug assembleDebug

The debug APK is a test build, not a signed production release.

## Physical SIM test procedure

1. Install the APK on a disposable Android test device.
2. Select the carrier label matching the SIM currently providing mobile data.
3. Select the package label based on the tester's own subscription information.
4. Turn Wi-Fi off manually, refresh the Android network snapshot, and confirm the transport reads Cellular.
5. Enter the HTTPS /healthz endpoint of the authorized research server and confirm the authorization checkbox.
6. Run exactly one health check and export the evidence report.
7. Repeat independently on the other carrier SIM, recording the device, Android version, timestamp, and subscription label.

A validated network or a successful health endpoint proves ordinary endpoint reachability only. It does not prove package entitlement, data accounting, zero-rating behavior, or a billing vulnerability. Any carrier enforcement assessment requires a formally authorized test environment and carrier-side logs or counters.
