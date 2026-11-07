import { createServerFn } from "@tanstack/react-start";
import { z } from "zod";

const MODEL = "google/gemini-3.8-flash";

const DiagnosticsSchema = z.object({
  status: z.enum(["disconnected", "connecting", "connected"]),
  serverConfigured: z.boolean(),
  port: z.string().max(10),
  sniSet: z.boolean(),
  payloadSet: z.boolean(),
  profileErrors: z.record(z.string(), z.string().max(200)),
  browserOnline: z.boolean(),
  sessionSeconds: z.number().min(0).max(1e7),
  downloadMbps: z.number().min(0).max(1e5),
  uploadMbps: z.number().min(0).max(1e5),
  lastEvent: z.string().max(200),
});
export type ConnectionDiagnostics = z.infer<typeof DiagnosticsSchema>;

const SYSTEM = `You are the troubleshooting helper inside "Tunnel Console", a VPN dashboard.
The browser dashboard simulates the connection; real tunnels run in the companion Android WireGuard app against the user's own server.
Using ONLY the diagnostics given, explain in plain, friendly language what is wrong (or confirm everything looks fine) and list 2-4 concrete next steps.
Keep it under 120 words. No markdown headings. Never suggest bypassing carrier billing, spoofing hosts, or using carrier/social hostnames.`;

export const explainConnection = createServerFn({ method: "POST" })
  .inputValidator((d: unknown) => DiagnosticsSchema.parse(d))
  .handler(async ({ data }): Promise<{ text: string } | { error: string }> => {
    const key = process.env["LOVABLE_API_KEY"];
    if (!key) return { error: "The AI helper isn't configured yet." };
    let res: Response;
    try {
      res = await fetch("https://ai.gateway.lovable.dev/v1/chat/completions", {
        method: "POST",
        headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json", "X-Lovable-AIG-SDK": "fetch" },
        body: JSON.stringify({
          model: MODEL,
          stream: true,
          messages: [
            { role: "system", content: SYSTEM },
            { role: "user", content: `Diagnostics:\n${JSON.stringify(data, null, 2)}` },
          ],
        }),
      });
    } catch (e) {
      console.error("[connection-help] network", e);
      return { error: "Couldn't reach the AI helper. Try again shortly." };
    }
    if (!res.ok || !res.body) {
      const body = await res.text().catch(() => "");
      console.error("[connection-help]", res.status, body.slice(0, 300));
      if (res.status === 429) return { error: "The AI helper is busy. Please wait a moment and try again." };
      if (res.status === 402) return { error: "AI credits have run out. Add credits in Settings → Plans & credits." };
      if (res.status === 403) return { error: "The AI helper isn't available for this workspace right now." };
      return { error: "The AI helper couldn't answer right now." };
    }
    const reader = res.body.getReader();
    const dec = new TextDecoder();
    let buf = "";
    let text = "";
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += dec.decode(value, { stream: true });
      const lines = buf.split("\n");
      buf = lines.pop() ?? "";
      for (const line of lines) {
        const t = line.trim();
        if (!t.startsWith("data:")) continue;
        const payload = t.slice(5).trim();
        if (payload === "[DONE]") continue;
        try {
          const j = JSON.parse(payload);
          text += j.choices?.[0]?.delta?.content ?? "";
        } catch {
          /* partial frame */
        }
      }
    }
    text = text.trim();
    return text ? { text } : { error: "The AI helper returned no explanation." };
  });
