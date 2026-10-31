CREATE TABLE public.relay_servers (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL,
  name TEXT NOT NULL,
  host TEXT NOT NULL,
  port INTEGER NOT NULL DEFAULT 443 CHECK (port BETWEEN 1 AND 65535),
  transport TEXT NOT NULL DEFAULT 'websocket',
  notes TEXT NOT NULL DEFAULT '',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT, UPDATE, DELETE ON public.relay_servers TO authenticated;
GRANT ALL ON public.relay_servers TO service_role;

ALTER TABLE public.relay_servers ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users manage their own relay servers"
ON public.relay_servers
FOR ALL
TO authenticated
USING (user_id = auth.uid())
WITH CHECK (user_id = auth.uid());