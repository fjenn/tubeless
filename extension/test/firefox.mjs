// Loads dist/firefox into a throwaway headless Firefox (its own temporary profile: the user's
// browsers, settings and YouTube session are not touched) through WebDriver BiDi
// (webExtension.install), opens the app or the probe page and waits for the <video> to play.
// node extension/test/firefox.mjs <firefox binary> [videoId[&more]]     APP=1: the Compose app, SHOT=<png>
import { spawn } from "node:child_process";
import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const built = process.env.EXT || resolve(dirname(fileURLToPath(import.meta.url)), "../dist/firefox");
// a test copy whose pages a web page may open (WebDriver BiDi may not navigate to moz-extension:// itself)
const ext = mkdtempSync(join(process.env.TMPDIR || tmpdir(), "tubeless-ff-ext-"));
cpSync(built, ext, { recursive: true });
const mf = JSON.parse(readFileSync(join(ext, "manifest.json"), "utf8"));
mf.web_accessible_resources = [...(mf.web_accessible_resources || []), { resources: ["app.html", "probe.html"], matches: ["<all_urls>"] }];
writeFileSync(join(ext, "manifest.json"), JSON.stringify(mf));
const firefox = process.argv[2];
const id = process.argv[3] || "aqz-KE-bpKQ";
const app = !!process.env.APP;
const UUID = "6b7d2c1e-0d6e-4f5a-9c1b-6a0c4b1e2f3a";
const profile = mkdtempSync(join(process.env.TMPDIR || tmpdir(), "tubeless-ff-"));
// a fixed moz-extension:// host for our id, and host permissions granted without a prompt
writeFileSync(join(profile, "user.js"), [
  `user_pref("extensions.webextensions.uuids", ${JSON.stringify(JSON.stringify({ "tubeless@tubeless.invalid": UUID }))});`,
  `user_pref("extensions.originControls.grantByDefault", true);`,
  `user_pref("media.autoplay.default", 0);`,
  `user_pref("media.autoplay.blocking_policy", 0);`,
  `user_pref("datareporting.policy.dataSubmissionEnabled", false);`,
  `user_pref("browser.shell.checkDefaultBrowser", false);`,
].join("\n") + "\n");
const proc = spawn(firefox, ["--headless", "--profile", profile, "--remote-debugging-port", "0", "--no-remote", "--window-size", "1280,800"], { stdio: ["ignore", "pipe", "pipe"], env: { ...process.env, MOZ_REMOTE_ALLOW_SYSTEM_ACCESS: "1" } });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const port = await new Promise((ok, no) => {
  const t = setTimeout(() => no(new Error("no WebDriver BiDi port")), 30000);
  proc.stderr.on("data", (d) => { const m = /WebDriver BiDi listening on ws:\/\/127\.0\.0\.1:(\d+)/.exec(d.toString()); if (m) { clearTimeout(t); ok(m[1]); } });
});
const ws = new WebSocket(`ws://127.0.0.1:${port}/session`);
await new Promise((ok) => ws.addEventListener("open", ok, { once: true }));
let seq = 0;
const waiting = new Map();
ws.addEventListener("message", (e) => {
  const m = JSON.parse(e.data);
  if (m.id && waiting.has(m.id)) { waiting.get(m.id)(m); waiting.delete(m.id); }
  if (m.method === "network.beforeRequestSent" && /youtubei\/v1\/(browse|next|player)/.test(m.params.request.url)) {
    const h = Object.fromEntries(m.params.request.headers.map((x) => [x.name.toLowerCase(), x.value.value]));
    console.log("  net:", m.params.request.url.split("?")[0].split("/").pop(), "auth:", !!h.authorization, "cookies:", (m.params.request.cookies || []).map((c) => c.name).join(",") || "-", "origin:", h.origin);
  }
  if (m.method === "log.entryAdded") console.log("  console:", String(m.params.text).slice(0, 300));
});
function send(method, params = {}) {
  const m = { id: ++seq, method, params };
  ws.send(JSON.stringify(m));
  return new Promise((r) => waiting.set(m.id, r)).then((x) => { if (x.type === "error") throw new Error(method + ": " + x.error + " " + x.message); return x.result; });
}
const evaluate = async (ctx, expression) => (await send("script.evaluate", { expression, target: { context: ctx }, awaitPromise: true })).result;
let code = 1;
try {
  await send("session.new", { capabilities: { alwaysMatch: { "moz:firefoxOptions": {}, unhandledPromptBehavior: { default: "ignore" } } } });
  await send("session.subscribe", { events: ["log.entryAdded", ...(process.env.NET ? ["network.beforeRequestSent"] : [])] });
  // COOKIE=name=value: a made-up youtube.com cookie in this throwaway profile (never a real session)
  if (process.env.COOKIE) {
    const [name, value] = process.env.COOKIE.split("=");
    await send("storage.setCookie", { cookie: { name, value: { type: "string", value }, domain: ".youtube.com", path: "/", secure: true, sameSite: "none" } });
  }
  const { extension } = await send("webExtension.install", { extensionData: { type: "path", path: ext } });
  console.log("extension installed:", extension);
  const { contexts } = await send("browsingContext.getTree", {});
  const ctx = contexts[0].context;
  const t0 = Date.now();
  const target = `moz-extension://${UUID}/${app ? "app.html" : "probe.html"}?v=${id}`;
  await send("browsingContext.navigate", { context: ctx, url: "https://example.com/", wait: "complete" });
  await evaluate(ctx, `location.href = ${JSON.stringify(target)}`);
  let st = {};
  for (let i = 0; i < 60; i++) {
    await sleep(1000);
    const r = await evaluate(ctx, app
      ? "JSON.stringify({ t: document.getElementById('tubeless-video')?.currentTime, w: document.getElementById('tubeless-video')?.videoWidth, h: document.getElementById('tubeless-video')?.videoHeight })"
      : "JSON.stringify(window.__spike || {})").catch((e) => ({ value: JSON.stringify({ evalError: e.message.slice(0, 200) }) }));
    if (i === 3 || i === 10) console.log("  at", (await evaluate(ctx, "location.href").catch((e) => ({ value: e.message.slice(0, 200) }))).value, JSON.stringify(st));
    st = JSON.parse(r.value || "{}");
    if (app ? st.t > 5 : (st.error || (st.ok && st.currentTime > 5))) break;
  }
  if (!app) for (const s of st.steps || []) console.log("  " + s);
  console.log(`state after ${Date.now() - t0} ms:`, JSON.stringify(app ? st : { ok: st.ok, client: st.client, itags: st.itags, currentTime: st.currentTime, error: st.error }));
  // CLICKS="x,y;x,y": clicks (CSS px) once it plays, then a screenshot a few seconds later
  for (const c of (process.env.CLICKS || "").split(";").filter(Boolean)) {
    const [x, y] = c.split(",").map(Number);
    await send("input.performActions", { context: ctx, actions: [{ type: "pointer", id: "mouse", actions: [
      { type: "pointerMove", x, y }, { type: "pointerDown", button: 0 }, { type: "pointerUp", button: 0 }] }] });
    await sleep(1500);
  }
  if (process.env.CLICKS) await sleep(3000);
  if (process.env.SHOT) {
    const { data } = await send("browsingContext.captureScreenshot", { context: ctx });
    writeFileSync(process.env.SHOT, Buffer.from(data, "base64"));
  }
  code = (app ? st.t > 5 : st.ok) ? 0 : 1;
} catch (e) {
  console.log("FAILED", e.message);
} finally {
  try { await send("session.end", {}); } catch (e) { /* closing */ }
  proc.kill();
  await sleep(1000);
  rmSync(profile, { recursive: true, force: true });
  rmSync(ext, { recursive: true, force: true });
  process.exit(code);
}
