import { isValidSnippetLanguageId, parseAcodeSnippets } from "./acode-snippets";

// Import as text at build time: serverless deployments do not ship the source
// tree or guarantee that process.cwd() is the repository root.
const packs = import.meta.glob<string>("../data/acode-snippets/*.snippets", {
  query: "?raw",
  import: "default",
  eager: true,
});

export function listSnippetLanguages() {
  return Object.entries(packs)
    .map(([path, raw]) => {
      const filename = path.slice(path.lastIndexOf("/") + 1);
      const id = filename.slice(0, -".snippets".length);
      return { id, filename, snippetCount: parseAcodeSnippets(raw, id).length };
    })
    .sort((a, b) => a.id.localeCompare(b.id));
}

export function readSnippetLanguage(language: string) {
  const lang = language.trim();
  if (!isValidSnippetLanguageId(lang)) {
    throw new Error("Invalid snippet language identifier");
  }
  const raw = packs[`../data/acode-snippets/${lang}.snippets`];
  if (raw === undefined) {
    throw new Error("Unknown snippet language");
  }
  return { language: lang, snippets: parseAcodeSnippets(raw, lang) };
}
