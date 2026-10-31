# Repeatable Android build and device checks

This workflow generates the complete Android source shown in Tunnel Console. It has
**built successfully on 2026-10-31**: debug, unsigned release, instrumentation APK
compilation and debug lint passed. API 34 emulator setup was attempted without KVM;
boot/installation did not complete, so instrumentation and VPN checks remain unrun.
See `verification-2026-10-31.md` for exact results and warnings. Local WireGuard Go
tests are not substitutes for Android runtime results.

## Generate and pin the tools

Requires Bun for generation; JDK **17**, Android Gradle Plugin **8.7.3**, Kotlin **2.0.21**,
Gradle **8.9**, Android SDK platform **34**, build-tools **34.0.0**, platform-tools,
emulator and the API-specific system images on your Android development machine.
AGP 8.7 may select build-tools 34.0.0; retain downloaded SDK/tool versions in the record.

```sh
bun native/android/generate-project.ts /tmp/tunnel-android
sdkmanager 'platform-tools' 'platforms;android-34' 'build-tools;34.0.0' 'emulator'
sdkmanager --licenses
# Use an independently verified Gradle 8.9 installation just to create the wrapper:
gradle -p /tmp/tunnel-android wrapper --gradle-version 8.9 --distribution-type bin
# The generated wrapper task pins the official Gradle 8.9 binary checksum:
# d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab
# Verify it matches distributionSha256Sum in gradle/wrapper/gradle-wrapper.properties.
bash native/android/run-checks.sh /tmp/tunnel-android build
```

Do not commit local.properties, credentials, enrollment responses, signing keys or
logs from real profiles. The generated app uses dummy instrumentation credentials
only. Release assembly is unsigned; passing it is NOT a signed-release verification.

## Emulator lane — repeat independently on each supported API

Baseline matrix: API **26, 31, 33, 34**, then **every newer API you claim to support**.
Create one AVD per API using the system image matching your host's ABI. Example:

```sh
sdkmanager 'system-images;android-34;google_apis;x86_64'
echo no | avdmanager create avd --name tunnel-api34 --package 'system-images;android-34;google_apis;x86_64'
emulator -avd tunnel-api34 -no-snapshot -port 5554
adb -s emulator-5554 wait-for-device
# Wait until: adb -s emulator-5554 shell getprop sys.boot_completed -> 1
bash native/android/run-checks.sh /tmp/tunnel-android emulator emulator-5554
```

The runner builds debug/release/test APKs and lint, records API/ABI and runs real
Android Keystore/enrollment instrumentation: encryption round-trip, fresh nonces,
tamper/truncation rejection, key-loss recovery, credential deletion, stable on-device
identity, pinned-server rejection and rejection of imported private keys.
Use a disposable installation: these tests erase the test installation's identity.

Manual emulator checks (record separately): installation/launch, VPN consent grant
and deny, invalid enrollment, wrong server key, connect timeout, start/stop and
rotation/reopen. With an owned test server reachable from the AVD, record a real
handshake and TCP/UDP/DNS transfer, revoked peer and rotated PSK. These are emulator
results only; the runner does NOT automatically claim any VPN/network test passed.

## Physical-device lane — cannot be replaced by emulator checks

```sh
adb devices
# Use the explicit serial of a disposable test phone; enable USB debugging yourself.
bash native/android/run-checks.sh /tmp/tunnel-android physical 'PHONE_SERIAL'
```

Run the same credential instrumentation, then record these **manual** checks:

| Required physical test | Evidence to capture (never include keys) |
| --- | --- |
| Hardware-backed Keystore | KeyInfo security level; software/TEE/StrongBox distinction, no blanket claim |
| Wi-Fi ↔ mobile handoff/roaming | Handshake/recovery timing, DNS/IPv4/IPv6 leakage, authorized traffic |
| OEM battery/background restrictions | Screen off, Doze, process death, restart, repeated toggling |
| VPN takeover/consent revocation | Another VPN, Always-on/Lockdown, truthful disconnected state |
| 16 KB page-size devices | Native-library load and functional tunnel on relevant supported devices |
| Server enrollment/revoke/rotation | Valid traffic before, denied after revoke/old PSK, new enrollment works |
| Real telemetry/load | Measured transfer totals and sustained resource use |
| Release | Signed release install, privacy/store review, secure delivery/deletion of enrollment response |

Server peer changes must also survive reboot. Test rollback/crash recovery on a
disposable owned Linux server; mocked wg tests do not verify persistence/deployment.

## Evidence record

For **each** API/device and every manual check:

```text
UTC / tester / source revision:
Lane: emulator | physical
Device model / API / ABI / page size:
JDK / Gradle / AGP / Kotlin / SDK versions:
Server engine version / owned deployment:
Check name:
Result: PASS | FAIL | NOT RUN
Non-secret evidence location:
Follow-up / limitation:
```

Default status for a new API/device record is **NOT RUN**; the dated report above
records the executed native build and blocked emulator attempt.
Only mark concrete checks PASS after execution. Never promote emulator PASS to physical
PASS or generated-source checks to native evidence.