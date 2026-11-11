# Roadmap

- [ ] Rotate this project's LOVABLE_API_KEY (2026-11-11), then refresh the running server to use the new key.

- [ ] Add a real VPN server (2026-11-07): no saved relay servers and no Google Cloud management connection found; awaiting provider selection or an owned server address and authorized access. Existing Google trial-only first-test preference remains in effect.

- [ ] Configure an owned reachable WireGuard server (2026-11-01): user selected Ubuntu/Debian VPS; no provider, endpoint or remote administrative connection supplied, and no server-management connection is available. Safe setup/connection/test guide completed in docs/wireguard-owned-server-testing.md. Awaiting non-secret server identification and authorized access; never collect private keys/passwords/enrollment files in chat. Cloud and additional backend remain unchanged.

- [x] Fix Android emulator APK installation timeout and rerun install/boot checks (2026-11-01 14:35 UTC): complete runner PASS with non-streamed bounded installation, full boot/package readiness, both package read-backs and six real Android credential tests. Seven workflow regression tests PASS. Evidence: native/android/verification-2026-11-01.md.

- [ ] Complete real Android emulator WireGuard connection (2026-11-01): native build/lint, API 26 software-emulator boot, both APK installations and six native credential tests PASS. Real handshake/TCP/UDP/DNS/VPN consent NOT RUN; blocked by no owned reachable deployed WireGuard endpoint or administrative connection. Other APIs/physical-device evidence remains unavailable. Browser remains simulated.

- [x] Combine both services in the app presentation after user chose Keep both services (2026-11-01): Servers shows service allocation and Cloud relay account controls separately from additional verification, with an external settings recheck. No accounts/data merged or backend changes. External features remain blocked by confirmation, owner permissions and real collector prerequisites listed below.

- [ ] Complete external email verification/account ownership (2026-11-01): live auth settings show auto-confirm enabled and zero accounts. Verification now fails closed until external manual confirmation is enabled; OTP + matching live identity and duplicate-submission guards added. Blocked by external auth configuration/template access and owner mailbox confirmation; Cloud untouched. Mock tests do not count as live email proof.

- [ ] Enable secure external profile save/retrieve and apply owner-only permissions (2026-11-01): blocked by the required migration tool having no external-project target. Do not apply external schema to Cloud or enable writes without live owner/cross-account/anonymous tests. External MCP write access does not remove this tool-targeting restriction.

- [ ] Set up and verify external private permissions/account ownership (2026-11-01): separate email verification UI added, confirmed identity requires live external auth lookup; no Cloud changes. External project has zero users/tables/buckets; owner-policy setup is prepared but blocked because the required migration/storage tools target Cloud only. Needs externally targeted schema/storage tools, owner email confirmation and two-account live permission tests.

- [x] Reconnect the external project with write-enabled access and show the safe next step (2026-11-01): separate private connection authorized; selected project reports transaction_read_only=off and public-schema CREATE permission. No public tables, policies or storage buckets exist. Cloud unchanged; next step is private schema/storage protections and independently verified external account ownership.
- [x] Discover and assess the selected additional project, bolt-native-database-72536136: active/healthy, no public tables or policies. Configure an isolated public read-only app reader without changing Cloud; record feature suitability in docs/additional-backend.md.
- [ ] Enable all four additional-project features (requested 2026-11-01): profile sync, monitoring, connection history and file storage. Cloud-side storage is ready: private `user-files` bucket with owner-scoped policies (2026-11-01). External management write access confirmed; private tables/storage policies are not created — ready storage SQL saved in docs/additional-backend-storage.sql. Still needs verified external account linkage. Real monitoring also needs an owned server collector; do not substitute simulation telemetry. Keep Cloud unchanged.

- [ ] Help connect both Google Ads and Facebook/Instagram: neither service connected; user declined both connection steps. Resume only on renewed request.

- [x] Address database connection request: project already uses Lovable Cloud for the database and accounts; no separate connection needed.

- [x] Finish Google Search Console setup for https://chestervpn.lovable.app/: linked Google account confirmed, verification tag and corrected sitemap published; ownership verified, property listed as siteOwner, sitemap submitted and downloaded by Google with 2 public URLs, zero errors and warnings (2026-10-31).

