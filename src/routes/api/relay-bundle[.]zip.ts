import { createFileRoute } from "@tanstack/react-router";
import { buildRelayBundleZip } from "@/lib/relay-bundle";

export const Route = createFileRoute("/api/relay-bundle.zip")({
  staticData: { sitemap: false },
  server: {
    handlers: {
      GET: async () => {
        const zipBytes = buildRelayBundleZip();
        const arrayBuffer = zipBytes.buffer.slice(
          zipBytes.byteOffset,
          zipBytes.byteOffset + zipBytes.byteLength,
        ) as ArrayBuffer;

        return new Response(arrayBuffer, {
          status: 200,
          headers: {
            "Content-Type": "application/zip",
            "Content-Disposition": 'attachment; filename="tunnel-relay-hetzner.zip"',
            "Cache-Control": "no-store",
          },
        });
      },
    },
  },
});
