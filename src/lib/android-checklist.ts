export type CompatibilityCheck = {
  id: string;
  title: string;
  status: "blocked" | "unverified";
  finding: string;
  passCriteria: string;
};

// Documentation only: these statuses are not device test results.
export const ANDROID_COMPATIBILITY_CHECKS: CompatibilityCheck[] = [
  {
    id: "transport", title: "Client / relay protocol", status: "blocked",
    finding: "The Android service sends HTTP-like headers over raw TLS. The relay expects WebSocket framing and an initial JSON target message.",
    passCriteria: "Implement one agreed protocol on both sides. Capture a successful authenticated handshake, target acknowledgement and bidirectional exchange; only then report CONNECTED.",
  },
  {
    id: "packets", title: "TUN packet forwarding", status: "blocked",
    finding: "TLS stream reads do not preserve IP packet boundaries. The included TCP relay does not implement IP routing, UDP forwarding or tunnel packet framing.",
    passCriteria: "Use a compatible packet tunnel with explicit framing and server-side routing. Verify TCP, UDP and DNS round trips, split/coalesced reads, large packets and MTU handling against services you own.",
  },
  {
    id: "build", title: "Android build & device matrix", status: "unverified",
    finding: "No Android Studio build, emulator run or physical-device test has been performed here. The sample declares minSdk 26 and target/compileSdk 34.",
    passCriteria: "Record a clean debug and release build, exact Gradle/JDK/dependency versions, and install/start/stop results on API 26, 31, 33 and 34; also test every newer Android version you intend to support and current distribution target-SDK requirements.",
  },
  {
    id: "consent", title: "VPN consent & foreground service", status: "unverified",
    finding: "Consent, notification and foreground-service code is present, but Android permission denial and platform restrictions are not device-tested.",
    passCriteria: "Test consent grant/deny/revoke, notification grant/deny on API 33+, foreground startup and service type on API 34+, notification removal on stop, and background-start restrictions without crashes or silent activation.",
  },
  {
    id: "tls", title: "TLS identity & protected sockets", status: "unverified",
    finding: "The sample sets SNI and calls protect(), but does not check its result or explicitly enable TLS endpoint hostname verification. SNI alone does not verify identity.",
    passCriteria: "Require successful socket protection and verify the expected certificate hostname. Reject expired, untrusted and wrong-host certificates before activating the tunnel; confirm the relay socket cannot route back into the VPN.",
  },
  {
    id: "lifecycle", title: "Lifecycle, cancellation & recovery", status: "unverified",
    finding: "Thread/socket cleanup and UI state recovery have not been tested on Android; repeated starts and stopping during connection need verification.",
    passCriteria: "Exercise repeated start/stop, stop during connect/handshake, activity rotation/reopen, process termination, permission revocation and peer EOF. Confirm one active tunnel, no leaked threads/sockets and truthful UI state.",
  },
  {
    id: "network", title: "Network changes & traffic policy", status: "unverified",
    finding: "The sample routes IPv4, configures a fixed DNS server and excludes its own app. IPv6, fail-closed behavior and reconnection are not established.",
    passCriteria: "Define full/split-tunnel and app-exclusion policy. Test Wi-Fi/mobile handoff, offline/resume, DNS failures and IPv4/IPv6/DNS leakage; explicitly tunnel or block unsupported traffic and verify the selected disconnect policy.",
  },
  {
    id: "auth", title: "Relay client authentication", status: "unverified",
    finding: "The relay sample has no client authentication. Signing into Tunnel Console does not authenticate a native relay connection.",
    passCriteria: "Add a separate authenticated relay handshake over TLS. Reject missing, invalid, expired and revoked credentials; authorize targets per client and keep credentials out of logs and shared profile codes.",
  },
  {
    id: "targets", title: "Relay target restrictions", status: "unverified",
    finding: "A host allowlist exists, but strict target parsing, integer ports, DNS/IP enforcement and per-client authorization have not been hardened or tested.",
    passCriteria: "Reject malformed JSON, non-integer/out-of-range ports and unauthorized host/port pairs. Enforce resolved-address policy, including loopback/private/metadata addresses and DNS changes; allow private destinations only when explicitly authorized.",
  },
  {
    id: "limits", title: "Resource limits & resilience", status: "unverified",
    finding: "The relay lacks demonstrated authentication deadlines, idle/connect timeouts, backpressure, message limits and concurrency/rate limits.",
    passCriteria: "Set and test these limits under slow peers, oversized messages, stalled upstreams, abrupt disconnects and sustained load. Confirm bounded memory, reliable cleanup and recovery after relay restart.",
  },
  {
    id: "telemetry", title: "Real telemetry & privacy", status: "unverified",
    finding: "The browser graphs are simulated. Android counters have not been validated for concurrent updates or compared with measured traffic.",
    passCriteria: "Use thread-safe counters and compare native byte/speed totals with measured transfers. Verify zero/reset behavior, disconnect reporting, profile handling and logs that exclude payloads and credentials.",
  },
  {
    id: "release", title: "Deployment & release readiness", status: "unverified",
    finding: "No production relay deployment, security/load assessment, signed APK verification or store-policy review is demonstrated.",
    passCriteria: "Verify TLS termination, firewall exposure, least privilege, dependency updates, monitoring and secret rotation. Record signed release testing, VPN/store disclosures, privacy policy and an independent security review before distribution.",
  },
];

export function androidChecklistMarkdown(): string {
  return [
    "# Android / relay compatibility checklist",
    "",
    "NOT PRODUCTION READY. No native build, device run or relay interoperability test has been verified. Browser tests are not Android evidence.",
    "Known protocol and packet-forwarding blockers must be resolved before end-to-end testing. All remaining production requirements are UNVERIFIED.",
    "",
    "## Test record",
    "Date: __________ | Tester: __________ | App commit: __________",
    "Device / Android API: __________ | Relay version / deployment: __________",
    "Build tools / JDK: __________ | Evidence links (redact secrets): __________",
    "",
    ...ANDROID_COMPATIBILITY_CHECKS.flatMap((check) => [
      `## ${check.title} — ${check.status === "blocked" ? "KNOWN BLOCKER" : "UNVERIFIED"}`,
      check.finding,
      `Pass criteria: ${check.passCriteria}`,
      "Result: NOT RUN | Evidence: __________ | Follow-up: __________",
      "",
    ]),
    "Release decision: NOT APPROVED until all blockers are resolved and every applicable requirement has recorded passing evidence; explain any non-applicable item.",
  ].join("\n");
}