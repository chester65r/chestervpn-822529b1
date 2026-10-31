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
