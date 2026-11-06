# Android emulator verification — 2026-11-01 UTC

## Installation retry — 14:35 UTC

- Full repeatable emulator lane: **PASS**, evidence run `20261101T143531Z-emulator`.
- Native build/lint: **PASS**; unsigned release is not distribution approval.
- API 26/x86_64 full boot and package readiness: **PASS**.
- Debug APK and test APK non-streamed installation: **PASS**, both package paths verified after installation.
- Android `NativeCredentialsTest`: **PASS**, `OK (6 tests)` on the real software emulator.
- Seven workflow regression tests: **PASS**; these mocked tests are separate from the six Android tests.
- The prior streamed install timed out. Explicit push installation succeeded (confirmed debug reinstall took 119 seconds; test APK 15 seconds). This establishes a working workaround, not a proven root cause. The runner uses non-streamed installation, a finite 480-second per-APK budget, package readiness/read-back and explicit failure evidence.
- Real WireGuard handshake, VPN consent and TCP/UDP/DNS: **NOT RUN**; no owned endpoint or authorized remote server connection supplied. The server setup/test guide is `docs/wireguard-owned-server-testing.md`.
- Physical-device checks and other API versions: **NOT RUN**. KVM remains unavailable; emulator results do not establish hardware-backed security or production readiness.

Latest local evidence is under `/tmp/android-real/project/test-evidence/20261101T143531Z-emulator/`. Only non-secret summary results are retained here.

## Executed results

- Official emulator 37.3.6, platform-tools and API 26 default x86_64 image downloaded and checked against Google's repository SHA-1 values.
- Gradle 8.9 distribution checked against pinned SHA-256; JDK 17.0.20.1, SDK 34 and build-tools 34 installed. Generated the actual displayed Android source.
- Debug, unsigned release, instrumentation APK compilation and lint: **PASS**, 128 tasks, fresh build approximately two minutes. Not release approval.
- Hardware acceleration: **UNAVAILABLE**. No VMX/SVM/KVM. Forced TCG software emulation.
- API 26 full boot: **PASS**. ADB reported `sys.boot_completed=1`, API 26/x86_64 and package service present. This improves on the earlier incomplete API 34 boot.
- Emulator workflow rebuild: **PASS**.
- Debug APK install: **BLOCKED**, exceeded the runner's 180-second installation bound without a completed install result. Test APK installation and instrumentation therefore **NOT RUN**.
- Real Android WireGuard library loading, VPN consent, handshake, TCP/UDP/DNS transfer, revocation and PSK rotation: **NOT RUN**. No owned reachable deployed test server was supplied or provisioned.
- Physical-device checks: **NOT RUN**; software emulator results cannot establish hardware Keystore, Wi-Fi/mobile roaming, OEM background policy or signed-release behavior.

The runner now explicitly requires full boot and bounds installation/instrumentation. Three mocked workflow guard tests passed; they are not Android runtime evidence. The emulator was stopped after recording results.

Temporary build and device logs were kept under `/tmp/android-real/project/test-evidence/`; this report is the durable non-secret summary. No device credentials or server keys were exported.

## Remaining requirements

1. Repeat the now-passing API 26 emulator lane across the supported API matrix, preferably on an accelerated host.
2. Enroll the native device with an owned reachable WireGuard server, grant VPN consent and record a fresh authenticated handshake and owned TCP/UDP/DNS traffic. Credential instrumentation alone does not test a tunnel.
3. Repeat supported API coverage and physical-device/release/server tests in the checklist.

**Not a completed real VPN test; not production ready. Browser connection and graphs remain simulations.**