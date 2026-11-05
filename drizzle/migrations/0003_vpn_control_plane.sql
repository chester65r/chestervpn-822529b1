-- Chestervpn control-plane metadata schema.
-- PREPARED MIGRATION: review and apply only to the intended owned Supabase project.
-- Never store WireGuard private keys, preshared keys, enrollment responses, or service secrets here.

create table public.vpn_devices (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  label text not null check (length(label) between 1 and 80),
  public_key text not null unique check (public_key ~ '^[A-Za-z0-9+/]{42}[AEIMQUYcgkosw048]=$'),
  state text not null default 'pending' check (state in ('pending','active','revoked')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  revoked_at timestamptz,
  unique (id, user_id),
  check ((state = 'revoked') = (revoked_at is not null))
);

create table public.vpn_gateways (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  name text not null check (length(name) between 1 and 80),
  endpoint_host text not null check (length(endpoint_host) between 1 and 253),
  endpoint_port integer not null check (endpoint_port between 1 and 65535),
  server_public_key text not null unique check (server_public_key ~ '^[A-Za-z0-9+/]{42}[AEIMQUYcgkosw048]=$'),
  tunnel_network cidr not null,
  dns_address inet not null,
  enabled boolean not null default false,
  health_state text not null default 'unknown' check (health_state in ('unknown','healthy','degraded','unreachable')),
  last_health_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (id, owner_id)
);

create table public.vpn_peers (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null,
  device_id uuid not null,
  gateway_id uuid not null,
  assigned_address inet not null check (family(assigned_address) = 4 and masklen(assigned_address) = 32),
  state text not null default 'pending' check (state in ('pending','active','revoked','error')),
  authorized_at timestamptz,
  revoked_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  foreign key (device_id, user_id) references public.vpn_devices(id, user_id) on delete cascade,
  foreign key (gateway_id, user_id) references public.vpn_gateways(id, owner_id) on delete cascade,
  unique (device_id, gateway_id),
  unique (gateway_id, assigned_address),
  check ((state = 'revoked') = (revoked_at is not null))
);

create table public.vpn_sessions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null,
  device_id uuid not null,
  gateway_id uuid not null,
  started_at timestamptz not null default now(),
  ended_at timestamptz,
  bytes_received bigint not null default 0 check (bytes_received >= 0),
  bytes_sent bigint not null default 0 check (bytes_sent >= 0),
  disconnect_reason text check (disconnect_reason is null or disconnect_reason in ('user','revoked','timeout','network','gateway','unknown')),
  foreign key (device_id, user_id) references public.vpn_devices(id, user_id) on delete cascade,
  foreign key (gateway_id, user_id) references public.vpn_gateways(id, owner_id) on delete cascade,
  check (ended_at is null or ended_at >= started_at)
);

create table public.vpn_gateway_health (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null,
  gateway_id uuid not null,
  observed_at timestamptz not null default now(),
  state text not null check (state in ('healthy','degraded','unreachable')),
  latency_ms integer check (latency_ms is null or latency_ms between 0 and 600000),
  peer_count integer check (peer_count is null or peer_count >= 0),
  recent_handshakes integer check (recent_handshakes is null or recent_handshakes >= 0),
  foreign key (gateway_id, owner_id) references public.vpn_gateways(id, owner_id) on delete cascade
);

create table public.vpn_audit_events (
  id uuid primary key default gen_random_uuid(),
  actor_user_id uuid references auth.users(id) on delete set null,
  owner_id uuid references auth.users(id) on delete set null,
  gateway_id uuid,
  device_id uuid,
  event_type text not null check (event_type in ('device_registered','peer_enrolled','peer_revoked','key_rotated','gateway_health_changed','session_started','session_ended','admin_action')),
  outcome text not null check (outcome in ('success','denied','failure')),
  created_at timestamptz not null default now(),
  metadata jsonb not null default '{}'::jsonb check (octet_length(metadata::text) <= 4096),
  foreign key (gateway_id, owner_id) references public.vpn_gateways(id, owner_id) on delete set null,
  foreign key (device_id, owner_id) references public.vpn_devices(id, user_id) on delete set null
);

create index vpn_devices_user_created on public.vpn_devices(user_id, created_at desc);
create index vpn_gateways_owner_enabled on public.vpn_gateways(owner_id, enabled);
create index vpn_peers_user_state on public.vpn_peers(user_id, state);
create index vpn_sessions_user_started on public.vpn_sessions(user_id, started_at desc);
create index vpn_health_gateway_observed on public.vpn_gateway_health(gateway_id, observed_at desc);
create index vpn_audit_owner_created on public.vpn_audit_events(owner_id, created_at desc);

alter table public.vpn_devices enable row level security;
alter table public.vpn_gateways enable row level security;
alter table public.vpn_peers enable row level security;
alter table public.vpn_sessions enable row level security;
alter table public.vpn_gateway_health enable row level security;
alter table public.vpn_audit_events enable row level security;

-- The signed-in user can register a public identity and read their own records.
-- Enrollment/activation, peer config, sessions and health must be written by a trusted server.
grant select, insert on public.vpn_devices to authenticated;
grant select on public.vpn_gateways, public.vpn_peers, public.vpn_sessions, public.vpn_gateway_health to authenticated;
grant select on public.vpn_audit_events to authenticated;
grant all on public.vpn_devices, public.vpn_gateways, public.vpn_peers, public.vpn_sessions,
  public.vpn_gateway_health, public.vpn_audit_events to service_role;

create policy "users read their vpn devices" on public.vpn_devices
  for select to authenticated using (user_id = (select auth.uid()));
create policy "users register their own public device key" on public.vpn_devices
  for insert to authenticated with check (user_id = (select auth.uid()) and state = 'pending' and revoked_at is null);
create policy "owners read their vpn gateways" on public.vpn_gateways
  for select to authenticated using (owner_id = (select auth.uid()));
create policy "users read their vpn peers" on public.vpn_peers
  for select to authenticated using (user_id = (select auth.uid()));
create policy "users read their vpn sessions" on public.vpn_sessions
  for select to authenticated using (user_id = (select auth.uid()));
create policy "owners read gateway health" on public.vpn_gateway_health
  for select to authenticated using (owner_id = (select auth.uid()));
create policy "owners and admins read audit events" on public.vpn_audit_events
  for select to authenticated using (
    owner_id = (select auth.uid()) or public.has_role((select auth.uid()), 'admin'::public.app_role)
  );

comment on table public.vpn_devices is 'Public device identities only; device private keys remain on-device.';
comment on table public.vpn_peers is 'Peer authorization metadata only; preshared keys and WireGuard configs are never stored.';
comment on table public.vpn_audit_events is 'Do not put keys, secrets, raw configs, IP payloads, or credentials in metadata.';
