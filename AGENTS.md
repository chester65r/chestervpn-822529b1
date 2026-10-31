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
- Tunnel logic (payload macros, validation, TUNNEL:// codes) lives in pure functions in `src/lib/tunnel.ts` with tests in `src/test/tunnel.test.ts`; UI components only call them — keeps rules testable.
- Connection/bandwidth is a client-side visual simulation (`src/lib/use-tunnel-sim.ts`); a browser cannot open a real VPN.
- Restore browser profiles only after hydration, validate their stored shape, and guard storage writes so blocked or corrupt storage cannot crash the workspace.
- Android and relay snippets are educational scaffolding, not an interoperable production VPN; retain explicit protocol limitations until device-tested interoperability exists.
- Android compatibility requirements live in a shared documentation module consumed by the checklist and its copyable test record; never mark requirements verified based on browser tests.
- Sitemap URLs derive from required route staticData.sitemap decisions via the shared inventory helper; exclude private routes and omit lastmod without an authoritative page-specific timestamp.
