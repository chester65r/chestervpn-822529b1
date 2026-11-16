export type AcodeSnippet = {
  trigger: string;
  body: string;
  language: string;
};

export type AcodeSnippetLanguageSummary = {
  id: string;
  filename: string;
  snippetCount: number;
};

const VALID_LANG_ID = /^[a-z0-9_]+$/i;

export function isValidSnippetLanguageId(id: string): boolean {
  return VALID_LANG_ID.test(id);
}

/**
 * Parses standard TextMate/SnipMate/Acode `.snippets` format:
 *   snippet <trigger>
 *   \t<body>
 */
export function parseAcodeSnippets(raw: string, language: string): AcodeSnippet[] {
  const lines = raw.replace(/\r\n/g, "\n").split("\n");
  const out: AcodeSnippet[] = [];
  let currentTrigger: string | null = null;
  let bodyLines: string[] = [];

  const flush = () => {
    if (currentTrigger !== null) {
      const cleaned = bodyLines
        .join("\n")
        .replace(/\n+$/, "");
      if (cleaned.length > 0) {
        out.push({
          trigger: currentTrigger,
          body: cleaned,
          language,
        });
      }
    }
    currentTrigger = null;
    bodyLines = [];
  };

  for (const line of lines) {
    if (line.startsWith("snippet ")) {
      flush();
      const trigger = line.slice("snippet ".length).trim();
      if (trigger) {
        currentTrigger = trigger;
      }
      continue;
    }

    if (currentTrigger !== null) {
      if (line.startsWith("\t")) {
        bodyLines.push(line.slice(1));
      } else if (line.trim() === "") {
        bodyLines.push("");
      } else if (!line.startsWith("#")) {
        // Non-indented non-comment line terminates current snippet block unless empty
        flush();
      }
    }
  }

  flush();
  return out;
}
