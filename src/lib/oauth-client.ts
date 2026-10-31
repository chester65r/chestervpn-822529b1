import { supabase } from "@/integrations/supabase/client";
type Result = { data: AuthorizationDetails | null; error: { message: string } | null };
export type AuthorizationDetails = {
  client?: { name?: string; client_name?: string; uri?: string };
  redirect_uri?: string;
  scope?: string;
  scopes?: string[];
  redirect_url?: string;
  redirect_to?: string;
};
type OAuthHelpers = {
  getAuthorizationDetails: (id: string) => Promise<Result>;
  approveAuthorization: (id: string) => Promise<Result>;
  denyAuthorization: (id: string) => Promise<Result>;
};
export const oauthClient = (supabase.auth as unknown as { oauth: OAuthHelpers }).oauth;