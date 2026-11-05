# Repeatable Android build and device checks

This workflow generates the complete Android source shown in Tunnel Console. It has
**built successfully on 2026-10-31**: debug, unsigned release, instrumentation APK
compilation and debug lint passed. API 34 emulator setup was attempted without KVM;
boot/installation did not complete, so instrumentation and VPN checks remain unrun.
See `verification-2026-10-31.md` for exact results and warnings. Local WireGuard Go
tests are not substitutes for Android runtime results.

Latest retry: `verification-2026-11-01.md`. Build, API 26 software-emulator full boot,
both APK installations and six native credential tests PASS. Non-streamed installation
resolved the observed installation blocker. Real WireGuard network checks remain NOT RUN.
Runner requires full boot, package readiness, bounded non-streamed installs (480 seconds
each), package read-back and bounded instrumentation (`timeout` on the test host).
Owned server preparation and safe connection details: `docs/wireguard-owned-server-testing.md`.

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

## Authorized TLS endpoint diagnostic

The generated Android source now includes `AuthorizedTlsProbe.kt`. It performs a real
TCP connect, TLS handshake with SNI, normal platform trust-chain validation and HTTPS
hostname verification, then sends a bounded HTTP GET/HEAD/OPTIONS request and captures
up to 4 KiB of response data. It requires endpoint hostname, SNI and Host to match,
limits timeouts to 1–60 seconds, and does not accept proxy authorization headers.

This is an endpoint diagnostic/client transport only. It does **not** create a VPN,
route other apps' traffic, or replace WireGuard. Invoke it only from a background worker
for an endpoint you own or are authorized to test; never run network I/O on Android's
main thread. Record the TLS version, cipher, response status and non-secret byte counts.
Android compilation and device execution of this new source are still NOT RUN until the
generated project is rebuilt and tested using the pinned workflow above.


## Mobily SIM-aware automatic WireGuard connection

The generated Android companion can optionally auto-start its saved WireGuard profile when
the active data SIM is identified as Mobily (including the Etihad Etisalat operator label).
It uses the active-data subscription on Android 11+ and falls back to the default data
subscription on older supported versions. The SIM operator/carrier label is the identity
signal; the currently registered network name is display-only so roaming does not cause
a SIM to be misclassified.

Enable **Auto-connect only on Mobily data SIM** in the native app. Android asks for the
Phone permission to read subscription/operator metadata and separately asks for VPN
consent. The app never reads account balance, phone numbers, SMS, APN settings, or billing
state. A zero-balance SIM therefore follows the same start decision as a funded SIM; a
WireGuard handshake and usable Internet still require a working carrier data path.

The auto rule is opt-in and manages only tunnels it started itself. It does not stop a
manually started tunnel on a different carrier. A manual stop disables the auto rule.
After reboot, the receiver can start only if the app was opted in, a saved profile exists,
and Android VPN consent is already granted; otherwise open the app to complete consent.
SIM/operator values remain best-effort and must be checked on the physical handset,
especially for dual-SIM, MVNO, roaming and OEM-specific behavior.

Automatic start is based on the active Mobily data SIM and the absence of an active
Wi-Fi/Ethernet route. A validated Wi-Fi route pauses the Mobily rule, and network capability
callbacks trigger reevaluation when available networks change. The balance is not checked:
when no mobile-data path is available, the app may start the tunnel but it cannot complete a
WireGuard handshake or carry Internet traffic until the carrier provides a usable data path.
On devices with multiple simultaneously connected networks, Android/OEM routing can differ;
the active SIM and transport decision must therefore be confirmed on the physical phone.


## Authorized protocol diagnostics

The generated Android companion exposes a separate **Test selected protocol** action for
endpoints the user owns or is explicitly authorized to test:

- TLS/TCP: verifies a TLS handshake and reports protocol/cipher metadata.
- WS and WSS: performs a bounded WebSocket upgrade and validates the server's
  Sec-WebSocket-Accept response.
- HTTP CONNECT and HTTPS CONNECT: sends a single CONNECT request to the configured
  proxy and requires HTTP 200.
- SOCKS5 and SOCKS5 over TLS: negotiates the no-authentication method and performs one
  bounded CONNECT request to the explicitly entered destination.

TLS adapters use the platform trust store, certificate-chain validation, endpoint
hostname verification and SNI for the exact authorized endpoint hostname. There is no
trust-all mode, hostname override, automatic plaintext downgrade, proxy credential field,
or unbounded response read. Connect/read timeouts are limited to 1–60 seconds; HTTP
response headers are capped at 16 KiB and diagnostic requests at 4 KiB.

These actions are protocol diagnostics only. They close the test socket after recording
the handshake result and do not carry Android application traffic or establish a VPN.
SOCKS5/HTTP CONNECT/WebSocket/TLS must not be described as full-device VPN transports.
WireGuard remains the only full-device VPN in this generated app. Real endpoint checks
must be performed on a physical device and do not establish Mobily/STC carrier compatibility.
