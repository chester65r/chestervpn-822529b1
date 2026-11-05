# Tunnel Console

Tunnel Console is a web dashboard and companion Android WireGuard project for managing VPN connection profiles and server-side relay configuration.

The browser connection indicator is a UI simulation. Actual VPN traffic is handled by the companion Android application using WireGuard; the web app does not create a device VPN tunnel.

## Architecture

- **Web app:** React, TypeScript, Vite, and TanStack Start.
- **Authentication and data:** Supabase Auth and Postgres with row-level security.
- **Remote assistant integration:** authenticated MCP tools in `src/lib/mcp/`, using caller-scoped Supabase access.
- **Android VPN:** WireGuard using the official Android tunnel library.
- **Server:** WireGuard with `wg-quick`; peer changes are persisted before live synchronization.

See [AGENTS.md](./AGENTS.md) for project invariants and safety requirements. In particular, do not reintroduce the retired custom TLS/payload transport from the legacy Android sample below.

## Development

Requirements: Bun and Node.js 22.

```sh
bun install --frozen-lockfile
bun run dev
```

## Validation

```sh
bun run lint
bun run test
bun run build
bun run test:interop
```

## Environment

Configure the Supabase URL and publishable key, plus any server-only integration keys required by enabled features. Never put Supabase secret/service-role keys or other server credentials in client-exposed `VITE_*` variables.

## Live app

https://chestervpn.lovable.app

## Build with Lovable

Continue developing this project in the [Lovable editor](https://lovable.dev/projects/316563bc-095f-489d-bb30-eabf79a4e7cb).

- **Ship faster:** describe what you want to build and Lovable handles the code.
- **Stay in sync:** every change made in Lovable is committed straight to this repository.
- **Full ownership:** changes pushed to the connected branch sync into Lovable.

