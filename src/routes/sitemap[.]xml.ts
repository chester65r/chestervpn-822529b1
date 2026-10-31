import { createFileRoute } from "@tanstack/react-router";
import { getRouterInstance } from "@tanstack/react-start";
import { sitemapStaticPaths, sitemapXML, type SitemapEntry } from "@/lib/sitemap";

const BASE_URL = "https://tunnel-guardian-compass.lovable.app";

export const Route = createFileRoute("/sitemap.xml")({
  staticData: { sitemap: false },
  server: {
    handlers: {
      GET: async () => {
        const router = await getRouterInstance();
        // No authoritative page-specific content timestamp exists; omit lastmod.
        const entries: SitemapEntry[] = sitemapStaticPaths(router).map((path) => ({ path }));
        if (entries.length === 0) {
          return new Response("No pages are included in this sitemap. Check route decisions and ancestor exclusions.", {
            status: 404, headers: { "Cache-Control": "no-store" },
          });
        }
        return new Response(sitemapXML(BASE_URL, entries), {
          headers: { "Content-Type": "application/xml", "Cache-Control": "public, max-age=3600" },
        });
      },
    },
  },
});