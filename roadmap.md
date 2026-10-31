# Roadmap

- [x] Native authentication implementation: local device identity, Keystore-wrapped credentials, public-only enrollment, pinned server identity and root-only revoke/rotation tool; 7 real local WireGuard packet tests and mocked server administration test pass. Native instrumentation supplied, NOT RUN.
- [x] Repeatable Android build/device workflow: project generator, pinned toolchain/checksum, explicit build/emulator/physical runner and API matrix/manual evidence record supplied; Android execution remains unavailable here.

- [x] Protocol mismatch resolved in design: Android blueprint embeds the WireGuard tunnel library; server blueprint is a WireGuard server; both use one shared profile generator. Local Go fixture (`bash interop/wireguard/run.sh`) passes 4 packet-level tests (TCP 1 MiB, UDP, DNS, key/PSK/address rejection). Not Android device evidence.
- [ ] Android build and on-device WireGuard handshake/traffic: blocked by absent Android SDK/JDK/Gradle, emulator and physical devices.
- [x] Locally testable remediation: checked socket protection and TLS peer hostname enforcement added to Android sample; atomic counters, startup/cancellation guards and fail-closed TUN gate added. Loopback relay now requires external credential, exact target/port allowlisting and bounded resources. Three executable fixture tests verify missing/wrong credential rejection, fractional/unauthorized ports and authenticated WebSocket-to-TCP echo. These are not native TLS or VPN interoperability tests.
- [ ] Native/device and production verification: blocked by missing Android toolchain/devices, deployed WireGuard server and release evidence; standalone project generator is now supplied.
- [ ] Requested native builds/API matrix and physical-device results: blocked by absent Android SDK/JDK/emulators and attached devices; do not substitute browser results.
- [x] Compatible packet engine: WireGuard replaced the custom client/relay protocol; local engine evidence is available, but Android device evidence remains blocked by the native toolchain/devices.

- [x] Agent connection guide: runtime URL, copy buttons, ChatGPT / Claude / Claude Code / other client connect and refresh instructions, visible workspace link; browser copy and mobile overflow checks pass. Existing MCP server unchanged; republish to include the page.

- [x] Agent integrations: 2 OAuth-protected remote read tools and 3 browser-local tools; managed OAuth newly activated, existing signing key retained. Manifest generated, 30 tests pass, local real-provider approve/deny and token exchange verified; both remote calls return 200. Local verification supplied the canonical configured Origin because the provider rejects localhost. Native WebMCP runtime is unavailable in test Chromium, so actual browser tool invocation remains unverified. Republish required; hosted consent and email-confirmation round trip remain unverified until then.

- [x] Final review: build health, test suite, click-through of Home / Configure / Share / Android screens
- [x] Marketing plan: research audience/communities, save research.md + plan.md, present ranked plan
- [x] Saved tunnel profiles: persist profiles between visits, manage (save/load/delete) and reuse them
- [x] Real relay server backend: Lovable Cloud accounts, synced relay_servers table, Servers tab, relay-server.mjs blueprint
- [x] Full review: refresh recovery and storage guards, accurate simulation totals, import validation and edit locks; 25 tests passed, signed-in server CRUD and repeated reloads verified; security scans found no issues. Android/relay interoperability remains a prerequisite for real VPN use, not a delivered feature.
- [x] Android compatibility checklist: 2 known blockers and 10 unverified requirements, expandable pass criteria and copyable evidence record; browser copy/layout checks and all 25 existing tests passed. Native testing remains unperformed.
- [x] Sitemap: router-derived public homepage URL, preserved robots.txt rules with sitemap reference, all 28 tests passed; published /sitemap.xml and /robots.txt both returned 200 with verified contents.

- [x] Deep review fixes: fail-closed Android tunnel state, credential locks, full-tunnel profile checks, transactional server peer changes, server-local firewall isolation persisted on boot, account-scoped server list loading, correct Mbps units, Go launcher fallback; 49 app tests + 7 packet tests pass. Android device/deployed-server verification still blocked (no toolchain/devices).
