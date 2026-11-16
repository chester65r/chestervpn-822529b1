// @vitest-environment node
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { isValidSnippetLanguageId, parseAcodeSnippets } from "../lib/acode-snippets";

describe(".acode-snippets parser and repository bundle", () => {
  it("parses kotlin.snippets from the repository .acode-snippets directory", () => {
    const raw = readFileSync(resolve(".acode-snippets/kotlin.snippets"), "utf8");
    const parsed = parseAcodeSnippets(raw, "kotlin");
    expect(parsed.length).toBeGreaterThan(10);
    expect(parsed.some((s) => s.trigger === "fun" && s.body.includes("fun "))).toBe(true);
    expect(parsed.some((s) => s.trigger === "data_class")).toBe(true);
  });

  it("parses typescript.snippets and sh.snippets cleanly", () => {
    const tsRaw = readFileSync(resolve(".acode-snippets/typescript.snippets"), "utf8");
    const tsSnippets = parseAcodeSnippets(tsRaw, "typescript");
    expect(tsSnippets.some((s) => s.trigger === "class")).toBe(true);

    const shRaw = readFileSync(resolve(".acode-snippets/sh.snippets"), "utf8");
    const shSnippets = parseAcodeSnippets(shRaw, "sh");
    expect(shSnippets.some((s) => s.trigger === "#!")).toBe(true);
  });

  it("blocks path traversal in language identifiers", () => {
    expect(isValidSnippetLanguageId("kotlin")).toBe(true);
    expect(isValidSnippetLanguageId("c_cpp")).toBe(true);
    expect(isValidSnippetLanguageId("../package")).toBe(false);
    expect(isValidSnippetLanguageId("kotlin.snippets")).toBe(false);
  });
});
