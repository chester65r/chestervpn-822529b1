import { createFileRoute, Link } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { supabase } from "@/integrations/supabase/client";
import { lovable } from "@/integrations/lovable";
import { oauthReturnPath } from "@/lib/oauth-return-path";

const RETURN_KEY = "tunnel-console:oauth-return";

export const Route = createFileRoute("/login")({
  staticData: { sitemap: false },
  validateSearch: (s: Record<string, unknown>) => ({ next: oauthReturnPath(s['next']) }),
  head: () => ({ meta: [
    { title: "Sign in — Tunnel Console" },
    { name: "description", content: "Sign in to connect an assistant to your Tunnel Console account." },
    { property: "og:title", content: "Sign in — Tunnel Console" },
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
    const complete = (hasSession: boolean) => {
      if (!active || !hasSession) return;
      const pending = typeof window !== "undefined" ? window.sessionStorage.getItem(RETURN_KEY) : null;
      if (pending) window.sessionStorage.removeItem(RETURN_KEY);
      window.location.assign(pending ?? next);
    };
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
  async function googleSignIn() {
    setBusy(true); setMessage("");
    try {
      if (next) window.sessionStorage.setItem(RETURN_KEY, next);
      const result = await lovable.auth.signInWithOAuth("google", { redirect_uri: `${window.location.origin}/login` });
      if (result.error) throw result.error;
      if (result.redirected) return; // browser goes to Google and back to /login
      window.location.assign(next); // session already set without redirect
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Google sign-in failed. Please try again.");
      setBusy(false);
    }
  }
  return <main className="tc-app-backdrop flex min-h-dvh items-center justify-center px-5 py-10">
    <div className="w-full max-w-md space-y-5">
    <div className="space-y-3 text-center">
      <span className="mx-auto grid h-12 w-12 place-items-center rounded-2xl bg-primary/10 text-primary ring-1 ring-inset ring-primary/20">
        <svg aria-hidden="true" viewBox="0 0 24 24" className="h-6 w-6" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z" /><path d="m9 12 2 2 4-4" /></svg>
      </span>
      <h1 className="text-[1.75rem] font-bold leading-tight tracking-tight">{signup ? "Create account" : "Sign in"}</h1>
      <p className="text-sm text-muted-foreground">Tunnel Console</p>
    </div>
    <div className="tc-card space-y-5 p-6 sm:p-7">
    <form className="space-y-4" onSubmit={event => { event.preventDefault(); void submit(); }}>
      <label className="block"><span className="tc-label">Email</span><input className="tc-field py-3" type="email" autoComplete="email" required value={email} onChange={e => setEmail(e.target.value)} /></label>
      <label className="block"><span className="tc-label">Password</span><input className="tc-field py-3" type="password" autoComplete={signup ? "new-password" : "current-password"} required value={password} onChange={e => setPassword(e.target.value)} /></label>
      {message && <p role="status" className="rounded-xl bg-muted px-3.5 py-2.5 text-sm leading-relaxed text-muted-foreground">{message}</p>}
      <Button className="h-11 w-full rounded-xl font-semibold" disabled={busy}>{busy ? "Please wait…" : signup ? "Create account" : "Sign in"}</Button>
    </form>
    <div className="flex items-center gap-3 text-xs text-muted-foreground"><span className="h-px flex-1 bg-border" />or<span className="h-px flex-1 bg-border" /></div>
    <Button variant="outline" className="h-11 w-full rounded-xl font-semibold" disabled={busy} onClick={() => void googleSignIn()}>
      <svg aria-hidden="true" viewBox="0 0 24 24" className="h-4 w-4"><path fill="#4285F4" d="M23.5 12.3c0-.8-.1-1.6-.2-2.3H12v4.5h6.4a5.5 5.5 0 0 1-2.4 3.6v3h3.9c2.3-2.1 3.6-5.2 3.6-8.8Z"/><path fill="#34A853" d="M12 24c3.2 0 6-1.1 8-2.9l-3.9-3a7.2 7.2 0 0 1-10.8-3.8H1.3v3.1A12 12 0 0 0 12 24Z"/><path fill="#FBBC05" d="M5.3 14.3a7.2 7.2 0 0 1 0-4.6V6.6H1.3a12 12 0 0 0 0 10.8l4-3.1Z"/><path fill="#EA4335" d="M12 4.8c1.8 0 3.4.6 4.6 1.8l3.5-3.5A12 12 0 0 0 1.3 6.6l4 3.1A7.2 7.2 0 0 1 12 4.8Z"/></svg>
      Continue with Google
    </Button>
    <Button variant="ghost" className="w-full rounded-xl" disabled={busy} onClick={() => { setSignup(!signup); setMessage(""); }}>{signup ? "Already have an account? Sign in" : "Create an account"}</Button>
    </div>
    <Link className="block text-center text-sm text-muted-foreground transition-colors hover:text-foreground" to="/">Back to Tunnel Console</Link>
    </div>
  </main>;
}