# Roadmap

- [ ] Agent connection guide: runtime URL, copy buttons, ChatGPT / Claude / Claude Code / other client connect and refresh instructions, visible workspace link and checks.

- [x] Agent integrations: 2 OAuth-protected remote read tools and 3 browser-local tools; managed OAuth newly activated, existing signing key retained. Manifest generated, 30 tests pass, local real-provider approve/deny and token exchange verified; both remote calls return 200. Local verification supplied the canonical configured Origin because the provider rejects localhost. Native WebMCP runtime is unavailable in test Chromium, so actual browser tool invocation remains unverified. Republish required; hosted consent and email-confirmation round trip remain unverified until then.

- [x] Final review: build health, test suite, click-through of Home / Configure / Share / Android screens
- [x] Marketing plan: research audience/communities, save research.md + plan.md, present ranked plan
- [x] Saved tunnel profiles: persist profiles between visits, manage (save/load/delete) and reuse them
- [x] Real relay server backend: Lovable Cloud accounts, synced relay_servers table, Servers tab, relay-server.mjs blueprint
- [x] Full review: refresh recovery and storage guards, accurate simulation totals, import validation and edit locks; 25 tests passed, signed-in server CRUD and repeated reloads verified; security scans found no issues. Android/relay interoperability remains a prerequisite for real VPN use, not a delivered feature.
- [x] Android compatibility checklist: 2 known blockers and 10 unverified requirements, expandable pass criteria and copyable evidence record; browser copy/layout checks and all 25 existing tests passed. Native testing remains unperformed.
- [x] Sitemap: router-derived public homepage URL, preserved robots.txt rules with sitemap reference, all 28 tests passed; published /sitemap.xml and /robots.txt both returned 200 with verified contents.
