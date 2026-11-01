# Additional backend assessment — 2026-11-01

Selected project: **bolt-native-database-72536136**.

## Confirmed

- External account access was reauthorized through a separate private, write-enabled MCP connection on 2026-11-01. The selected project reports `transaction_read_only=off` and permission to create public-schema objects. No external data or schema was changed during this check.
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
- External project: write-enabled management access is now confirmed. The permission inventory
  is empty: no public tables, public/storage policies or storage buckets exist. The ready-to-run
  SQL lives in `docs/additional-backend-storage.sql`; it has not been applied. Private account
  ownership must be verified separately before runtime features are enabled.

## Not enabled

Private reads/writes, schema creation, account linkage and real monitoring remain disabled. The MCP connection grants development access only, not runtime access to private app records. A Cloud token must not be treated as an external-project token, nor may email alone link account ownership.

## Ownership and permission preparation — 2026-11-01

- **Live email settings check (2026-11-01 13:59 UTC):** email auth and signup enabled, anonymous auth disabled, but `mailer_autoconfirm=true`. No external accounts exist. This does not prove mailbox ownership. The app now pauses verification unless a fresh settings read confirms manual email confirmation. Cloud auth settings were not changed.
- The email OTP helper requires a successful challenge, a session and a matching live confirmed non-anonymous identity; duplicate submits are guarded. Tests are mocked security regression checks, not delivered-email evidence.
- **Email verification blocker:** available auth configuration tools target managed Cloud, not the external project. External confirmation/template configuration must be changed with an externally targeted auth-settings tool before the form is usable. The email template must contain an OTP (`{{ .Token }}`); template and mail delivery have not been inspected. Real email/code completion requires the owner's mailbox; no credentials or OTPs should be pasted into chat.

- Live inventory: zero external users, confirmed users, tables, policies or buckets. Security advisor returned no findings; an empty schema is not evidence of owner isolation.
- Servers now includes a separate additional-account email/code verification form. It uses an in-memory auth-only client, requests verification directly from external Auth and validates the confirmed, non-anonymous identity with a live `getUser()` call. No Cloud session is reused, no account is linked by email, and successful authentication does not unlock private features.
- Owner-only DDL is prepared in `docs/additional-backend-permissions.sql`; files policies are prepared in `docs/additional-backend-storage.sql`. All new tables have grants and RLS, require external `auth.uid()` ownership plus confirmed identity, and exclude hosts, payloads, free-text secrets and credentials. Monitoring is browser read/delete only; collector writes require a separately verified server.
- **Not applied:** required managed migration and Storage tools cannot target the external project. Calling them would change Cloud, contrary to the request. No SQL bucket writes or alternate migration path were used.
- **Still unverified:** external signup/mail delivery configuration, real owner email confirmation, two-account own/cross-account/anonymous access tests, private file upload/download/deletion and collector authorization. Do not describe these as passing or enable features until tested against live externally targeted permissions.
- Tests cover auth-only request isolation, write blocking, unconfirmed/anonymous identity rejection and live identity lookup. Owner must complete email confirmation in the Servers screen; never paste passwords or tokens into chat.

The built-in external-database attachment cannot be added to this Cloud-connected project. A separately configured integration can coexist with Cloud; it does not replace Cloud or host a WireGuard packet engine.