import { QueryClient } from "@tanstack/react-query";
import { createRouter } from "@tanstack/react-router";
import { describe, expect, it } from "vitest";
import { routeTree } from "@/routeTree.gen";
import { sitemapStaticPaths, sitemapXML } from "@/lib/sitemap";

describe("public sitemap", () => {
  it("includes public content pages, excluding auth and server endpoints", () => {
    const router = createRouter({ routeTree, context: { queryClient: new QueryClient() } });
    expect(sitemapStaticPaths(router)).toEqual(["/", "/connect"]);
  });

  it("uses the published origin without inventing a modification date", () => {
    const xml = sitemapXML("https://chestervpn.lovable.app", [{ path: "/" }]);
    expect(xml).toContain("<loc>https://chestervpn.lovable.app/</loc>");
    expect(xml).not.toContain("<lastmod>");
  });

  it("rejects offsite and traversal paths", () => {
    expect(() => sitemapXML("https://chestervpn.lovable.app", [{ path: "//example.com" }])).toThrow();
    expect(() => sitemapXML("https://chestervpn.lovable.app", [{ path: "/../private" }])).toThrow();
  });
});