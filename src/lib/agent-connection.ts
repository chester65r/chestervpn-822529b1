export const CONNECTION_APP_NAME = "Tunnel Console";
// Stable connection identity: assistants already installed this server under this
// slug, and Claude Code keys a user-scoped install by it. Never derive it from the
// display name, or existing connections stop matching.
export const CONNECTION_SLUG = "tunnel-guardian-compass";
export function connectionSlug(name: string): string {
  let slug = name.normalize("NFKD").replace(/[\u0300-\u036f]/g, "").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 63).replace(/-+$/g, "") || "lovable-app";
  if (["workspace", "computer-use", "claude-in-chrome", "claude-preview", "claude-browser"].includes(slug)) slug += "-app";
  return slug;
}
export function claudeInstallCommand(slug: string, url: string): string {
  return `claude mcp add --scope user --transport http ${slug} '${url.replace(/'/g, "'\\''")}'`;
}
