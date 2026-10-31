export type CheckStatus = "blocked" | "local" | "unverified";

export type CompatibilityCheck = {
  id: string;
  title: string;
  status: CheckStatus;
  finding: string;
  passCriteria: string;
};

export const STATUS_LABEL: Record<CheckStatus, string> = {
  blocked: "KNOWN BLOCKER",
  local: "LOCAL TEST ONLY",
  unverified: "UNVERIFIED",
};

/** Command that reproduces the local packet-level evidence (needs Bun and Go). */
export const INTEROP_COMMAND = "bash interop/wireguard/run.sh";

// Documentation only. "local" means an engine/protocol test passed off-device; it is never device evidence.
export const ANDROID_COMPATIBILITY_CHECKS: CompatibilityCheck[] = [
  {
    id: "transport", title: "Client / server protocol", status: "local",
    finding: `Resolved in design: the raw TLS client and WebSocket relay were retired. The Android blueprint embeds the official WireGuard tunnel library (GoBackend), and the server blueprint is a WireGuard server; both use profiles from one shared generator. Local evidence (${INTEROP_COMMAND}): two independent wireguard-go peers configured only from generated wg-quick files completed authenticated handshakes on loopback. The Android project compiled successfully with the library on 2026-10-31; library runtime loading and on-device handshake are NOT RUN.`,
    passCriteria: "On each supported device, import a server-issued profile, start the tunnel, and record a fresh handshake with the configured server key before the app shows CONNECTED.",
  },
  {
    id: "packets", title: "TUN packet forwarding", status: "local",
    finding: "Local evidence: IP packets crossed userspace TUN devices through WireGuard — a 1 MiB TCP echo, UDP datagrams of 1, 512 and 1252 bytes (MTU 1280), and a DNS A lookup via the tunnel resolver. The server's NAT/forwarding script and Android's GoBackend TUN are NOT RUN; the test used a simplified loopback UDP socket because this sandbox rejects the default socket options.",
    passCriteria: "On device and against your deployed server: verify TCP, UDP and DNS to services you own, large transfers, MTU, and that no traffic bypasses the tunnel when it is up.",
  },
  {
    id: "build", title: "Android build & device matrix", status: "unverified",
    finding: "2026-10-31: generated Android project built with JDK 17.0.18, Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21 and SDK 34. Debug, unsigned release, instrumentation APK compilation and debug lint passed (24 warnings, zero errors). API 34 x86_64 emulator setup and ADB detection succeeded without KVM, but full boot/installation did not complete: first install lacked the package service; retry timed out. No instrumentation or VPN runtime result passed. API 26, 31, 33 and newer runs remain NOT RUN. See native/android/verification-2026-10-31.md. Physical devices remain required for hardware-backed keys, Wi-Fi/mobile handoff, OEM restrictions and real-network behavior.",
    passCriteria: "Record clean debug and release builds with exact Gradle/JDK/AGP versions, and install/start/stop results on API 26, 31, 33, 34 and every newer version you support, including 16 KB page-size devices.",
  },
  {
    id: "consent", title: "VPN consent & service lifecycle", status: "unverified",
    finding: "The app requests VpnService consent before starting; the engine's VPN service is declared by the library manifest. Consent denial/revocation, Always-on/Lockdown interaction and background restrictions are not device-tested.",
    passCriteria: "Test consent grant/deny/revoke, another VPN taking over, Always-on VPN and Lockdown mode, and confirm the UI never shows CONNECTED without a handshake.",
  },
  {
    id: "tls", title: "Peer identity & protected sockets", status: "local",
    finding: "WireGuard authenticates the server by its static public key plus a preshared key (no certificates or SNI). Locally, an unregistered client key and a wrong preshared key both failed to handshake. GoBackend protects its UDP sockets from the VPN, but it does not check protect()'s return value; routing-loop behavior on device is NOT RUN.",
    passCriteria: "On device, confirm wrong server keys never handshake, the engine's UDP socket bypasses the tunnel, and traffic resumes after the tunnel is restarted.",
  },
  {
    id: "lifecycle", title: "Lifecycle, cancellation & recovery", status: "unverified",
    finding: "Start/stop calls are serialized on one worker thread; the controller owns engine state, fails closed (keeps blocking traffic while waiting for a handshake, reports stop failures) and locks credential changes until the engine is confirmed down. Rotation, process death and repeated toggling remain device-UNVERIFIED.",
    passCriteria: "Exercise repeated start/stop, stop during startup, rotation/reopen, process termination and permission revocation. Confirm one active tunnel and truthful UI state.",
  },
  {
    id: "network", title: "Network changes & traffic policy", status: "unverified",
    finding: "Profiles route 0.0.0.0/0 and ::/0 so IPv6 cannot bypass an IPv4-only tunnel; DNS uses a tunnel-only resolver. Wi-Fi/mobile handoff, roaming and leak tests are NOT RUN.",
    passCriteria: "Test Wi-Fi/mobile handoff, offline/resume, DNS failures, and IPv4/IPv6/DNS leakage with independent leak checks.",
  },
  {
    id: "auth", title: "Device authentication", status: "local",
    finding: "DeviceEnrollment generates the private identity on Android, exports only the public key and pins an independently confirmed server public key. A root-only peer-admin.py enrolls, rotates PSKs and revokes live/persisted peers without device private keys. Seven local packet-engine tests pass, including wrong server identity, removal of an already authenticated peer and rejection of old PSKs after rotation. The peer-admin workflow passes a mocked-command test, NOT a deployed wg syncconf test. Native enrollment and Keystore instrumentation tests are supplied but NOT RUN. Encrypted no-backup storage fails closed on tampering/key loss; no hardware-backed Keystore claim is made.",
    passCriteria: "Run NativeCredentialsTest on supported APIs: encrypted round-trip, fresh nonce, tamper/truncation/key-loss rejection, deletion, local identity and server pinning. Against the deployed server verify secure enrollment delivery, live revocation and rotation, persistence after reboot; private keys must not enter logs, backups, clipboard, view state or share codes. Physical devices are required to record hardware-backed Keystore properties.",
  },
  {
    id: "targets", title: "Server forwarding restrictions", status: "unverified",
    finding: "The setup script forwards only wg0 → internet with NAT and drops tunnel traffic to private and link-local ranges. The nftables rules were syntax-reviewed, not executed here.",
    passCriteria: "On the server, confirm clients cannot reach the server's private networks, metadata services or each other unless explicitly allowed, and that rules persist after reboot.",
  },
  {
    id: "limits", title: "Resource limits & resilience", status: "unverified",
    finding: "WireGuard drops unauthenticated packets cheaply, but no load, rate-limit or sustained-throughput testing has been done.",
    passCriteria: "Load-test the server with the expected number of devices, confirm bounded CPU/memory, and recovery after server restart.",
  },
  {
    id: "telemetry", title: "Real telemetry & privacy", status: "unverified",
    finding: "Android speeds now come from the engine's per-peer byte counters; the browser graphs remain simulated. Error messages omit configuration details. Native measurements are NOT RUN.",
    passCriteria: "Compare reported byte totals with measured transfers and confirm logs contain no keys or endpoints.",
  },
  {
    id: "release", title: "Deployment & release readiness", status: "unverified",
    finding: "No production server deployment, security/load assessment, signed APK verification or store-policy review is demonstrated.",
    passCriteria: "Record signed release testing, VPN/store disclosures, privacy policy, secret handling and an independent security review before distribution.",
  },
];

