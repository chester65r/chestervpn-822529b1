import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { buildAndroidStudioProjectZip } from "../src/lib/android-project-bundle";
import { buildRelayBundleZip } from "../src/lib/relay-bundle";
import {
  buildCarrierSingBoxClientConfig,
  buildSingBoxClientConfig,
  buildSingBoxServerConfig,
  validateSingBoxClientConfig,
} from "../src/lib/singbox";

test("sing-box client configs pass schema validation for STC (VLESS-Reality), Mobily (VLESS-WSS-CDN), and Trojan-TLS", () => {
  const stcConfig = buildCarrierSingBoxClientConfig("stc", {
    serverHost: "2.29.8.194",
    serverPort: 443,
    sni: "www.stc.com.sa",
    sniSplitMinBytes: 5,
    sniSplitMaxBytes: 15,
  });
  assert.deepEqual(validateSingBoxClientConfig(stcConfig), []);

  const mobilyConfig = buildCarrierSingBoxClientConfig("mobily", {
    serverHost: "2.29.8.194",
    serverPort: 443,
    sni: "www.mobily.com.sa",
    wsHost: "cdn.mobily.com.sa",
    wsPath: "/cdn-ws",
    utlsFingerprint: "chrome",
  });
  assert.deepEqual(validateSingBoxClientConfig(mobilyConfig), []);

  const trojanConfig = buildSingBoxClientConfig({
    protocol: "trojan-tls",
    serverHost: "2.29.8.194",
    serverPort: 8443,
    sni: "static.194.8.29.2.clients.your-server.de",
    utlsFingerprint: "ios",
    fragmentEnabled: true,
    recordFragmentEnabled: true,
  });
  assert.deepEqual(validateSingBoxClientConfig(trojanConfig), []);
});

test("relay-server/config.json matches buildSingBoxServerConfig ports and outbounds", () => {
  const onDisk = JSON.parse(
    readFileSync(resolve(process.cwd(), "relay-server/config.json"), "utf8"),
  ) as Record<string, unknown>;
  const generated = buildSingBoxServerConfig();
  assert.equal((onDisk.inbounds as unknown[]).length, (generated.inbounds as unknown[]).length);
  assert.equal((onDisk.outbounds as Array<{ tag: string }>).map((o) => o.tag).join(","), "direct,block");
});

test("Android Studio project ZIP and Hetzner relay ZIP package cleanly", () => {
  const androidZip = buildAndroidStudioProjectZip({
    endpointHost: "2.29.8.194",
    endpointPort: 443,
    protocol: "vless-reality",
    utlsFingerprint: "chrome",
  });
  assert.ok(androidZip.byteLength > 20_000);

  const relayZip = buildRelayBundleZip();
  const relayListing = Buffer.from(relayZip).toString("latin1");
  assert.ok(relayListing.includes("tunnel-relay/config.json"));
  assert.ok(relayListing.includes("tunnel-relay/sing-box.service"));
  assert.ok(relayListing.includes("tunnel-relay/setup-singbox.sh"));
  assert.ok(!relayListing.includes("gateway.mjs"));
});
