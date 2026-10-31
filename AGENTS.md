<!-- LOVABLE:BEGIN -->
> [!IMPORTANT]
> This project is connected to [Lovable](https://lovable.dev). Avoid rewriting
> published git history — force pushing, or rebasing/amending/squashing commits
> that are already pushed — as it rewrites history on Lovable's side and the
> user will likely lose their project history.
>
> Commits you push to the connected branch sync back to Lovable and show up in
> the editor, so keep the branch in a working state.
<!-- LOVABLE:END -->

## Project rules
- Connection instructions use the hydrated deployment origin for MCP URLs and a shared tested helper for safe Claude Code command quoting and server names; keep the guide separate from MCP server implementation.
- Remote MCP tools live in `src/lib/mcp/`, require verified OAuth client tokens, and access account records through caller-scoped RLS; browser tools remain in their owning screens and never expose credentials or commit consequential actions.
- OAuth login returns use one canonical same-origin path validator; preserve consent parameters through sign-in and email confirmation, and exclude authorization pages from the sitemap.
- Tunnel logic (payload macros, validation, TUNNEL:// codes) lives in pure functions in `src/lib/tunnel.ts` with tests in `src/test/tunnel.test.ts`; UI components only call them — keeps rules testable.
- Connection/bandwidth is a client-side visual simulation (`src/lib/use-tunnel-sim.ts`); a browser cannot open a real VPN.
- Restore browser profiles only after hydration, validate their stored shape, and guard storage writes so blocked or corrupt storage cannot crash the workspace.
- Android and server blueprints both use WireGuard (official Android tunnel library + wg-quick server) and render profiles from the shared generator in `src/lib/wireguard.ts`; never reintroduce a custom transport, so one audited protocol serves both sides.
- Native authentication source lives in the displayed blueprint module: generate device private keys only on Android, wrap credentials with Keystore, enroll only public identities with a root-only offline server tool, and never put native credentials into browser storage or account/MCP tokens; this separates VPN identity from web identity.
- The Android controller, not the activity, owns engine state; it fails closed (never auto-removes the VPN) and permits credential changes only when confirmed down. Server peer changes persist to disk before live sync, so restarts cannot re-authorize revoked devices.
- Packet-level interoperability evidence comes from the standalone Go fixture in `interop/wireguard/` (`run.sh`), configured only from generator output; it is never part of the web build and never counts as Android device evidence.
- Generate standalone Android projects from the displayed blueprint with native/android/generate-project.ts; its pinned build and explicit emulator/physical lanes keep native evidence reproducible and never promote emulator results to physical-device verification.
- Android compatibility requirements live in a shared documentation module consumed by the checklist and its copyable test record; "local" status means off-device engine evidence only, and nothing is marked verified from browser or local tests.
- Sitemap URLs derive from required route staticData.sitemap decisions via the shared inventory helper; exclude private routes and omit lastmod without an authoritative page-specific timestamp.
- Database record backups are dated, explicit snapshots separate from schema migrations and exclude credentials and managed auth data; this prevents accidental restores and avoids treating Git as a live database mirror.
- Recurring backups use a managed-authenticated server callback, a strict non-free-text projection and private-repository gate; no database rows or connector credentials are exposed over HTTP.
