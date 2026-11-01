-- Storage setup for the external project (bolt-native-database-72536136).
-- PREPARED ONLY. Apply after additional-backend-permissions.sql on the external project.
-- Create PRIVATE user-files through the external Storage API, not SQL.
-- Required settings: public=false, file_size_limit=10485760,
-- allowed_mime_types=['text/plain','application/pdf']. Review content before upload.
-- Available managed storage tools target Cloud, not the external project.

create policy "user-files: select own files"
  on storage.objects for select to authenticated
  using (bucket_id = 'user-files' and (storage.foldername(name))[1] = (select auth.uid())::text and (select public.tunnel_confirmed_owner()));

create policy "user-files: insert own files"
  on storage.objects for insert to authenticated
  with check (bucket_id = 'user-files' and (storage.foldername(name))[1] = (select auth.uid())::text and (select public.tunnel_confirmed_owner()));

create policy "user-files: update own files"
  on storage.objects for update to authenticated
  using (bucket_id = 'user-files' and (storage.foldername(name))[1] = (select auth.uid())::text and (select public.tunnel_confirmed_owner()))
  with check (bucket_id = 'user-files' and (storage.foldername(name))[1] = (select auth.uid())::text and (select public.tunnel_confirmed_owner()));

create policy "user-files: delete own files"
  on storage.objects for delete to authenticated
  using (bucket_id = 'user-files' and (storage.foldername(name))[1] = (select auth.uid())::text and (select public.tunnel_confirmed_owner()));
