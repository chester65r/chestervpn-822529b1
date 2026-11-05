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
- Keep the optional additional backend isolated and read-only until separately verified account ownership and private storage policies exist; never reuse Cloud sessions or alter the managed client to connect it.
- Service overview is presentation-only; additional connection rechecks read external auth settings without copying identities, records or Cloud credentials between services.
- Additional account verification uses a separate in-memory auth-only client, fails closed on automatic/unreadable email confirmation settings, and requires successful email OTP plus matching live identity; it never links by email or unlocks data access without independent permission tests.
- Connection instructions use the hydrated deployment origin for MCP URLs and a shared tested helper for safe Claude Code command quoting and server names; keep the guide separate from MCP server implementation.
- The public-facing app name is "Tunnel Console" (`CONNECTION_APP_NAME` in `src/lib/agent-connection.ts`); the assistant connection slug stays the fixed `CONNECTION_SLUG` value so existing assistant connections keep matching — never derive it from the display name.
- Remote MCP tools live in `src/lib/mcp/`, require verified OAuth client tokens, and access account records through caller-scoped RLS; browser tools remain in their owning screens and never expose credentials or commit consequential actions.
- OAuth login returns use one canonical same-origin path validator; preserve consent parameters through sign-in and email confirmation, and exclude authorization pages from the sitemap.
- Tunnel configuration, payload-template parsing, validation, carrier metadata, and profile serialization live in pure TypeScript modules with unit tests; UI components only call those modules.
- Connection and bandwidth displayed in the browser remain explicitly labeled simulations; a browser cannot open a device VPN or prove carrier-network behavior.
- Restore browser profiles only after hydration, validate their stored shape, and guard storage writes so blocked or corrupt storage cannot crash the workspace.
- WireGuard remains the default, audited full-device VPN transport. In addition, the project MAY implement opt-in custom transport adapters (TLS/TCP, WebSocket/WSS, HTTP CONNECT, and SOCKS5) for endpoints the user owns or is authorized to use. Do not treat these adapters as a full-device VPN unless a separately reviewed Android VpnService/TUN integration actually routes device traffic through them.
- Custom transport implementations MUST use authenticated endpoints, normal TLS certificate-chain validation, hostname verification, bounded timeouts, resource limits, explicit error states, and secret-safe logging. Do not add trust-all certificate managers, disable hostname verification, silently downgrade TLS, or accept arbitrary remote configuration commands.
- SNI and HTTP Host values may be configurable for a legitimate virtual host, but must identify the authorized service endpoint and pass certificate/hostname verification. Do not implement domain fronting, unrelated-host impersonation, carrier-whitelist exploitation, zero-rating or billing bypass, or evasion of carrier access controls.
- STC and Mobily support is a carrier compatibility/testing layer, not a source of undocumented carrier endpoints. Do not invent APNs, SNI names, Host headers, ports, credentials, exemptions, or claims of carrier compatibility. Mark unknown values as user-provided/unverified and require real SIM/device tests before marking them verified.
- Carrier detection is best-effort and must distinguish SIM operator from currently registered network where Android exposes both; allow manual override and handle MVNO/roaming/dual-SIM/permission-denied states without guessing.
- Native authentication source lives in the displayed blueprint module: generate device private keys only on Android, wrap credentials with Keystore, enroll only public identities with a root-only offline server tool, and never put native credentials into browser storage or account/MCP tokens; this separates VPN identity from web identity.
- The Android controller owns engine state; it fails closed and permits credential changes only when a transport is confirmed down. A transport failure must never silently remove an active VPN route or leak traffic outside an enabled full-device VPN.
- Server peer changes persist to disk before live sync, so restarts cannot re-authorize revoked devices.
- Packet-level WireGuard interoperability evidence comes from the standalone Go fixture in `interop/wireguard/` (`run.sh`), configured only from generator output; it is never part of the web build and never counts as Android device evidence.
- Generate standalone Android projects from the displayed blueprint with `native/android/generate-project.ts`; keep toolchain pins and explicit emulator/physical-device verification lanes reproducible.
- Android device checks use bounded non-streamed APK installation after full boot and package-service readiness, then verify installed package paths before instrumentation; this avoids streamed-install stalls and false runtime evidence.
- Android compatibility requirements live in a shared documentation module consumed by the checklist and its copyable test record; "local" status means off-device engine evidence only, and nothing is marked verified from browser or local tests.
- Sitemap URLs derive from required route staticData.sitemap decisions via the shared inventory helper; exclude private routes and omit lastmod without an authoritative page-specific timestamp.
- Database record backups are dated, explicit snapshots separate from schema migrations and exclude credentials and managed auth data; this prevents accidental restores and avoids treating Git as a live database mirror.
- AI features call Lovable AI Gateway only from server functions in `src/lib/*.functions.ts`, sending sanitized diagnostics (no hosts/payloads); keeps keys server-side and user data minimal.
- AWS server panel (view-only, owner role) lives in src/lib/aws-status*.ts; only EC2 Describe* calls, gated by has_role admin — prevents any user or code path from changing the AWS account.
