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
    finding: "The Android service and relay still have different protocols: raw TLS versus WebSocket framing and a credential/target JSON handshake. Android now refuses TUN activation; this prevents a false CONNECTED result, but does not resolve interoperability. A local WebSocket-to-TCP echo test is not Android VPN proof.",
    passCriteria: "Implement one agreed protocol on both sides. Capture a successful authenticated handshake, target acknowledgement and bidirectional exchange; only then report CONNECTED.",
  },
  {
    id: "packets", title: "TUN packet forwarding", status: "blocked",
    finding: "TLS stream reads do not preserve IP packet boundaries. The included TCP relay does not implement IP routing, UDP forwarding or tunnel packet framing.",
    passCriteria: "Use a compatible packet tunnel with explicit framing and server-side routing. Verify TCP, UDP and DNS round trips, split/coalesced reads, large packets and MTU handling against services you own.",
  },
  {
    id: "build", title: "Android build & device matrix", status: "unverified",
    finding: "NOT RUN: no complete Android Studio project, JDK, Gradle, Android SDK/emulator or attached device is available here. API 26, 31, 33, 34 and newer supported-version builds/runs remain unverified. Emulators can cover installation/consent; physical devices are required for representative Wi-Fi/mobile handoff, OEM background restrictions and measured real-network behavior. The sample declares minSdk 26 and target/compileSdk 34, not current store approval.",
    passCriteria: "Record a clean debug and release build, exact Gradle/JDK/dependency versions, and install/start/stop results on API 26, 31, 33 and 34; also test every newer Android version you intend to support and current distribution target-SDK requirements.",
  },
  {
    id: "consent", title: "VPN consent & foreground service", status: "unverified",
    finding: "Consent, notification and foreground-service code is present, but Android permission denial and platform restrictions are not device-tested.",
    passCriteria: "Test consent grant/deny/revoke, notification grant/deny on API 33+, foreground startup and service type on API 34+, notification removal on stop, and background-start restrictions without crashes or silent activation.",
  },
  {
    id: "tls", title: "TLS identity & protected sockets", status: "unverified",
    finding: "The sample now requires successful protect(), matches TLS peer identity to SNI and enables HTTPS hostname verification with a handshake timeout. Native certificate rejection and routing-loop tests are still NOT RUN.",
    passCriteria: "Require successful socket protection and verify the expected certificate hostname. Reject expired, untrusted and wrong-host certificates before activating the tunnel; confirm the relay socket cannot route back into the VPN.",
  },
  {
    id: "lifecycle", title: "Lifecycle, cancellation & recovery", status: "unverified",
    finding: "The sample now rejects overlapping starts, closes sockets during cancellation and handles permission revocation. It refuses TUN activation because no compatible packet engine exists. Lifecycle and UI recovery remain device-UNVERIFIED.",
    passCriteria: "Exercise repeated start/stop, stop during connect/handshake, activity rotation/reopen, process termination, permission revocation and peer EOF. Confirm one active tunnel, no leaked threads/sockets and truthful UI state.",
  },
  {
    id: "network", title: "Network changes & traffic policy", status: "unverified",
    finding: "The sample routes IPv4, configures a fixed DNS server and excludes its own app. IPv6, fail-closed behavior and reconnection are not established.",
    passCriteria: "Define full/split-tunnel and app-exclusion policy. Test Wi-Fi/mobile handoff, offline/resume, DNS failures and IPv4/IPv6/DNS leakage; explicitly tunnel or block unsupported traffic and verify the selected disconnect policy.",
  },
  {
    id: "auth", title: "Relay client authentication", status: "unverified",
    finding: "The loopback-only relay now requires a shared credential supplied outside profile codes. Missing/incorrect credential rejection is locally tested, not production authentication evidence. Per-client identity, expiry, revocation and verified TLS remain unimplemented or unverified; app/MCP sign-in does not authenticate native traffic.",
    passCriteria: "Add a separate authenticated relay handshake over TLS. Reject missing, invalid, expired and revoked credentials; authorize targets per client and keep credentials out of logs and shared profile codes.",
  },
  {
    id: "targets", title: "Relay target restrictions", status: "unverified",
    finding: "The relay requires exact host:port allowlisting and non-coerced integer ports; fractional and unauthorized ports are locally rejected in tests. Resolved-address policy, DNS rebinding defense and per-client authorization remain unimplemented or unverified. Do not expose the sample publicly.",
    passCriteria: "Reject malformed JSON, non-integer/out-of-range ports and unauthorized host/port pairs. Enforce resolved-address policy, including loopback/private/metadata addresses and DNS changes; allow private destinations only when explicitly authorized.",
  },
  {
    id: "limits", title: "Resource limits & resilience", status: "unverified",
    finding: "The sample adds handshake/connect/idle deadlines, payload and connection limits, bounded buffers with overload disconnects and error cleanup. Slow-peer, sustained-load and rate-limit evidence remains absent; graceful backpressure and per-client rate limiting are not established.",
    passCriteria: "Set and test these limits under slow peers, oversized messages, stalled upstreams, abrupt disconnects and sustained load. Confirm bounded memory, reliable cleanup and recovery after relay restart.",
  },
  {
    id: "telemetry", title: "Real telemetry & privacy", status: "unverified",
    finding: "The browser graphs remain simulated. Android counters now use atomic increments and resets, and runtime exceptions omit configuration details. No compiled native concurrency, measured-transfer or privacy assessment exists.",
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