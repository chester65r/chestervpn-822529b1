# Repository review and safe-hardening notes

**Review date:** 2026-11-06  
**Reviewed baseline:** `main` at `dc08931afdf807ca3083c354868e651f5d2e2242`  
**Scope:** source, Android blueprints, migrations, workflows, Actions runs, and open PRs.  
**Status:** review notes only; this document does not represent a production security certification.

## Executive assessment

The repository contains a credible WireGuard-native direction, but it is not yet production-ready. The browser dashboard is a presentation/demo layer; it does not create a device VPN. The generated Android WireGuard project is the preferred native implementation, while the older `android/` tree is legacy and should not be treated as a second supported engine.

## Confirmed findings

1. **Browser connection is a simulation.** `src/lib/use-tunnel-sim.ts` advances connection state with timers and generates bandwidth/transfer figures. The UI labels the graph as simulated. Browser CONNECTED must never be treated as a real VPN handshake or traffic measurement.
2. **The current main CI definition is misaligned with the app.** The main workflow invokes `npm install` and `npx webpack`, while the actual project scripts use Bun, Vite, TanStack Start, Vitest and a Bun lockfile. The latest observed main workflow failed during npm dependency installation.
3. **Legacy Android code is not a complete VPN implementation.** The old service forwards raw TUN reads into a byte-stream socket and contains incomplete WS/SOCKS branches. The project should identify this path as legacy and keep the WireGuard GoBackend blueprint as the maintained native path.
4. **The old `wireguard-enhanced.ts` helper is unsafe for real key generation.** It creates random base64-looking strings with `Math.random()` and creates a public key independently instead of deriving it from the private key. Do not use this helper to create production identities.
5. **The AI diagnostics server function needs authentication and bounded input.** Draft PR #3 adds caller authentication and limits diagnostic field sizes. That protection must be verified against the deployed runtime before release.
6. **Role-check SQL needs least-privilege enforcement.** Draft PR #3 adds a caller-identity check, restricts function execution and switches the role helper to invoker permissions. The resulting migrations still require review against the live database and its applied migration history.
7. **The backup target conflicts with the repository visibility.** The backup implementation refuses to write unless the target repository is private, but this repository is public. Keep that fail-closed behavior; configure a separate private backup destination rather than weakening the guard or making an unapproved visibility change.
8. **Native VPN network evidence is incomplete.** Recorded API 26 emulator and credential instrumentation checks do not prove an authenticated WireGuard handshake, real TCP/UDP/DNS transfer, IPv6 leak behavior, physical-device behavior or production-server recovery.

## Pull-request status observed

- **PR #3 — security and CI:** draft. It contains useful caller-authentication and SQL hardening work. Its observed CI run stopped at lint because repository-wide formatting diagnostics prevented later tests/build from running. It was behind current main when inspected.
- **PR #4 — transport and SIM-aware additions:** draft. Its web CI passed in the observed run; its native Android workflow failed lint. Physical SIM behavior, real endpoint interoperability, native tunnel consent and production network tests remain unverified. Keep it draft pending separate security and compatibility review.

## Safe-hardening work in this branch

- Replace the mismatched Webpack/npm CI with the project's Bun/Vite/Vitest build and Go interoperability checks.
- Retain strict local linting, while making CI's functional ESLint check distinguish code-quality rules from the current Prettier baseline.
- Report Prettier formatting as a visible, non-blocking check rather than silently removing the local formatter rule.
- Exercise the peer-administration unit test with mocked `wg` commands without running the CI process as root; retain and assert the production root-only guard.
- Preserve `main`, the existing draft PRs, historical files and deployment configuration.

## Release gates still required

1. Reconcile draft PRs with current main; review their complete diffs and migration ordering before merge.
2. Require passing lint, unit tests, Go packet interoperability tests, web build and Android native build/lint.
3. Validate a real handshake and authorized TCP/UDP/DNS traffic against a dedicated server the project owner controls.
4. Verify IPv4/IPv6/DNS leak behavior, peer revocation, key rotation, reboot persistence, firewall compatibility and rollback on a disposable server.
5. Run physical-device checks for VPN permission, Android lifecycle, network handoff, OEM background restrictions and hardware-backed Keystore properties.
6. Use a private, access-controlled backup destination; verify restore separately from backup creation.
7. Complete privacy, licensing, dependency, secret-management, abuse-resistance and store-readiness reviews.

## Operating boundary

Maintain the standard WireGuard model for devices and servers that the user owns or is authorized to use. Do not add carrier billing/zero-rating circumvention, carrier whitelist exploitation, unrelated-host SNI/Host impersonation or evasion of network access controls. Carrier labels and SIM detection do not create data service or prove that traffic is authorized.
