create or replace function public.has_role(_user_id uuid, _role public.app_role)
returns boolean
language sql
stable
security invoker
set search_path = ''
as $$
  select (select auth.uid()) = _user_id
    and exists (
      select 1
      from public.user_roles
      where user_id = _user_id
        and role = _role
    )
$$;

drop policy if exists "Users manage their own relay servers" on public.relay_servers;
create policy "Users manage their own relay servers"
on public.relay_servers
for all
to authenticated
using (user_id = (select auth.uid()))
with check (user_id = (select auth.uid()));

drop policy if exists "Users read own roles" on public.user_roles;
create policy "Users read own roles"
on public.user_roles
for select
to authenticated
using ((select auth.uid()) = user_id);
