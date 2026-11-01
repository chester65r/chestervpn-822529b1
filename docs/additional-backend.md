# Additional backend assessment — 2026-11-01

Selected project: **bolt-native-database-72536136**.

## Confirmed

- External account access is authorized through a private, read-only MCP connection.
- The selected project is active and healthy. Its public schema currently has no tables or policies.
- A separate app reader is configured with its public URL and publishable key. It does not persist sessions, process login callbacks, forward Cloud bearer tokens, or permit writes.
- Isolation tests pass; the Data API schema endpoint rejects anonymous schema inspection (401, secret key required). This is not permission to add a secret key or enable private features; a table read is checked separately.
- A live read using the public key reaches PostgREST and returns PGRST205/404 for the absent `tunnel_connection_status` table. Connectivity is confirmed, not successful data retrieval.
- The managed Cloud client, accounts, relay server records, AI helper and backups remain unchanged.

## Feature suitability

| Feature | Safe scope | Prerequisites before enabling |
| --- | --- | --- |
| Synced tunnel profiles | Non-secret settings with explicit sync consent | Private tables, owner policies and verified external account identity. Do not upload raw payloads, native private keys or credentials. |
| Server monitoring | Sanitized health summaries | An authenticated collector running on a real owned WireGuard server; scoped reads. Browser simulation is not server health. |
| Connection history | Opt-in status/error category and time; simulation clearly labelled | Owner-only access and retention/deletion controls. Exclude hosts, payloads, traffic contents, tokens and keys. |
| File storage | Explicitly chosen non-secret documents | Private buckets, file size/type validation, owner policies and short-lived authorized downloads. Never store native credentials or complete sensitive VPN configurations. |
| Public status | Intentionally public aggregate status | A narrow anonymous SELECT policy and an authoritative source. No public tables currently exist. |

## Storage state (2026-11-01)

- Managed Cloud: private bucket `user-files` exists (10 MB per-file limit) with owner-scoped
  select/insert/update/delete policies on `storage.objects`; each user is confined to a folder
  named with their own user id. Enabled and verified.
- External project: still blocked — management access remains read-only (write attempts return
  `25006 cannot execute INSERT in a read-only transaction`). The ready-to-run SQL lives in
  `docs/additional-backend-storage.sql`.

## Not enabled

Private reads/writes, schema creation, account linkage and real monitoring are not implemented. The MCP connection grants development inspection only, not runtime access to private app records. A Cloud token must not be treated as an external-project token, nor may email alone link account ownership.

The built-in external-database attachment cannot be added to this Cloud-connected project. A separately configured integration can coexist with Cloud; it does not replace Cloud or host a WireGuard packet engine.