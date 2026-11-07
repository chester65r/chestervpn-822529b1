/// <reference types="vite/client" />

// Declares the app's public build-time variables so `import.meta.env.VITE_*` reads
// typecheck under this project's strict tsconfig (noPropertyAccessFromIndexSignature).
interface ImportMetaEnv {
  readonly VITE_SUPABASE_URL: string;
  readonly VITE_SUPABASE_PUBLISHABLE_KEY: string;
  readonly VITE_SUPABASE_PROJECT_ID: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
