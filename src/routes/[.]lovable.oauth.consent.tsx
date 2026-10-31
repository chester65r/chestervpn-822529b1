import { createFileRoute, redirect, Link } from "@tanstack/react-router";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { supabase } from "@/integrations/supabase/client";
import { oauthClient } from "@/lib/oauth-client";

export const Route = createFileRoute("/.lovable/oauth/consent")({
  staticData: { sitemap: false },
  ssr: false,
  validateSearch: (s: Record<string, unknown>) => ({ authorization_id: typeof s.authorization_id === "string" ? s.authorization_id : "" }),
  head: () => ({ meta: [
    { title: "Approve connection — Tunnel Guardian Compass" },
    { name: "description", content: "Review and approve assistant access to your Tunnel Console account." },
    { property: "og:title", content: "Approve connection — Tunnel Guardian Compass" },
    { property: "og:description", content: "Control assistant access to your saved relay servers." },
    { property: "og:type", content: "website" }, { name: "twitter:card", content: "summary" }, { name: "robots", content: "noindex" },
  ] }),
  beforeLoad: async ({ search, location }) => {
    if (!search.authorization_id) throw new Error("This connection request is missing its authorization ID. Start the connection again from your assistant.");
    const { data, error } = await supabase.auth.getSession();
    if (error) throw error;
    if (!data.session) throw redirect({ to: "/login", search: { next: location.pathname + location.searchStr + location.hash } });
  },
  loader: async ({ location }) => {
    const id = new URLSearchParams(location.searchStr).get("authorization_id");
    if (!id) throw new Error("Missing connection request.");
    const { data, error } = await oauthClient.getAuthorizationDetails(id);
    if (error) throw new Error("This connection request is invalid, expired, or unavailable. Start the connection again from your assistant.");
    if (!data) throw new Error("No connection details were returned. Please try again.");
    const immediate = data.redirect_url ?? data.redirect_to;
    if (immediate && !data.client) throw redirect({ href: immediate });
    const { data: account, error: accountError } = await supabase.auth.getUser();
    if (accountError) throw accountError;
    return { details: data, email: account.user?.email };
  },
  errorComponent: ({ error }) => <main className="mx-auto max-w-md space-y-4 px-6 py-12"><h1 className="text-2xl font-bold">Connection unavailable</h1><p role="alert" className="text-muted-foreground">{error.message}</p><Link to="/">Back to Tunnel Console</Link></main>,
  component: Consent,
});
function Consent() {
  const { details, email } = Route.useLoaderData();
  const { authorization_id } = Route.useSearch();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const client = details.client?.name ?? details.client?.client_name ?? "Your assistant";
  const scopes = details.scopes ?? (details.scope ?? "").split(" ").filter(Boolean);
  async function decide(approve: boolean) {
    setBusy(true); setError("");
    try {
      const result = approve ? await oauthClient.approveAuthorization(authorization_id) : await oauthClient.denyAuthorization(authorization_id);
      if (result.error) throw new Error(result.error.message);
      const target = result.data?.redirect_url ?? result.data?.redirect_to;
      if (!target) throw new Error("No return address was provided. Please restart the connection.");
      window.location.assign(target);
    } catch { setError("Your choice could not be saved. Please try again or restart the connection."); setBusy(false); }
  }
  return <main className="mx-auto max-w-md space-y-5 px-6 py-12">
    <h1 className="break-words text-2xl font-bold">Connect {client} to Tunnel Guardian Compass</h1>
    <div className="space-y-2 text-sm text-muted-foreground"><p className="break-all">Signed in as {email}</p><Button variant="ghost" disabled={busy} onClick={async () => { const result = await supabase.auth.signOut(); if (result.error) { setError("Could not switch accounts. Please try again."); return; } window.location.reload(); }}>Switch account</Button>
      {details.redirect_uri && <p className="break-all">Return address: {details.redirect_uri}</p>}
    </div>
    <p>This lets {client} use this app as you.</p>
    <ul className="list-disc space-y-2 pl-5 text-sm"><li>Read your saved relay server names, hosts, ports and transports</li><li>Read Android compatibility requirements and known blockers</li>{scopes.map(scope => <li key={scope}>{scope === "email" ? "Share your email address" : scope === "profile" ? "Share your basic profile" : scope === "openid" ? "Confirm your account identity" : `Additional permission requested: ${scope}`}</li>)}</ul>
    <p className="text-sm text-muted-foreground">This does not bypass this app’s permissions. Device-local profiles are not available to remote assistants. No VPN is started.</p>
    {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
    <div className="flex gap-3"><Button disabled={busy} onClick={() => void decide(true)}>Approve connection</Button><Button variant="outline" disabled={busy} onClick={() => void decide(false)}>Cancel connection</Button></div>
  </main>;
}