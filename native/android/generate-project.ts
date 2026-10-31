// Creates a clean buildable Android project from exactly the source displayed in the app.
// Run: bun native/android/generate-project.ts /tmp/tunnel-android
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { resolve, join } from "node:path";
import { BLUEPRINT_FILES } from "../../src/lib/android-blueprint";

const destination = process.argv[2];
if (!destination) throw new Error("Provide a new output directory (for example /tmp/tunnel-android)");
const out = resolve(destination);
if (existsSync(out)) throw new Error("Destination must not exist; never overwrite a native project");
mkdirSync(out, { recursive: true });
function write(path: string, contents: string) {
  const full = join(out, path);
  mkdirSync(resolve(full, ".."), { recursive: true });
  writeFileSync(full, contents);
}
for (const file of BLUEPRINT_FILES) write(file.path, file.code);
write("settings.gradle.kts", `pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "TunnelConsole"
include(":app")
`);
write("build.gradle.kts", `plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
`);
write("gradle.properties", "android.useAndroidX=true\nkotlin.code.style=official\norg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8\n");
write(".gitignore", ".gradle/\n**/build/\nlocal.properties\n*.jks\n*.keystore\n*.enrollment.conf\n");
console.log("Generated Android project; run native/android/run-checks.sh with this directory.");