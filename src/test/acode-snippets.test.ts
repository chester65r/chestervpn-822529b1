// @vitest-environment node
import { describe, expect, it, vi } from "vitest";
import { isValidSnippetLanguageId, parseAcodeSnippets } from "../lib/acode-snippets";
import { listSnippetLanguages, readSnippetLanguage } from "../lib/acode-snippet-library.server";

describe("Acode parser and bundled library", () => {
  it("loads Kotlin templates from the application bundle", () => {
    const { snippets } = readSnippetLanguage("kotlin");
    expect(snippets.length).toBeGreaterThan(10);
    expect(snippets.some((s) => s.trigger === "fun" && s.body.includes("fun "))).toBe(true);
    expect(snippets.some((s) => s.trigger === "data_class")).toBe(true);
  });

  it("loads TypeScript and shell templates", () => {
    expect(readSnippetLanguage("typescript").snippets.some((s) => s.trigger === "class")).toBe(
      true,
    );
    expect(readSnippetLanguage("sh").snippets.some((s) => s.trigger === "#!")).toBe(true);
  });

  it("lists all six UI language packs with matching nonempty contents", () => {
    const languages = listSnippetLanguages();
    expect(languages.map((lang) => lang.id)).toEqual([
      "kotlin",
      "python",
      "sh",
      "sql",
      "tsx",
      "typescript",
    ]);
    for (const lang of languages) {
      const { snippets } = readSnippetLanguage(lang.id);
      expect(lang.filename).toBe(`${lang.id}.snippets`);
      expect(lang.snippetCount).toBeGreaterThan(0);
      expect(snippets).toHaveLength(lang.snippetCount);
      expect(snippets.every((snippet) => snippet.language === lang.id)).toBe(true);
    }
  });

  it("does not depend on the runtime working directory", () => {
    const cwd = vi.spyOn(process, "cwd").mockImplementation(() => {
      throw new Error("Source tree unavailable");
    });
    try {
      expect(listSnippetLanguages()).toHaveLength(6);
      expect(readSnippetLanguage(" kotlin ").snippets.length).toBeGreaterThan(10);
    } finally {
      cwd.mockRestore();
    }
  });

  it("parses CRLF input and preserves placeholders", () => {
    expect(
      parseAcodeSnippets("snippet fun\r\n\tfun ${1:name}() {\r\n\t\t$0\r\n\t}\r\n", "kotlin"),
    ).toEqual([{ trigger: "fun", language: "kotlin", body: "fun ${1:name}() {\n\t$0\n}" }]);
  });

  it("rejects missing packs and path traversal", () => {
    expect(() => readSnippetLanguage("missing")).toThrow("Unknown snippet language");
    for (const lang of ["../package", "kotlin.snippets", "../kotlin", ""]) {
      expect(() => readSnippetLanguage(lang)).toThrow("Invalid snippet language identifier");
    }
    expect(isValidSnippetLanguageId("kotlin")).toBe(true);
    expect(isValidSnippetLanguageId("c_cpp")).toBe(true);
    expect(isValidSnippetLanguageId("../package")).toBe(false);
  });
});
