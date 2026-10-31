import { defineTool } from "@lovable.dev/mcp-js";
import { androidChecklistMarkdown } from "../../android-checklist";

export default defineTool({
  name: "get_android_compatibility",
  title: "Android compatibility requirements",
  description: "Read the Android and relay checklist, including known blockers and unverified production requirements; these are not device test results.",
  inputSchema: {},
  annotations: { readOnlyHint: true, idempotentHint: true, openWorldHint: false },
  handler: () => ({ content: [{ type: "text", text: androidChecklistMarkdown() }] }),
});