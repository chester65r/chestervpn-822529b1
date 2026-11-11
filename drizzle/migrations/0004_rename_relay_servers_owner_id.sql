-- The relay_servers owner column was renamed from user_id to owner_id on the
-- live backend (confirmed by the 2026-11-11 backup header). This migration
-- brings the repo's migration history in line with the live schema and the
-- runtime code, which writes owner_id. It is idempotent: on a database that
-- already has owner_id, the rename is skipped.

DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'relay_servers'
      AND column_name = 'user_id'
  ) THEN
    ALTER TABLE public.relay_servers RENAME COLUMN user_id TO owner_id;
  END IF;
END $$;

-- The RLS policy from 0000 references user_id; re-create it against owner_id.
DROP POLICY IF EXISTS "Users manage their own relay servers" ON public.relay_servers;

CREATE POLICY "Users manage their own relay servers"
ON public.relay_servers
FOR ALL
TO authenticated
USING (owner_id = auth.uid())
WITH CHECK (owner_id = auth.uid());
