// node probe-chrome.mjs <url...>: opens each url in a throwaway headless Chrome, "clicks" the probe bookmarklet
// (Page.navigate to its javascript: URL = a browser-initiated javascript: navigation, as a bookmark click), prints window.__bm.
import { spawn } from "node:child_process";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const body = readFileSync(new URL("./bookmark-probe.js", import.meta.url), "utf8");
const bm = "javascript:" + encodeURIComponent(body);
const profile = mkdtempSync(join(tmpdir(), "bm-chrome-"));
const proc = spawn(process.env.BIN || "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", ["--headless=new", `--user-data-dir=${profile}`, "--remote-debugging-pipe",
  "--no-first-run", "--window-size=1280,800", "--autoplay-policy=no-user-gesture-required", "--mute-audio", "about:blank"], { stdio: ["ignore", "ignore", "pipe", "pipe", "pipe"] });
const [, , , to, from] = proc.stdio;
let seq = 0, buf = ""; const waiting = new Map();
from.on("data", (d) => { buf += d; let i; while ((i = buf.indexOf("\0")) >= 0) { const m = JSON.parse(buf.slice(0, i)); buf = buf.slice(i + 1);
  if (m.id && waiting.has(m.id)) { waiting.get(m.id)(m); waiting.delete(m.id); }
  if (m.method === "Runtime.consoleAPICalled" && m.params.type === "error") console.log("  console.error:", m.params.args.map((a) => a.value ?? a.description).join(" ").slice(0, 200));
  if (m.method === "Log.entryAdded" && /Content Security|Trusted/i.test(m.params.entry.text)) console.log("  log:", m.params.entry.text.slice(0, 220)); } });
const send = (method, params = {}, sessionId) => { const msg = { id: ++seq, method, params, ...(sessionId ? { sessionId } : {}) }; to.write(JSON.stringify(msg) + "\0");
  return new Promise((r) => waiting.set(msg.id, r)).then((x) => { if (x.error) throw new Error(method + ": " + x.error.message); return x.result; }); };
const { targetId } = await send("Target.createTarget", { url: "about:blank" });
const { sessionId: s } = await send("Target.attachToTarget", { targetId, flatten: true });
for (const d of ["Runtime", "Page", "Log"]) await send(d + ".enable", {}, s);
const ev = async (e) => (await send("Runtime.evaluate", { expression: e, returnByValue: true, awaitPromise: true }, s)).result.value;
for (const url of process.argv.slice(2)) {
  console.log("==", url);
  await send("Page.navigate", { url }, s); await sleep(6000);
  console.log("  at", await ev("location.href"), "title:", await ev("document.title"));
  try { await send("Page.navigate", { url: bm }, s); } catch (e) { console.log("  navigate(js):", e.message); }
  for (let i = 0; i < 60 && !(await ev("window.__bm && window.__bm.done")); i++) await sleep(500);
  console.log(JSON.stringify(await ev("window.__bm || null"), null, 1));
}
proc.kill();
