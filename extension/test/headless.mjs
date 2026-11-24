// Loads the extension into a throwaway headless Chrome (its own temporary profile: the user's
// Chrome, its settings and its YouTube session are not touched), opens player.html and waits
// until the <video> has played a few seconds. Chrome 137+ ignores --load-extension, so the
// extension goes in through CDP Extensions.loadUnpacked over --remote-debugging-pipe.
// node extension/test/headless.mjs [videoId] [chromePath]
// APP=1: opens the Compose app (app/app.html, run assemble.sh first) instead, logs its console and saves app.png
import { spawn } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ext = process.env.EXT || resolve(dirname(fileURLToPath(import.meta.url)), "../dist/chrome");
const id = process.argv[2] || "aqz-KE-bpKQ";  // may carry more query, e.g. "aqz-KE-bpKQ&c=ANDROID_VR"
const chrome = process.argv[3] || "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const profile = mkdtempSync(join(process.env.TMPDIR || tmpdir(), "tubeless-spike-"));
const proc = spawn(chrome, ["--headless=new", `--user-data-dir=${profile}`, "--remote-debugging-pipe", "--enable-unsafe-extension-debugging",
  "--no-first-run", "--window-size=1280,800", "--no-default-browser-check", "--autoplay-policy=no-user-gesture-required", "about:blank"],
  { stdio: ["ignore", "ignore", "pipe", "pipe", "pipe"] });
