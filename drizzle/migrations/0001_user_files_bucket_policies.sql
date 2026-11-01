-- Per-user private files in the "user-files" bucket.
-- Each authenticated user may read/write only objects inside a folder named with their own user id.
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