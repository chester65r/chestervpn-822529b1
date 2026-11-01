-- Storage setup for the external project (bolt-native-database-72536136).
-- Prepared 2026-11-01. Run this in one pass once management access is write-enabled;
-- the current MCP connection rejects writes (read-only transaction).
-- Same policy shape as the managed Cloud "user-files" bucket: each authenticated user
-- may only read/write objects inside a folder named with their own user id.

insert into storage.buckets (id, name, public, file_size_limit)
values ('user-files', 'user-files', false, 10485760)
on conflict (id) do update
  set public = excluded.public,
      file_size_limit = excluded.file_size_limit;

create policy "user-files: select own files"
  on storage.objects for select to authenticated
  using (bucket_id = 'user-files' and (storage.foldername(name))[1] = auth.uid()::text);

create policy "user-files: insert own files"
  on storage.objects for insert to authenticated
  with check (bucket_id = 'user-files' and (storage.foldername(name))[1] = auth.uid()::text);

create policy "user-files: update own files"
  on storage.objects for update to authenticated
  using (bucket_id = 'user-files' and (storage.foldername(name))[1] = auth.uid()::text)
  with check (bucket_id = 'user-files' and (storage.foldername(name))[1] = auth.uid()::text);

create policy "user-files: delete own files"
  on storage.objects for delete to authenticated
  using (bucket_id = 'user-files' and (storage.foldername(name))[1] = auth.uid()::text);