const [, , , toChrome, fromChrome] = proc.stdio;
let seq = 0, buf = "";
const waiting = new Map();
fromChrome.on("data", (d) => {
  buf += d.toString();
  let i;
  while ((i = buf.indexOf("\0")) >= 0) {
    const msg = JSON.parse(buf.slice(0, i)); buf = buf.slice(i + 1);
    if (msg.id && waiting.has(msg.id)) { waiting.get(msg.id)(msg); waiting.delete(msg.id); }
    if (msg.method === "Runtime.consoleAPICalled") console.log("  console:", msg.params.args.map((a) => a.value ?? a.description).join(" ").slice(0, 300));
    if (msg.method === "Network.requestWillBeSent" && /youtubei\/v1\/(browse|next|player|guide)/.test(msg.params.request.url))
      console.log("  net:", msg.params.request.method, msg.params.request.url.split("?")[0].split("/").pop(), "auth:", !!msg.params.request.headers.Authorization, msg.params.requestId);
    if (msg.method === "Network.requestWillBeSentExtraInfo" && msg.params.headers) {
      const c = msg.params.headers.cookie || msg.params.headers.Cookie;
      if (c !== undefined || msg.params.headers.authorization) console.log("  net-extra:", msg.params.requestId, "cookie:", c ? c.split(";").map((x) => x.trim().split("=")[0]).join(",") : "-", "origin:", msg.params.headers.origin);
    }
    if (msg.method === "Debugger.paused") {
      const d = msg.params.data || {};
      const f = msg.params.callFrames?.[0];
      console.log("  thrown:", (d.description || "").split("\n").slice(0, 3).join(" | ").slice(0, 400), "@", f?.functionName, f?.url?.split("/").pop());
      send("Debugger.resume", {}, msg.sessionId);
    }
    if (msg.method === "Runtime.exceptionThrown") console.log("  exception:", JSON.stringify(msg.params.exceptionDetails).slice(0, 600));
  }
});
function send(method, params = {}, sessionId) {
  const m = { id: ++seq, method, params, ...(sessionId ? { sessionId } : {}) };
  toChrome.write(JSON.stringify(m) + "\0");
  return new Promise((r) => waiting.set(m.id, r)).then((x) => { if (x.error) throw new Error(method + ": " + x.error.message); return x.result; });
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let code = 1;
try {
  const { id: extId } = await send("Extensions.loadUnpacked", { path: ext });
  console.log("extension loaded:", extId);
  const app = !!process.env.APP;
  const { targetId } = await send("Target.createTarget", { url: "about:blank" });
  const { sessionId } = await send("Target.attachToTarget", { targetId, flatten: true });
  await send("Runtime.enable", {}, sessionId);
  await send("Page.enable", {}, sessionId);
  // NET=1: logs the app's youtubei calls with whether they carried SAPISIDHASH and cookies
  if (process.env.NET) await send("Network.enable", {}, sessionId);
  // COOKIE=name=value: a made-up youtube.com cookie in this throwaway profile (never a real session)
  if (process.env.COOKIE) {
    const [name, value] = process.env.COOKIE.split("=");
    await send("Storage.setCookies", { cookies: [{ name, value, domain: ".youtube.com", path: "/", secure: true, sameSite: "None" }] });
  }
  // DEBUG=1: every exception thrown in the page (caught or not), its first lines and where
  if (process.env.DEBUG) { await send("Debugger.enable", {}, sessionId); await send("Debugger.setPauseOnExceptions", { state: "all" }, sessionId); }
  const t0 = Date.now();
  await send("Page.navigate", { url: `chrome-extension://${extId}/${app ? "app.html" : "probe.html"}?v=${id}` }, sessionId);
  if (app) {
    let st = {};
    for (let t = 0; t < 90; t++) {
      await sleep(1000);
      const e = await send("Runtime.evaluate", { expression: "JSON.stringify({k: window.__tubeless, t: (document.getElementById('tubeless-video')||{}).currentTime, w: (document.getElementById('tubeless-video')||{}).videoWidth, h: (document.getElementById('tubeless-video')||{}).videoHeight, mem: performance.memory && performance.memory.usedJSHeapSize})", returnByValue: true }, sessionId);
      st = JSON.parse(e.result.value);
      if (st.t > 5) break;
    }
    // CLICKS="x,y;x,y": clicks (CSS px) once it plays, a second apart, then a second screenshot state
    for (const c of (process.env.CLICKS || "").split(";").filter(Boolean)) {
      const [x, y] = c.split(",").map(Number);
      for (const type of ["mousePressed", "mouseReleased"]) await send("Input.dispatchMouseEvent", { type, x, y, button: "left", clickCount: 1 }, sessionId);
      await sleep(1500);
    }
    // TYPE="text": typed key by key (as a keyboard would), then Enter
    if (process.env.TYPE) {
      for (const ch of process.env.TYPE) {
        await send("Input.dispatchKeyEvent", { type: "keyDown", text: ch, key: ch, unmodifiedText: ch }, sessionId);
        await send("Input.dispatchKeyEvent", { type: "keyUp", key: ch }, sessionId);
      }
      for (const type of ["rawKeyDown", "keyUp"]) await send("Input.dispatchKeyEvent", { type, key: "Enter", code: "Enter", windowsVirtualKeyCode: 13, ...(type === "rawKeyDown" ? { text: "\r" } : {}) }, sessionId);
      await sleep(4000);
    }
    if (process.env.CLICKS) await sleep(3000);
    if (process.env.WAIT) await sleep(+process.env.WAIT);
    // DOWNLOADS=<dir>: files the page hands to the browser's downloads land there
    if (process.env.DOWNLOADS) await send("Browser.setDownloadBehavior", { behavior: "allow", downloadPath: process.env.DOWNLOADS, eventsEnabled: false });
    if (process.env.EVAL) console.log("eval:", JSON.stringify((await send("Runtime.evaluate", { expression: process.env.EVAL, awaitPromise: true, returnByValue: true }, sessionId)).result.value));
    console.log("app state after", Date.now() - t0, "ms:", JSON.stringify(st));
    const shot = await send("Page.captureScreenshot", { format: "png" }, sessionId);
    writeFileSync(process.env.SHOT || "app.png", Buffer.from(shot.data, "base64"));
    // RELOAD=1: opens the app again without test parameters (settings and library must have been kept)
    if (process.env.RELOAD) {
      await send("Page.navigate", { url: `chrome-extension://${extId}/app.html${process.env.RELOAD_QS || ""}` }, sessionId);
      await sleep(6000);
      const shot2 = await send("Page.captureScreenshot", { format: "png" }, sessionId);
      writeFileSync((process.env.SHOT || "app.png").replace(/\.png$/, "-reload.png"), Buffer.from(shot2.data, "base64"));
    }
    code = st.t > 5 ? 0 : 1;
    throw new Error(code ? "app: video did not play" : "app: done");
  }
  let r = {};
  for (let t = 0; t < 60; t++) {
    await sleep(1000);
    const e = await send("Runtime.evaluate", { expression: "JSON.stringify(window.__spike || {})", returnByValue: true }, sessionId);
    r = JSON.parse(e.result.value || "{}");
    if (r.error || (r.ok && r.currentTime > 5)) break;
  }
  for (const s of r.steps || []) console.log("  " + s);
  console.log(JSON.stringify({ ok: r.ok, client: r.client, itags: r.itags, currentTime: r.currentTime, error: r.error }));
  code = r.ok ? 0 : 1;
} catch (e) {
  console.log("FAILED", e.message);
} finally {
  proc.kill();
  await sleep(500);
  rmSync(profile, { recursive: true, force: true });
  process.exit(code);
}
