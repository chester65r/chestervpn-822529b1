import { createFileRoute } from "@tanstack/react-router";
import { buildAndroidStudioProjectZip } from "@/lib/android-project-bundle";

export const Route = createFileRoute("/api/android-project.zip")({
  staticData: { sitemap: false },
  server: {
    handlers: {
      GET: async ({ request }) => {
        const url = new URL(request.url);
        const host = url.searchParams.get("host") ?? undefined;
        const portParam = Number(url.searchParams.get("port"));
        const sni = url.searchParams.get("sni") ?? undefined;
        const networkProfileId = url.searchParams.get("profile") ?? undefined;
        const serverPublicKey = url.searchParams.get("serverKey") ?? undefined;
        const protocolParam = url.searchParams.get("protocol");
        const utlsParam = url.searchParams.get("utls");
        const uuid = url.searchParams.get("uuid") ?? undefined;
        const realityPublicKey = url.searchParams.get("pbk") ?? undefined;
        const realityShortId = url.searchParams.get("sid") ?? undefined;
        const trojanPassword = url.searchParams.get("password") ?? undefined;
        const wsHost = url.searchParams.get("wsHost") ?? undefined;
        const wsPath = url.searchParams.get("wsPath") ?? undefined;

        const protocol =
          protocolParam === "trojan-tls"
            ? "trojan-tls"
            : protocolParam === "vless-ws-tls" || networkProfileId?.includes("mobily")
              ? "vless-ws-tls"
              : "vless-reality";
        const utlsFingerprint =
          protocolParam || utlsParam
            ? utlsParam === "ios" || utlsParam === "firefox" || utlsParam === "safari" || utlsParam === "randomized"
              ? utlsParam
              : "chrome"
            : undefined;

        const zipBytes = buildAndroidStudioProjectZip({
          endpointHost: host,
          endpointPort: Number.isInteger(portParam) && portParam > 0 && portParam <= 65535 ? portParam : undefined,
          sni,
          networkProfileId,
          serverPublicKey,
          protocol,
          utlsFingerprint,
          uuid,
          realityPublicKey,
          realityShortId,
          trojanPassword,
          wsHost,
          wsPath,
        });

        const arrayBuffer = zipBytes.buffer.slice(
          zipBytes.byteOffset,
          zipBytes.byteOffset + zipBytes.byteLength,
        ) as ArrayBuffer;

        return new Response(arrayBuffer, {
          status: 200,
          headers: {
            "Content-Type": "application/zip",
            "Content-Disposition": 'attachment; filename="tunnel-console-android.zip"',
            "Cache-Control": "no-store",
          },
        });
      },
    },
  },
});
