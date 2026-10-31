import { createFileRoute, Link } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { ArrowLeft, Check, Copy, ExternalLink } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { CONNECTION_APP_NAME, claudeInstallCommand, connectionSlug } from "@/lib/agent-connection";

export const Route = createFileRoute("/connect")({
  staticData: { sitemap: true },
  head: () => ({ meta: [
    { title: "Connect your assistant — Tunnel Guardian Compass" },
    { name: "description", content: "Connect ChatGPT, Claude, Claude Code or another AI assistant to Tunnel Guardian Compass and refresh its connection." },
    { property: "og:title", content: "Connect your assistant — Tunnel Guardian Compass" },
    { property: "og:description", content: "Copy your MCP server URL and follow assistant-specific connection and refresh steps." },
    { property: "og:type", content: "website" }, { name: "twitter:card", content: "summary" },
  ] }),
  component: ConnectionGuide,
});
type Client = "chatgpt" | "claude" | "code" | "other";
function External({ href, children }: { href: string; children: React.ReactNode }) {
  return <a className="inline-flex items-center gap-1 text-primary underline underline-offset-4" href={href} target="_blank" rel="noopener noreferrer">{children}<ExternalLink className="h-3 w-3 shrink-0" /></a>;
}
function CopyBlock({ text, label }: { text: string; label: string }) {
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState("");
  return <div className="space-y-2">
    <div className="flex items-start gap-2 rounded-md border border-border bg-muted p-3">
      <code className="min-w-0 flex-1 break-all text-sm text-foreground">{text || "Loading address…"}</code>
      <Button variant="ghost" size="icon" disabled={!text} aria-label={label} title={label} onClick={async () => {
        try { await navigator.clipboard.writeText(text); setCopied(true); setError(""); }
        catch { setError("Copy the text above manually; clipboard access is unavailable."); }
      }}>{copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}</Button>
    </div>
    {copied && <p role="status" className="text-xs text-primary">Copied</p>}
    {error && <p role="status" className="text-xs text-muted-foreground">{error}</p>}
  </div>;
}
function ConnectionGuide() {
  const [url, setUrl] = useState("");
  const [client, setClient] = useState<Client>("chatgpt");
  useEffect(() => { setUrl(new URL("/mcp", window.location.origin).toString()); }, []);
  const claudeLink = `https://claude.ai/customize/connectors?modal=add-custom-connector&connectorName=${encodeURIComponent(CONNECTION_APP_NAME)}&connectorUrl=${encodeURIComponent(url)}`;
  const slug = connectionSlug(CONNECTION_APP_NAME);
  return <main className="mx-auto min-h-dvh max-w-2xl px-5 py-6 sm:px-8 sm:py-10">
    <Link to="/" className="mb-6 inline-flex items-center gap-2 text-sm text-muted-foreground"><ArrowLeft className="h-4 w-4" />Tunnel Console</Link>
    <h1 className="text-2xl font-bold">Connect your assistant</h1>
    <section className="space-y-3 border-b border-border py-6">
      <h2 className="text-sm font-semibold text-muted-foreground">MCP SERVER URL</h2>
      <CopyBlock text={url} label="Copy MCP server URL" />
    </section>
    <Tabs value={client} onValueChange={value => setClient(value as Client)} className="mt-6">
      <TabsList className="grid h-auto w-full grid-cols-2 gap-1 sm:grid-cols-4">
        <TabsTrigger value="chatgpt">ChatGPT</TabsTrigger><TabsTrigger value="claude">Claude</TabsTrigger><TabsTrigger value="code">Claude Code</TabsTrigger><TabsTrigger value="other">Other MCP clients</TabsTrigger>
      </TabsList>
    </Tabs>
    <section className="space-y-4 border-b border-border py-6">
      <h2 className="text-lg font-semibold">Connect</h2>
      <ol className="list-decimal space-y-4 pl-5 text-sm leading-relaxed">
        {client === "chatgpt" && <>
          <li>Open <External href="https://chatgpt.com/#settings/Connectors/Advanced">ChatGPT Apps settings</External> and enable Developer mode. Read the risk notice. If unavailable, ask your ChatGPT admin to enable it.</li>
          <li>Open <External href="https://chatgpt.com/plugins#settings/Connectors?create-connector=true&redirectAfter=%2Fplugins">New plugin</External>.</li>
          <li>Enter <strong>{CONNECTION_APP_NAME}</strong> as the name and paste the MCP server URL above.</li>
          <li>Review the details, choose OAuth if asked, check “I understand and want to continue”, then click “Create”. ChatGPT shows this warning for every custom MCP server. Finish sign-in and approve the connection.</li>
          <li>Enable the app from the chat composer, then ask ChatGPT to use Tunnel Guardian Compass.</li>
        </>}
        {client === "claude" && <>
          <li>{url ? <External href={claudeLink}>Add to Claude</External> : "Loading Claude connection link…"} to open the connector dialog with the app name and URL filled in.</li>
          <li>Review the details and click “Add”. Sign in and approve the connection when prompted.</li>
          <li>If the form does not open, go to <External href="https://claude.ai/customize/connectors">Claude Connectors</External>, choose “Add custom connector”, enter <strong>{CONNECTION_APP_NAME}</strong> and paste the MCP server URL above.</li>
          <li>Enable the connector from the chat composer, then ask Claude to use Tunnel Guardian Compass.</li>
        </>}
        {client === "code" && <>
          <li>Copy this command and run it in a terminal:<div className="mt-3"><CopyBlock text={url ? claudeInstallCommand(CONNECTION_APP_NAME, url) : ""} label="Copy Claude Code install command" /></div></li>
          <li>Start Claude Code and run <code>/mcp</code> to confirm the app is connected. Select the app to sign in and approve access when prompted.</li>
          <li>Ask Claude Code to use Tunnel Guardian Compass.</li>
        </>}
        {client === "other" && <>
          <li>Open your assistant’s MCP server or custom connector settings.</li>
          <li>Create a remote MCP server connection.</li>
          <li>Name it <strong>{CONNECTION_APP_NAME}</strong> and paste the MCP server URL above.</li>
          <li>Finish the sign-in and authorization prompts.</li>
          <li>Enable the connection, then ask the assistant to use Tunnel Guardian Compass.</li>
        </>}
      </ol>
    </section>
    <section className="space-y-4 py-6">
      <h2 className="text-lg font-semibold">Refresh after the app changes</h2>
      <p className="text-sm text-muted-foreground">After updates are published, refresh your connection to load the latest tools.</p>
      <ol className="list-decimal space-y-4 pl-5 text-sm leading-relaxed">
        {client === "chatgpt" && <><li>Open <External href="https://chatgpt.com/plugins">ChatGPT Plugins</External> and select this app.</li><li>Scroll to “Information” and click “Refresh”.</li><li>If the URL changed, delete the app from Plugins and repeat the connect steps with the latest URL. ChatGPT cannot change an existing app’s URL.</li><li>Start a new chat and ask ChatGPT to use the app.</li></>}
        {client === "claude" && <><li>Open <External href="https://claude.ai/customize/connectors">Claude Connectors</External> and select this connector.</li><li>Refresh or update its tools.</li><li>If the URL changed, remove the connector and repeat the connect steps with the latest URL. Claude cannot change an existing connector’s URL.</li><li>Ask Claude to use the app.</li></>}
        {client === "code" && <><li>Start a new Claude Code session to load the app’s latest tools.</li><li>If the URL changed, run <code className="break-all">claude mcp remove {slug}</code>, then run the install command above with the latest URL.</li><li>Ask Claude Code to use the app.</li></>}
        {client === "other" && <><li>Open your assistant’s MCP server or connector settings.</li><li>Select the connection for this app.</li><li>Refresh the tool list, reload the server or reconnect it.</li><li>If the URL changed, paste the latest URL from above.</li><li>Start a new chat or session and ask the assistant to use the app.</li></>}
      </ol>
    </section>
  </main>;
}