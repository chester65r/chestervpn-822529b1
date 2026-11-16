import { readdirSync, readFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { createServerFn } from "@tanstack/react-start";
import {
  isValidSnippetLanguageId,
  parseAcodeSnippets,
  type AcodeSnippet,
  type AcodeSnippetLanguageSummary,
} from "./acode-snippets";

const SNIPPETS_DIR = resolve(process.cwd(), ".acode-snippets");

export const listAcodeSnippetLanguages = createServerFn({ method: "GET" }).handler(
  async (): Promise<AcodeSnippetLanguageSummary[]> => {
    try {
      const files = readdirSync(SNIPPETS_DIR)
        .filter((f) => f.endsWith(".snippets"))
        .sort((a, b) => a.localeCompare(b));

      return files.map((filename) => {
        const id = filename.slice(0, -".snippets".length);
        const content = readFileSync(join(SNIPPETS_DIR, filename), "utf8");
        const count = parseAcodeSnippets(content, id).length;
        return { id, filename, snippetCount: count };
      });
    } catch {
      return [];
    }
  },
);

export const readAcodeSnippetLanguage = createServerFn({ method: "GET" })
  .inputValidator((d: { language: string }) => d)
  .handler(async ({ data }): Promise<{ language: string; snippets: AcodeSnippet[] }> => {
    const lang = (data?.language ?? "kotlin").trim();
    if (!isValidSnippetLanguageId(lang)) {
      throw new Error("Invalid snippet language identifier");
    }

    const filePath = join(SNIPPETS_DIR, `${lang}.snippets`);
    if (!filePath.startsWith(SNIPPETS_DIR + "/") && !filePath.startsWith(SNIPPETS_DIR + "\\")) {
      throw new Error("Invalid snippet path");
    }

    const raw = readFileSync(filePath, "utf8");
    return {
      language: lang,
      snippets: parseAcodeSnippets(raw, lang),
    };
  });
