// Builds the extension for Chrome (MV3) and Firefox (MV3, also Firefox for Android) into
// extension/dist/{chrome,firefox} and zips them, from:
//   - extension/src (the pages and tubeless.js), extension/vendor (Shaka, Mediabunny, js-joda)
//   - the Kotlin/Wasm app: ./gradlew :web:compileProductionExecutableKotlinWasmJsOptimize
//     (KIND=development: :web:compileDevelopmentExecutableKotlinWasmJs, bigger and unoptimized)
//   - skiko (Skia for the web) from the Gradle cache, Roboto + Pretendard (Korean fallback), the SABR page and yt-dlp's solver
// No bundler and no npm install: plain files. Usage: node extension/build.mjs
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
const version = readFileSync(join(root, "gradle.properties"), "utf8").match(/app\.version=([\d.]+)/)[1];

function skikoJar() {
  const base = join(homedir(), ".gradle/caches/modules-2/files-2.1/org.jetbrains.skiko/skiko-js-wasm-runtime");
  const jars = [];
  for (const v of readdirSync(base)) for (const h of readdirSync(join(base, v))) for (const f of readdirSync(join(base, v, h))) if (f.endsWith(".jar")) jars.push(join(base, v, h, f));
  if (!jars.length) throw new Error("skiko-js-wasm-runtime is not in the Gradle cache: build :web first");
  return jars.sort().pop();
}

// where the Tubeless website is served (site/); SITE_MATCHES="pattern,pattern" adds more (a test server, a custom domain)
const SITE_MATCHES = ["https://fjenn.github.io/tubeless/*", ...(process.env.SITE_MATCHES || "").split(",").filter(Boolean)];

const manifest = (browser) => ({
  manifest_version: 3,
  name: "Tubeless",
  version,
  description: "YouTube on your own terms: no ads, downloads, SponsorBlock, your own subscriptions.",
  icons: { 16: "icon-16.png", 32: "icon-32.png", 48: "icon-48.png", 128: "icon-128.png" },
  action: { default_title: "Tubeless", default_icon: { 16: "icon-16.png", 32: "icon-32.png", 48: "icon-48.png", 128: "icon-128.png" } },
  permissions: ["declarativeNetRequestWithHostAccess", "cookies", "unlimitedStorage"],
  host_permissions: ["https://*.youtube.com/*", "https://*.googlevideo.com/*", "https://*.ytimg.com/*", "https://*.ggpht.com/*",
    "https://*.googleusercontent.com/*", "https://youtubei.googleapis.com/*"],
  // the hidden SABR frame: our script in YouTube's page world, only on robots.txt?tubeless, only inside our page
  content_scripts: [{ matches: ["https://www.youtube.com/robots.txt?tubeless*"], js: ["sabr-page.js", "sabr-frame.js"], all_frames: true,
    run_at: "document_end", world: "MAIN" },
    // the Tubeless website: the page learns the extension is here and hands over to app.html (Full mode)
    { matches: SITE_MATCHES, js: ["site.js"], run_at: "document_start" }],
  content_security_policy: { extension_pages: "script-src 'self' 'wasm-unsafe-eval'; object-src 'self'" },
  ...(browser === "chrome"
    ? { background: { service_worker: "background.js" }, minimum_chrome_version: "120" }
    : { background: { scripts: ["background.js"] },
        browser_specific_settings: { gecko: { id: "tubeless@tubeless.invalid", strict_min_version: "128.0" }, gecko_android: { strict_min_version: "128.0" } } }),
});

for (const browser of ["chrome", "firefox"]) {
  const out = join(here, "dist", browser);
  rmSync(out, { recursive: true, force: true });
  mkdirSync(join(out, "vendor"), { recursive: true });
  cpSync(join(here, "src"), out, { recursive: true });
  rmSync(join(out, "manifest.json"), { force: true });
  cpSync(join(here, "vendor"), join(out, "vendor"), { recursive: true });
  // the Kotlin/Wasm app; its one bare import becomes a file, and no dev-tools formatters
  cpSync(join(wasmDir, "tubeless.wasm"), join(out, "tubeless.wasm"));
  cpSync(join(wasmDir, "tubeless.uninstantiated.mjs"), join(out, "tubeless.uninstantiated.mjs"));
  writeFileSync(join(out, "tubeless.mjs"), readFileSync(join(wasmDir, "tubeless.mjs"), "utf8")
    .replace(/from '@js-joda\/core'/, "from './vendor/js-joda.mjs'").split("\n").filter((l) => !l.includes("custom-formatters")).join("\n"));
  execFileSync("unzip", ["-q", "-o", skikoJar(), "skiko.mjs", "skiko.wasm", "-d", out]);
  cpSync(join(root, "common/fonts/RobotoVariable.ttf"), join(out, "RobotoVariable.ttf"));
  cpSync(join(root, "common/fonts/PretendardVariable.ttf"), join(out, "PretendardVariable.ttf"));
  cpSync(join(root, "common/sabr/sabr-page.js"), join(out, "sabr-page.js"));
  // yt-dlp's solver, lib then core, as one text for __thSabr.setSolver (as BrowserSabr does)
  writeFileSync(join(out, "yt-solver.txt"), ["yt.solver.lib.min.js", "yt.solver.core.min.js"].map((f) => readFileSync(join(root, "common/ejs", f), "utf8")).join("\n;var meriyah=lib.meriyah,astring=lib.astring;\n"));
  writeFileSync(join(out, "manifest.json"), JSON.stringify(manifest(browser), null, 2) + "\n");
  const zip = join(here, "dist", `tubeless-${version}-${browser}.zip`);
  rmSync(zip, { force: true });
  execFileSync("zip", ["-q", "-r", "-X", zip, "."], { cwd: out });
  console.log(`${browser}: ${out} (${zip})`);
}
if (!existsSync(wasmDir)) console.log("note: no wasm build at", wasmDir);
