import { defineTool, ToolError } from "@lovable.dev/mcp-js";
import { supabaseForUser } from "../supabase";

export default defineTool({
  name: "list_relay_servers",
  title: "List my relay servers",
  description: "List up to 100 relay servers belonging to the signed-in caller; does not connect to them or access device-local profiles.",
  inputSchema: {},
  annotations: { readOnlyHint: true, idempotentHint: true, openWorldHint: false },
  handler: async (_args, ctx) => {
    const client = supabaseForUser(ctx);
    const { data, error } = await client.from("relay_servers")
      .select("id,name,host,port,transport").order("created_at", { ascending: false }).limit(100);
    if (error) throw new ToolError("Could not load your relay servers. Please try again.");
    const servers = (data ?? []).map(row => ({ id: String(row.id), name: String(row.name), host: String(row.host), port: Number(row.port), transport: String(row.transport) }));
    return { content: [{ type: "text", text: servers.length ? JSON.stringify(servers) : "No saved relay servers." }], structuredContent: { servers } };
  },
});