export function checklistCounts() {
  return ANDROID_COMPATIBILITY_CHECKS.reduce(
    (counts, check) => ({ ...counts, [check.status]: counts[check.status] + 1 }),
    { blocked: 0, local: 0, unverified: 0 } as Record<CheckStatus, number>,
  );
}

export function androidChecklistMarkdown(): string {
  const counts = checklistCounts();
  return [
    "# Android / server compatibility checklist",
    "",
    `NOT PRODUCTION READY. ${counts.blocked} known blockers, ${counts.local} items with local engine tests only, ${counts.unverified} unverified. Native debug/unsigned release/test APK builds and lint passed on 2026-10-31; emulator runtime was blocked before instrumentation. No Android device test has passed. Browser and local engine tests are not Android evidence.`,
    `Reproduce local packet evidence: ${INTEROP_COMMAND}`,
    "",
    "## Test record",
    "Date: __________ | Tester: __________ | App commit: __________",
    "Device / Android API: __________ | Server version / deployment: __________",
    "Build tools / JDK: __________ | Evidence links (redact keys): __________",
    "",
    ...ANDROID_COMPATIBILITY_CHECKS.flatMap((check) => [
      `## ${check.title} — ${STATUS_LABEL[check.status]}`,
      check.finding,
      `Pass criteria: ${check.passCriteria}`,
      "Device result: NOT RUN | Evidence: __________ | Follow-up: __________",
      "",
    ]),
    "Release decision: NOT APPROVED until every applicable requirement has recorded passing device and deployment evidence; explain any non-applicable item.",
  ].join("\n");
}
