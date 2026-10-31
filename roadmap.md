# Roadmap

- [ ] Replace the incompatible native TLS/WebSocket path with a proven packet engine and matching relay configuration; run packet-level interoperability tests and record their exact scope separately from Android/device verification.
- [x] Locally testable remediation: checked socket protection and TLS peer hostname enforcement added to Android sample; atomic counters, startup/cancellation guards and fail-closed TUN gate added. Loopback relay now requires external credential, exact target/port allowlisting and bounded resources. Three executable fixture tests verify missing/wrong credential rejection, fractional/unauthorized ports and authenticated WebSocket-to-TCP echo. These are not native TLS or VPN interoperability tests.
- [ ] Native/device and production verification: blocked by missing Android project/toolchain/devices, interoperable packet engine, deployed relay and release evidence.
- [ ] Requested native builds/API matrix and physical-device results: blocked by absent complete Android project, Android SDK/JDK/emulators and attached devices; do not substitute browser results.
- [ ] Requested Android-to-relay authentication and interoperable VPN protocol: blocked by absent compatible native packet engine; shared-token relay demonstration is not native identity or end-to-end VPN proof.

- [x] Agent connection guide: runtime URL, copy buttons, ChatGPT / Claude / Claude Code / other client connect and refresh instructions, visible workspace link; browser copy and mobile overflow checks pass. Existing MCP server unchanged; republish to include the page.

- [x] Agent integrations: 2 OAuth-protected remote read tools and 3 browser-local tools; managed OAuth newly activated, existing signing key retained. Manifest generated, 30 tests pass, local real-provider approve/deny and token exchange verified; both remote calls return 200. Local verification supplied the canonical configured Origin because the provider rejects localhost. Native WebMCP runtime is unavailable in test Chromium, so actual browser tool invocation remains unverified. Republish required; hosted consent and email-confirmation round trip remain unverified until then.

- [x] Final review: build health, test suite, click-through of Home / Configure / Share / Android screens
- [x] Marketing plan: research audience/communities, save research.md + plan.md, present ranked plan
- [x] Saved tunnel profiles: persist profiles between visits, manage (save/load/delete) and reuse them
- [x] Real relay server backend: Lovable Cloud accounts, synced relay_servers table, Servers tab, relay-server.mjs blueprint
- [x] Full review: refresh recovery and storage guards, accurate simulation totals, import validation and edit locks; 25 tests passed, signed-in server CRUD and repeated reloads verified; security scans found no issues. Android/relay interoperability remains a prerequisite for real VPN use, not a delivered feature.
- [x] Android compatibility checklist: 2 known blockers and 10 unverified requirements, expandable pass criteria and copyable evidence record; browser copy/layout checks and all 25 existing tests passed. Native testing remains unperformed.
- [x] Sitemap: router-derived public homepage URL, preserved robots.txt rules with sitemap reference, all 28 tests passed; published /sitemap.xml and /robots.txt both returned 200 with verified contents.