- [x] Verified latest GitHub documentation/roadmap/backups (all 154 project files present, none missing) and re-scanned repository files for credentials/tokens — no real secrets found. Fixed the backup route swallowing failures without logging.
- [ ] Set up recurring safe selected server-record exports to private GitHub, excluding credentials/tokens; route and tests are ready, scheduler not yet created — needs a publish so the callback URL exists, then enable the daily schedule.

- [x] Run repeatable Android build and attempt API 34 emulator checks: native debug/unsigned release/test APK builds and lint PASS; emulator installation blocked without KVM, no instrumentation result. Dated report: native/android/verification-2026-10-31.md.
- [x] GitHub repository made private and existing project files compared successfully (144 matching files, no differences). Git sync status itself is managed in Lovable Settings and cannot be inspected by the agent.
- [x] Prepare GitHub backup additions: original marketing documents, database backup guide and dated server-record CSV (zero saved rows); existing schema migration checked against live columns and owner-only policy. Credentials and account data excluded. All 150 repository files present and matching on remote read-back at 04:40 UTC.
- [x] Fresh safe server snapshot at 2026-10-31T04:40:15Z: zero rows, free-text notes excluded along with credentials and tokens; latest backup guide updated. Explicit GitHub delivery requested; verify remote contents after the push.

- [x] Native authentication implementation: local device identity, Keystore-wrapped credentials, public-only enrollment, pinned server identity and root-only revoke/rotation tool; 7 real local WireGuard packet tests and mocked server administration test pass. Native instrumentation supplied, NOT RUN.
- [x] Repeatable Android build/device workflow: project generator, pinned toolchain/checksum, explicit build/emulator/physical runner and API matrix/manual evidence record supplied; Android execution remains unavailable here.

- [x] Protocol mismatch resolved in design: Android blueprint embeds the WireGuard tunnel library; server blueprint is a WireGuard server; both use one shared profile generator. Local Go fixture (`bash interop/wireguard/run.sh`) passes 4 packet-level tests (TCP 1 MiB, UDP, DNS, key/PSK/address rejection). Not Android device evidence.
- [ ] On-device WireGuard handshake/traffic: API 26 boot/install/credential tests now pass; blocked by absent owned deployed test server and physical phones for physical-device evidence.
- [x] Locally testable remediation: checked socket protection and TLS peer hostname enforcement added to Android sample; atomic counters, startup/cancellation guards and fail-closed TUN gate added. Loopback relay now requires external credential, exact target/port allowlisting and bounded resources. Three executable fixture tests verify missing/wrong credential rejection, fractional/unauthorized ports and authenticated WebSocket-to-TCP echo. These are not native TLS or VPN interoperability tests.
- [ ] Native/device and production verification: toolchain installed and build passed; blocked by emulator runtime, absent physical phones, deployed WireGuard server and release evidence.
- [ ] Requested runtime API matrix and physical-device results: API 34 setup attempted, installation blocked without KVM; accelerated emulator host and physical phones required. Do not substitute build/browser results.
- [x] Compatible packet engine: WireGuard replaced the custom client/relay protocol; local engine evidence is available, but Android device evidence remains blocked by the native toolchain/devices.

- [x] Agent connection guide: runtime URL, copy buttons, ChatGPT / Claude / Claude Code / other client connect and refresh instructions, visible workspace link; browser copy and mobile overflow checks pass. Existing MCP server unchanged; republish to include the page.

- [x] Agent integrations: 2 OAuth-protected remote read tools and 3 browser-local tools; managed OAuth newly activated, existing signing key retained. Manifest generated, 30 tests pass, local real-provider approve/deny and token exchange verified; both remote calls return 200. Local verification supplied the canonical configured Origin because the provider rejects localhost. Native WebMCP runtime is unavailable in test Chromium, so actual browser tool invocation remains unverified. Republish required; hosted consent and email-confirmation round trip remain unverified until then.

