import { createServerFn } from "@tanstack/react-start";
import { z } from "zod";
import type { ConnectionDiagnostics } from "./connection-help";
import { requestConnectionExplanation } from "./connection-help.server";

// This schema must stay in lockstep with the ConnectionDiagnostics type in
// ./connection-help (the shape the server helper consumes).
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
export type { ConnectionDiagnostics };

// Thin wrapper: validation lives here, the provider call lives in
// ./connection-help.server (operator-configured AI_HELP_* env, no gateway).
export const explainConnection = createServerFn({ method: "POST" })
  .inputValidator((d: unknown) => DiagnosticsSchema.parse(d))
  .handler(async ({ data }): Promise<{ text: string } | { error: string }> => {
    return requestConnectionExplanation(data);
  });
