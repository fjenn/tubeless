// FF=<firefox binary> MOZ_REMOTE_ALLOW_SYSTEM_ACCESS=1 node site/test/bookmark-probe-firefox.mjs <url...> (BM=<other bookmark body>)
// node probe-firefox.mjs <url...>: throwaway headless Firefox over Marionette; the probe bookmarklet is opened the way a
// bookmark click opens it (browser chrome: openTrustedLinkIn(javascript:..., "current")), then window.__bm is printed.
import { spawn } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import net from "node:net";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const body = readFileSync(new URL(process.env.BM || "./bookmark-probe.js", import.meta.url), "utf8");
const bm = "javascript:" + encodeURIComponent(body);
const profile = mkdtempSync(join(tmpdir(), "bm-ff-"));
let port = 0; const want = 2900 + Math.floor(Math.random() * 500);
writeFileSync(join(profile, "user.js"), [`user_pref("marionette.port", ${want});`, `user_pref("media.autoplay.default", 0);`, `user_pref("media.autoplay.blocking_policy", 0);`,
  `user_pref("media.volume_scale", "0.0");`, `user_pref("datareporting.policy.dataSubmissionEnabled", false);`].join("\n"));
const proc = spawn(process.env.FF, ["--headless", "--marionette", "--profile", profile, "--no-remote", "--window-size", "1280,800"], { stdio: ["ignore", "pipe", "pipe"] });
proc.stderr.on("data", (d) => { const m = /Marionette\s+INFO\s+Listening on port (\d+)/.exec(d.toString()); if (m) port = +m[1]; });
proc.stdout.on("data", (d) => { const m = /Listening on port (\d+)/.exec(d.toString()); if (m) port = +m[1]; });
while (!port) await sleep(300);
let sock;
for (let i = 0; ; i++) { try { sock = await new Promise((ok, no) => { const c = net.connect(port, "127.0.0.1"); c.once("connect", () => ok(c)); c.once("error", no); }); break; } catch (e) { if (i > 60) throw e; await sleep(1000); } }
let buf = "", id = 0; const waiting = new Map();
sock.on("data", (d) => { buf += d; for (;;) { const c = buf.indexOf(":"); if (c < 0) break; const n = +buf.slice(0, c); if (Buffer.byteLength(buf.slice(c + 1)) < n) break;
  const raw = Buffer.from(buf.slice(c + 1)); const msg = raw.subarray(0, n).toString(); buf = raw.subarray(n).toString(); const m = JSON.parse(msg);
  if (Array.isArray(m) && waiting.has(m[1])) { waiting.get(m[1])(m); waiting.delete(m[1]); } } });
await sleep(500);
const cmd = (name, params = {}) => { const i = ++id; const s = JSON.stringify([0, i, name, params]); sock.write(Buffer.byteLength(s) + ":" + s);
  return new Promise((r) => waiting.set(i, r)).then((m) => { if (m[2]) throw new Error(name + ": " + JSON.stringify(m[2]).slice(0, 300)); return m[3]; }); };
console.log("port", port);
await cmd("WebDriver:NewSession", { capabilities: { alwaysMatch: { acceptInsecureCerts: true, pageLoadStrategy: "eager" } } });
const js = async (ctx, script) => { await cmd("Marionette:SetContext", { value: ctx }); return (await cmd("WebDriver:ExecuteScript", { script, args: [], sandbox: ctx === "content" ? null : undefined })).value; };
for (const url of process.argv.slice(2)) {
  console.log("==", url);
  await cmd("Marionette:SetContext", { value: "content" });
  await cmd("WebDriver:Navigate", { url }).catch((e) => console.log("  nav:", e.message));
  await sleep(4000);
  console.log("  at", await js("content", "return location.href"));
  await js("chrome", `window.openTrustedLinkIn(${JSON.stringify(bm)}, "current", { allowInheritPrincipal: true }); return 1`);
  for (let i = 0; i < 60; i++) { if (await js("content", "return !!(window.wrappedJSObject || window).__bm?.done").catch(() => false)) break; await sleep(500); }
  console.log(await js("content", "return JSON.stringify((window.wrappedJSObject || window).__bm || null, null, 1)"));
}
proc.kill();
process.exit(0);
