export const CONNECTION_APP_NAME = "Tunnel Guardian Compass";
export function connectionSlug(name: string): string {
  let slug = name.normalize("NFKD").replace(/[\u0300-\u036f]/g, "").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 63).replace(/-+$/g, "") || "lovable-app";
  if (["workspace", "computer-use", "claude-in-chrome", "claude-preview", "claude-browser"].includes(slug)) slug += "-app";
  return slug;
}
export function claudeInstallCommand(name: string, url: string): string {
  return `claude mcp add --scope user --transport http ${connectionSlug(name)} '${url.replace(/'/g, "'\\''")}'`;
}