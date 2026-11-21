import { createServerFn } from "@tanstack/react-start";
import { listSnippetLanguages, readSnippetLanguage } from "./acode-snippet-library.server";

export const listAcodeSnippetLanguages = createServerFn({ method: "GET" }).handler(async () =>
  listSnippetLanguages(),
);

export const readAcodeSnippetLanguage = createServerFn({ method: "GET" })
  .inputValidator((d: { language: string }) => d)
  .handler(async ({ data }) => readSnippetLanguage(data?.language ?? "kotlin"));
