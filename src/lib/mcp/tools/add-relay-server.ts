import { defineTool, ToolError } from "@lovable.dev/mcp-js";
import { z } from "zod";
import { supabaseForUser } from "../supabase";
import { isHostOrIp } from "@/lib/tunnel";
import { recordAuditEvent } from "@/lib/audit";

export default defineTool({
  name: "add_relay_server",
  title: "Add a relay server",
  description: "Add a new relay server to your account. Requires authentication.",
  inputSchema: {
    name: z.string().min(1, "Name is required"),
    host: z.string().min(1, "Host is required").refine(isHostOrIp, "Enter a valid IPv4 address or domain"),
    port: z.coerce.number().int().min(1).max(65535, "Port must be between 1 and 65535"),
    transport: z.string().optional().default("websocket"),
    notes: z.string().optional().default(""),
  },
  annotations: { readOnlyHint: false, idempotentHint: false, openWorldHint: false },
  handler: async (args, ctx) => {
    const client = supabaseForUser(ctx);
    const userId = ctx.getUserId();
    if (!userId) {
      recordAuditEvent({
        actorId: "anonymous",
        action: "mcp.add_relay_server",
        target: args.host,
        status: "unauthorized",
      });
      throw new ToolError("Authentication required to add a relay server.");
    }

    const { name, host, port, transport, notes } = args;

    const { data, error } = await client.from("relay_servers").insert({
      owner_id: userId,
      name: name.trim(),
      host: host.trim(),
      port,
      transport: transport ?? "websocket",
      notes: notes?.trim() ?? "",
    }).select("id,name,host,port,transport,notes,created_at");

    if (error) {
      recordAuditEvent({
        actorId: userId,
        action: "mcp.add_relay_server",
        target: `${host.trim()}:${port}`,
        status: "failure",
        details: { error: error.message },
      });
      throw new ToolError(`Could not add relay server: ${error.message}`);
    }

    const server = data?.[0];
    if (!server) throw new ToolError("Server was not created");

    recordAuditEvent({
      actorId: userId,
      action: "mcp.add_relay_server",
      target: `${server.host}:${server.port}`,
      status: "success",
      details: { id: String(server.id), transport: server.transport },
    });

    return {
      content: [{ type: "text", text: `Added relay server: ${name} (${host}:${port})` }],
      structuredContent: {
        server: {
          id: String(server.id),
          name: server.name,
          host: server.host,
          port: server.port,
          transport: server.transport,
          notes: server.notes,
          createdAt: server.created_at,
        },
      },
    };
  },
});
