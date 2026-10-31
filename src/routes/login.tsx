import { createFileRoute, Link } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { supabase } from "@/integrations/supabase/client";
import { oauthReturnPath } from "@/lib/oauth-return-path";

export const Route = createFileRoute("/login")({
  staticData: { sitemap: false },
  validateSearch: (s: Record<string, unknown>) => ({ next: oauthReturnPath(s['next']) }),
  head: () => ({ meta: [
    { title: "Sign in — Tunnel Guardian Compass" },
    { name: "description", content: "Sign in to connect an assistant to your Tunnel Console account." },
    { property: "og:title", content: "Sign in — Tunnel Guardian Compass" },
    { property: "og:description", content: "Secure account sign-in for Tunnel Console integrations." },
    { property: "og:type", content: "website" }, { name: "twitter:card", content: "summary" },
    { name: "robots", content: "noindex" },
  ] }),
  component: Login,
});
function Login() {
  const { next } = Route.useSearch();
  const [signup, setSignup] = useState(false);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  useEffect(() => {
    let active = true;
    const complete = (hasSession: boolean) => { if (active && hasSession) window.location.assign(next); };
    void supabase.auth.getSession().then(({ data }) => complete(Boolean(data.session))).catch(() => setMessage("Could not check your account. Please try signing in."));
    const { data } = supabase.auth.onAuthStateChange((_event, session) => complete(Boolean(session)));
    return () => { active = false; data.subscription.unsubscribe(); };
  }, [next]);
  async function submit() {
    setBusy(true); setMessage("");
    try {
      if (signup) {
        const { data, error } = await supabase.auth.signUp({ email, password, options: { emailRedirectTo: new URL(next, window.location.origin).href } });
        if (error) throw error;
        if (data.session) window.location.assign(next);
        else setMessage("Check your email to confirm your account and continue this connection.");
      } else {
        const { error } = await supabase.auth.signInWithPassword({ email, password });
        if (error) throw error;
        window.location.assign(next);
      }
    } catch (error) { setMessage(error instanceof Error ? error.message : "Sign-in failed. Please try again."); }
    finally { setBusy(false); }
  }
  return <main className="mx-auto max-w-md space-y-5 px-6 py-12">
    <h1 className="text-2xl font-bold">{signup ? "Create account" : "Sign in"}</h1>
    <p className="text-muted-foreground">Tunnel Guardian Compass</p>
    <form className="space-y-4" onSubmit={event => { event.preventDefault(); void submit(); }}>
      <label className="block space-y-2"><span>Email</span><input className="w-full rounded-md border border-input bg-muted p-3" type="email" autoComplete="email" required value={email} onChange={e => setEmail(e.target.value)} /></label>
      <label className="block space-y-2"><span>Password</span><input className="w-full rounded-md border border-input bg-muted p-3" type="password" autoComplete={signup ? "new-password" : "current-password"} required value={password} onChange={e => setPassword(e.target.value)} /></label>
      {message && <p role="status" className="text-sm text-muted-foreground">{message}</p>}
      <Button className="w-full" disabled={busy}>{busy ? "Please wait…" : signup ? "Create account" : "Sign in"}</Button>
    </form>
    <Button variant="ghost" disabled={busy} onClick={() => { setSignup(!signup); setMessage(""); }}>{signup ? "Already have an account? Sign in" : "Create an account"}</Button>
    <Link className="block text-sm text-muted-foreground" to="/">Back to Tunnel Console</Link>
  </main>;
}