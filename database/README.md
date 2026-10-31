# Database backup

Snapshot checked at 2026-10-31T04:38:38Z (UTC).

## Structure

The application has one public table: `relay_servers`. Its reproducible definition,
port constraint, grants, and owner-only row-level security policy are in
`../drizzle/migrations/0000_create_relay_servers.sql`. The live columns and policy
were checked against this migration when this snapshot was prepared.

## Saved server records

`backups/relay-servers-2026-10-31.csv` contains the selected server-record snapshot.
There were **zero saved server records** at export time, so it contains only the
column header. It is not a live database mirror; later changes need a new export.

## Exclusions and restoration

This backup does not contain authentication accounts, passwords, sessions, access
tokens, native private keys, browser-local profiles, or managed platform schemas.
Do not add credentials to the repository. Keep any future record exports private
and inspect free-text fields for secrets before committing them.

Structure migrations do not restore managed authentication settings. On a different
deployment, server ownership IDs must map to valid destination accounts before
importing rows. Applying a snapshot to the running app is a separate, deliberate
operation; no live records were changed by this backup.