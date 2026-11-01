-- PREPARED ONLY: external project, never managed Cloud. Not applied.
create function public.tunnel_confirmed_owner()
returns boolean language sql stable security definer set search_path = ''
as $$ select exists (select 1 from auth.users where id = (select auth.uid())
and email_confirmed_at is not null and coalesce(is_anonymous, false) = false); $$;
revoke all on function public.tunnel_confirmed_owner() from public, anon;
grant execute on function public.tunnel_confirmed_owner() to authenticated, service_role;

create table public.tunnel_profiles (
id uuid primary key default gen_random_uuid(), user_id uuid not null default auth.uid(),
preset text not null check (preset in ('custom','standard-tls','websocket')),
port integer not null default 443 check (port between 1 and 65535), created_at timestamptz not null default now());
grant select, insert, update, delete on public.tunnel_profiles to authenticated;
grant all on public.tunnel_profiles to service_role;
alter table public.tunnel_profiles enable row level security;
create policy "confirmed owner profiles" on public.tunnel_profiles for all to authenticated
using (user_id = (select auth.uid()) and (select public.tunnel_confirmed_owner()))
with check (user_id = (select auth.uid()) and (select public.tunnel_confirmed_owner()));
create index tunnel_profiles_owner on public.tunnel_profiles(user_id);

create table public.tunnel_connection_history (
id uuid primary key default gen_random_uuid(), user_id uuid not null default auth.uid(),
source text not null check (source in ('simulation','native')),
status text not null check (status in ('connected','disconnected','error')),
error_category text check (error_category in ('validation','network','authentication','timeout','unknown')),
occurred_at timestamptz not null default now());
grant select, insert, delete on public.tunnel_connection_history to authenticated;
grant all on public.tunnel_connection_history to service_role;
alter table public.tunnel_connection_history enable row level security;
create policy "confirmed owner history" on public.tunnel_connection_history for all to authenticated
using (user_id = (select auth.uid()) and (select public.tunnel_confirmed_owner()))
with check (user_id = (select auth.uid()) and (select public.tunnel_confirmed_owner()));
create index tunnel_history_owner on public.tunnel_connection_history(user_id, occurred_at);

create table public.tunnel_server_health (
id uuid primary key default gen_random_uuid(), user_id uuid not null, server_id uuid not null,
status text not null check (status in ('healthy','unreachable','degraded')),
latency_ms integer check (latency_ms between 0 and 600000), observed_at timestamptz not null default now());
grant select, delete on public.tunnel_server_health to authenticated;
grant all on public.tunnel_server_health to service_role;
alter table public.tunnel_server_health enable row level security;
create policy "confirmed owner reads health" on public.tunnel_server_health for select to authenticated
using (user_id = (select auth.uid()) and (select public.tunnel_confirmed_owner()));
create policy "confirmed owner deletes health" on public.tunnel_server_health for delete to authenticated
using (user_id = (select auth.uid()) and (select public.tunnel_confirmed_owner()));
create index tunnel_health_owner on public.tunnel_server_health(user_id, observed_at);
-- Browser cannot INSERT/UPDATE monitoring. Real collector authorization is separate.