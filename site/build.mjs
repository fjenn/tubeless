// Builds the website (Instant mode + the hand-over to the extension) into site/dist: plain static files for any
// static host (GitHub Pages, Cloudflare Pages, a USB stick behind `python3 -m http.server`).
//   - site/*: the page, instant.js (window.tubeless without an extension), the PWA manifest and icons
//   - the same Kotlin/Wasm app as the extension: ./gradlew :web:compileProductionExecutableKotlinWasmJsOptimize
//     (KIND=development: :web:compileDevelopmentExecutableKotlinWasmJs, bigger and unoptimized)
//   - skiko (Skia for the web) from the Gradle cache, Roboto + Pretendard, js-joda
// No bundler and no npm install. Usage: node site/build.mjs
import { cpSync, mkdirSync, readFileSync, rmSync, writeFileSync, readdirSync, existsSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { dirname, join, resolve } from "node:path";
import { homedir } from "node:os";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "..");
const kind = process.env.KIND || "production";
const wasmDir = kind === "production"
  ? join(root, "web/build/compileSync/wasmJs/main/productionExecutable/optimized")
  : join(root, "web/build/compileSync/wasmJs/main/developmentExecutable/kotlin");
if (!existsSync(join(wasmDir, "tubeless.wasm"))) throw new Error("no wasm build at " + wasmDir + ": build :web first");
const version = readFileSync(join(root, "gradle.properties"), "utf8").match(/app\.version=([\d.]+)/)[1];

function skikoJar() {
  const base = join(homedir(), ".gradle/caches/modules-2/files-2.1/org.jetbrains.skiko/skiko-js-wasm-runtime");
  const jars = [];
  for (const v of readdirSync(base)) for (const h of readdirSync(join(base, v))) for (const f of readdirSync(join(base, v, h))) if (f.endsWith(".jar")) jars.push(join(base, v, h, f));
  if (!jars.length) throw new Error("skiko-js-wasm-runtime is not in the Gradle cache: build :web first");
  return jars.sort().pop();
}

const out = process.env.OUT ? resolve(process.env.OUT) : join(here, "dist");
rmSync(out, { recursive: true, force: true });
mkdirSync(join(out, "vendor"), { recursive: true });
for (const f of ["index.html", "instant.js", "app.js", "manifest.webmanifest", "icon-192.png", "icon-512.png"]) cpSync(join(here, f), join(out, f));
cpSync(join(root, "extension/src/icon-128.png"), join(out, "icon-128.png"));
cpSync(join(root, "extension/vendor/js-joda.mjs"), join(out, "vendor/js-joda.mjs"));
cpSync(join(root, "extension/vendor/LICENSES.txt"), join(out, "vendor/LICENSES.txt"));
cpSync(join(wasmDir, "tubeless.wasm"), join(out, "tubeless.wasm"));
cpSync(join(wasmDir, "tubeless.uninstantiated.mjs"), join(out, "tubeless.uninstantiated.mjs"));
writeFileSync(join(out, "tubeless.mjs"), readFileSync(join(wasmDir, "tubeless.mjs"), "utf8")
  .replace(/from '@js-joda\/core'/, "from './vendor/js-joda.mjs'").split("\n").filter((l) => !l.includes("custom-formatters")).join("\n"));
execFileSync("unzip", ["-q", "-o", skikoJar(), "skiko.mjs", "skiko.wasm", "-d", out]);
cpSync(join(root, "common/fonts/RobotoVariable.ttf"), join(out, "RobotoVariable.ttf"));
cpSync(join(root, "common/fonts/PretendardVariable.ttf"), join(out, "PretendardVariable.ttf"));
// the files change with each build, the page doesn't: a version query keeps caches honest
const html = readFileSync(join(out, "index.html"), "utf8").replace(/src="(instant|app)\.js"/g, `src="$1.js?v=${version}-${Date.now().toString(36)}"`);
writeFileSync(join(out, "index.html"), html);
writeFileSync(join(out, ".nojekyll"), "");
writeFileSync(join(out, "version.txt"), `${version}\n`);
console.log(`site: ${out} (Tubeless ${version}, ${kind})`);
