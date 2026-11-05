# Chestervpn control-plane foundation

## Product direction

Build toward the usability class of a full tunnel client (one-tap connect, explicit server choice, diagnostics, carefully scoped app selection) without cloning TRM branding or relying on opaque third-party endpoints. The supported tunnel protocol in this project is WireGuard to an owned, authorized gateway. No carrier-billing bypass, carrier-specific payload tricks, or restricted-connectivity workarounds.

## Current architecture status

```text
Android app (existing generated blueprint; not the local-only companion)
  ├─ device-generated WireGuard private key -> Android Keystore encrypted local store
  ├─ device public key -> authenticated enrollment request [not wired to an API yet]
  └─ native WireGuard engine -> owned Linux WireGuard gateway [not deployed]

Control plane (prepared metadata schema only)
  ├─ authenticated account -> per-user devices / sessions [migration not applied]
  ├─ gateway owner -> gateway records / health / audit [no service API yet]
  └─ server-side peer provisioning -> wg syncconf and persistent config [not implemented]

Linux gateway (first read-only component)
  └─ loopback healthz -> ip link + wg latest-handshakes [unit tests only; no VPS]
```

## Database migration 0003

`0003_vpn_control_plane.sql` is a reviewed starting schema for device public-key registration, owner gateways, peer lifecycle metadata, session counters, health snapshots, and audit events. It uses RLS and composite tenant keys. Authenticated clients can register only a pending public identity and read their own permitted rows; activation, gateway writes, peer changes, health ingestion, and audit writes are reserved for trusted server-side code.

It contains no fields for private keys, PSKs, access tokens, raw WireGuard profiles, or enrollment response content. It is **not applied** to any database and has not been validated against a live Supabase/Postgres instance. Review policy/operator semantics, run migrations on a disposable staging project, and add database-native tenant tests before applying to production.

## Next engineering stages

1. Validate migration and least-privilege policies in a staging Supabase project, then build an authenticated server-only control-plane API.
2. Implement serialized, lock-protected gateway peer lifecycle and rollback tests; fail safely when live config cannot be synchronized.
3. Deploy to an owned Debian/Ubuntu VPS after its non-secret hostname/OS/network layout is confirmed; provision no firewall rules until preserving current rules and rollback are reviewed.
4. Connect Android enrollment to the API and wire the existing generated WireGuard Android client to returned configuration. Keep private keys native, add server-key pin verification, and do not represent state as connected before tunnel stats show a recent authenticated handshake.
5. Exercise persistence, revoke/rotate, route/DNS/IPv6 behavior, authorized TCP/UDP traffic, and monitoring on a disposable owned server and physical Android device.
6. Build/sign the distribution release using a private key provided through a secure signing service—not Git—and verify store, privacy, and support operations.

## Evidence distinctions

- Migration/schema checks below are local source-level assertions, not Postgres execution.
- Health/peer/unit tests with fakes or mocks are not live server evidence.
- CI source build is not a physical-phone test or actual VPN handshake.
- No database, gateway, public endpoint, or Android release is deployed by these changes.
