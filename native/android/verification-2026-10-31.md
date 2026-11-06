# Android verification — 2026-10-31 UTC

## Executed build: PASS

Generated the project from the displayed blueprint in a fresh temporary directory.
Installed OpenJDK 17.0.18, verified the official Gradle 8.9 distribution SHA-256,
generated the pinned wrapper, and installed Android platform 34/build-tools 34.0.0.
AGP 8.7.3, Kotlin 2.0.21, WireGuard tunnel 1.0.20260102.

`bash native/android/run-checks.sh /tmp/tunnel-android-emulator build`

- Debug APK: PASS.
- Unsigned release APK: PASS (not signed-release approval).
- Android instrumentation APK compilation: PASS (not test execution).
- Debug lint: PASS with 24 warnings, zero errors. Warnings include default locale,
  dependency updates, backup rules, static application-context reference, missing
  launcher icon and untranslated text; passing lint is not production approval.
- Workflow evidence-gate regression tests: 2/2 PASS; these use mocks, not Android.
- Initial native build completed in 3m25s with 128 tasks.

## API 34 x86_64 emulator: BLOCKED / no runtime checks passed

Installed the official Android emulator and Google APIs API 34 x86_64 image,
created `tunnel-api34`, and started it headlessly with software rendering and
`-accel off`. KVM check reports no supported vmx/svm acceleration in this sandbox.
ADB eventually identifies `emulator-5554` as a device and reports API 34/x86_64,
but `sys.boot_completed` never returned 1 during the bounded attempt.

Executed the emulator workflow twice. The first installation failed with
`cmd: Can't find service: package`. The package service subsequently appeared,
but the second workflow was stopped after its 180-second installation timeout.
Neither attempt produced an instrumentation result. This is an environment/startup
blocker, not evidence of a credential-test failure or a successful installation.

- Emulator setup and ADB detection: PASS.
- Complete boot, APK installation and launch: NOT VERIFIED.
- Six Keystore/enrollment instrumentation tests: NOT RUN.
- Consent, lifecycle, real WireGuard handshake, TCP/UDP/DNS and leakage: NOT RUN.
- API 26, 31, 33 and newer-than-34 runtime matrix: NOT RUN.

Raw temporary evidence: `/tmp/tunnel-android-emulator/test-evidence/`;
tool/boot diagnostics: `/tmp/android-tools/`. These are local temporary locations,
not durable download links. This report is the retained project record.
Stopped the software emulator after recording results. Repeat on an accelerated
Android host, wait for `sys.boot_completed=1`, then use the documented emulator lane.

## Physical-device evidence still required

- Real hardware Keystore security level (TEE/StrongBox versus software).
- Wi-Fi/mobile handoff, roaming and independent IPv4/IPv6/DNS leakage checks.
- OEM battery restrictions, Doze, screen-off, process death and recovery.
- Real-device VPN takeover, consent revocation and Always-on/Lockdown behavior.
- Supported 16 KB page-size devices and native tunnel loading.
- Real-phone telemetry, sustained throughput and resource use.
- Signed-release install and secure credential delivery/deletion on phones.

Also NOT RUN: deployed-server enrollment/revocation/PSK rotation, reboot persistence,
firewall isolation, load/security assessment and store/privacy review. These need
an owned server and release evidence; they are not all inherently physical-only.

**Not production ready. A successful build does not establish Android runtime or VPN operation.**