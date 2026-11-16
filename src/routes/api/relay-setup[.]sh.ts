import { createFileRoute } from "@tanstack/react-router";
import { buildRelayInstallerScript } from "@/lib/relay-bundle";

export const Route = createFileRoute("/api/relay-setup.sh")({
  staticData: { sitemap: false },
  server: {
    handlers: {
      GET: async ({ request }) => {
        const url = new URL(request.url);
        const host = url.searchParams.get("host") ?? undefined;
        const script = buildRelayInstallerScript(host);

        return new Response(script, {
          status: 200,
          headers: {
            "Content-Type": "text/x-shellscript; charset=utf-8",
            "Content-Disposition": 'attachment; filename="install-hetzner-relay.sh"',
            "Cache-Control": "no-store",
          },
        });
      },
    },
  },
});