- [x] Final review: build health, test suite, click-through of Home / Configure / Share / Android screens
- [x] Marketing plan: research audience/communities, save research.md + plan.md, present ranked plan
- [x] App name consistency: connection page, sign-in, consent screen and MCP title now show "Tunnel Console"; Claude Code install slug stays `tunnel-guardian-compass` so existing connections keep working (2026-11-02). Republish required to reach the live site.
- [x] Saved tunnel profiles: persist profiles between visits, manage (save/load/delete) and reuse them
- [x] Real relay server backend: Lovable Cloud accounts, synced relay_servers table, Servers tab, relay-server.mjs blueprint
- [x] Full review: refresh recovery and storage guards, accurate simulation totals, import validation and edit locks; 25 tests passed, signed-in server CRUD and repeated reloads verified; security scans found no issues. Android/relay interoperability remains a prerequisite for real VPN use, not a delivered feature.
- [x] Android compatibility checklist: 2 known blockers and 10 unverified requirements, expandable pass criteria and copyable evidence record; browser copy/layout checks and all 25 existing tests passed. Native testing remains unperformed.
- [x] Sitemap: router-derived public homepage URL, preserved robots.txt rules with sitemap reference, all 28 tests passed; published /sitemap.xml and /robots.txt both returned 200 with verified contents.

- [x] Deep review fixes: fail-closed Android tunnel state, credential locks, full-tunnel profile checks, transactional server peer changes, server-local firewall isolation persisted on boot, account-scoped server list loading, correct Mbps units, Go launcher fallback; 49 app tests + 7 packet tests pass. Android device/deployed-server verification still blocked (no toolchain/devices).

- [x] AI helper explaining connection errors (Gemini via AI Gateway)

- [x] Microsoft sign-in button in the Android app (2026-11-06): REVERTED 2026-11-07 at user request — the work was meant as review-only. FirebaseAuthManager, TunnelViewModel and ServersScreen restored to their pre-change state; the small ContextExt.findActivity helper stays because the user's own later Theme.kt code depends on it. If resumed: needs Microsoft enabled in the Firebase console plus the Firebase redirect URI registered in the customer's Microsoft app registration.

- [x] Repair the preview after the Compose migration (2026-11-07): the migration commit had deleted the web app, so the preview could not start. Restored the web app files and its package.json from history, reinstalled dependencies, and added `src/vite-env.d.ts` so the generated backend client's `import.meta.env` reads typecheck. Repaired one corrupted line in the WireGuard profile parser and removed three unreferenced modules that never compiled or shipped (`src/lib/tunnel-engine.ts`, `src/lib/config-manager.ts`, `src/components/tunnel/NetworkDiagnosticsTab.tsx`; recoverable from commit `ce547e0^` if the Network Diagnostics screen is wanted later). Build OK, 0 type errors, 75 tests pass, homepage and assistant-connection page render with no page errors.

- [x] Owned WireGuard server found already deployed (2026-11-11): live inventory of the owned Hetzner VPS found wg0 deployed since 2026-11-05, blueprint-compliant (subnet 10.66.66.0/24, UDP 51820, NAT via wg0-fw scripts, forwarding persisted), superseding the stale 2026-11-01 "no owned WireGuard server" blocker above. One peer (10.66.66.2) is enrolled and has carried ~1.1 GiB of real traffic (last handshake ~2026-11-06). A second peer, phone-1 (10.66.66.3), was added live and persisted 2026-11-11 for the owner's phone; client config + QR delivered. Full inventory: SERVER-SETUP.md. On-device handshake/traffic evidence for the new peer is still pending the owner's first connection (tracked by the open on-device item above).

- [x] Independent local verification of the full repository mirror (2026-11-11): all 269 source/text files mirrored and checked; web test suite passes 81/81 (the "75 tests" figure in earlier entries was stale) and the production web build passes. Go interop fixture could not yield real evidence in that sandbox (loopback UDP blocked by the environment, not a code failure); it remains off-device engine evidence only.

- [x] Android build verified locally with the pinned toolchain (2026-11-11): project generated with the repo's own generator, zero source changes; debug APK, unsigned release APK and instrumentation test APK all built (128 tasks, matching the 2026-11-01 verification count), lint 0 errors, and the debug APK contains the real WireGuard engine (libwg-go.so). Emulator and physical-device lanes remain NOT RUN — no KVM/device evidence yet; see ANDROID-BUILD.md.

- [ ] Additional backend activation started (2026-11-11): the prepared permissions SQL and storage SQL (docs/additional-backend-permissions.sql, docs/additional-backend-storage.sql) are being applied to the external project (canary-park), including the private user-files bucket with owner-scoped policies. Profile sync, monitoring, connection history and file storage remain Locked until the owner's verified additional-account linkage (email OTP + matching live identity + permission tests) is completed; Cloud remains unchanged.
