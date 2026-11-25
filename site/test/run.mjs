// Drives the built website (site/dist) in a throwaway headless Chrome or Firefox (own temporary profile; the
// user's browsers, settings and YouTube session are never touched) and checks Instant mode end to end.
//   node site/test/run.mjs chrome|firefox <scenario...>    scenarios: home search watch seek shorts sponsor drift save
//   Tubeless on youtube.com (the bookmark): ythome ywatch yfx ydl yover; build the site with SITE_URL=http://127.0.0.1:<PORT>/tubeless-publik/
//   and pass the same PORT (the bookmark loads yt.js from there into www.youtube.com, as from GitHub Pages)
// env: BIN=<browser binary>, DIST=<site dir> (site/dist), SHOTS=<dir for screenshots>, DRIFT_S=<seconds> (600),
//      EXT=<unpacked Chrome extension dir> (Full-mode hand-over check, chrome only), PORT
import { spawn, execFileSync } from "node:child_process";
import { createServer } from "node:http";
import { mkdirSync, mkdtempSync, readFileSync, existsSync, writeFileSync, statSync, rmSync, readdirSync, cpSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve, dirname, extname } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const dist = resolve(process.env.DIST || join(here, "../dist"));
const shots = process.env.SHOTS || join(here, "../../eval/screens");
mkdirSync(shots, { recursive: true });
const [browser = "chrome", ...scenarios] = process.argv.slice(2);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const results = [];
const ok = (name, pass, detail) => { if (process.env.XMODE) name = name.replace(/^bookmark/, "extension"); results.push({ name, pass, detail }); console.log(`${pass ? "PASS" : "FAIL"} ${browser} ${name}: ${detail}`); };

// ------------------------------------------------------------------ a static server, as GitHub Pages would be
const TYPES = { ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".mjs": "text/javascript; charset=utf-8", ".wasm": "application/wasm",
  ".json": "application/json", ".webmanifest": "application/manifest+json", ".png": "image/png", ".ttf": "font/ttf", ".txt": "text/plain" };
const server = createServer((req, res) => {
  const p = decodeURIComponent(new URL(req.url, "http://x").pathname).replace(/^\/tubeless-publik/, "");
  let f = join(dist, p.endsWith("/") ? p + "index.html" : p);
  if (!f.startsWith(dist) || !existsSync(f) || statSync(f).isDirectory()) { res.writeHead(404); return res.end("not found"); }
  // GitHub Pages answers with ACAO *: the bookmark loads the app's modules and wasm from youtube.com
  res.writeHead(200, { "Content-Type": TYPES[extname(f)] || "application/octet-stream", "Cache-Control": "no-cache", "Access-Control-Allow-Origin": "*" });
  res.end(readFileSync(f));
});
await new Promise((r) => server.listen(+process.env.PORT || 0, "127.0.0.1", r));
const base = process.env.LIVE || `http://127.0.0.1:${server.address().port}/tubeless-publik/`;
console.log("serving", dist, "at", base);

// ------------------------------------------------------------------ browser drivers: open(url), eval(expr), shot(file), click(x,y), key(k), type(s), wheel(x,y,dy)
const profile = mkdtempSync(join(process.env.TMPDIR || tmpdir(), `tubeless-site-${browser}-`));
const dlDir = join(profile, "downloads");   // the browser's downloads folder (the save scenario)
mkdirSync(dlDir);
let drv, extId = null;
// XMODE=1 (with EXT=<extension/dist/chrome|firefox>): the y* scenarios run in the extension's own app page instead of the bookmark
const XMODE = !!process.env.XMODE;
const FF_UUID = "6b7d2c1e-0d6e-4f5a-9c1b-6a0c4b1e2f3a";
if (browser === "chrome") drv = await chrome();
else drv = await firefox();

async function chrome() {
  const bin = process.env.BIN || "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
  const proc = spawn(bin, ["--headless=new", `--user-data-dir=${profile}`, "--remote-debugging-pipe", "--enable-unsafe-extension-debugging",
    "--no-first-run", "--no-default-browser-check", "--window-size=1280,800", "--autoplay-policy=no-user-gesture-required", "--mute-audio",
    // the bookmark scenarios load the app from this test server (127.0.0.1) into youtube.com, as from GitHub Pages
    "--disable-features=LocalNetworkAccessChecks,PrivateNetworkAccessRespectPreflightResults",
    // UA=<string>: the browser's User-Agent (headless Chrome says "HeadlessChrome", which servers may treat differently)
    ...(process.env.UA ? [`--user-agent=${process.env.UA}`] : []), "about:blank"],
    { stdio: ["ignore", "ignore", "pipe", "pipe", "pipe"] });
  const [, , , to, from] = proc.stdio;
  let seq = 0, buf = "";
  const waiting = new Map(), net = [];
  from.on("data", (d) => {
    buf += d.toString();
    let i;
    while ((i = buf.indexOf("\0")) >= 0) {
      const m = JSON.parse(buf.slice(0, i)); buf = buf.slice(i + 1);
      if (m.id && waiting.has(m.id)) { waiting.get(m.id)(m); waiting.delete(m.id); }
      if (m.method === "Runtime.consoleAPICalled") { const t = m.params.args.map((a) => a.value ?? a.description).join(" "); if (/\[tubeless\]/.test(t) || process.env.VERBOSE) console.log("  console:", t.slice(0, 240)); }
      if (m.method === "Runtime.exceptionThrown") console.log("  exception:", JSON.stringify(m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text).slice(0, 300));
      if (m.method === "Network.requestWillBeSent") net.push({ url: m.params.request.url, method: m.params.request.method, headers: m.params.request.headers, type: m.params.type });
    }
  });
  const send = (method, params = {}, sessionId) => {
    const msg = { id: ++seq, method, params, ...(sessionId ? { sessionId } : {}) };
    to.write(JSON.stringify(msg) + "\0");
    return new Promise((r) => waiting.set(msg.id, r)).then((x) => { if (x.error) throw new Error(method + ": " + x.error.message); return x.result; });
  };
  if (process.env.EXT) { extId = (await send("Extensions.loadUnpacked", { path: resolve(process.env.EXT) })).id; console.log("extension:", extId); }
  await send("Browser.setDownloadBehavior", { behavior: "allow", downloadPath: dlDir });
  const { targetId } = await send("Target.createTarget", { url: "about:blank" });
  const { sessionId: s } = await send("Target.attachToTarget", { targetId, flatten: true });
  for (const d of ["Runtime", "Page", "Network"]) await send(d + ".enable", {}, s);
  await send("Emulation.setDeviceMetricsOverride", { width: 1280, height: 800, deviceScaleFactor: 1, mobile: false }, s);
  const mouse = async (type, x, y, extra = {}) => send("Input.dispatchMouseEvent", { type, x, y, button: "left", clickCount: 1, ...extra }, s);
  return {
    net,
    async open(url) { await send("Page.navigate", { url }, s); },
    // a bookmark click: a browser-initiated javascript: navigation (page CSP does not apply, as for a real bookmark)
    async bookmark(url) { await send("Page.navigate", { url }, s); },
    async eval(expr) { const r = await send("Runtime.evaluate", { expression: expr, returnByValue: true, awaitPromise: true, userGesture: true }, s); return r.result.value; },
    async shot(file) { const r = await send("Page.captureScreenshot", { format: "png" }, s); writeFileSync(file, Buffer.from(r.data, "base64")); },
    async click(x, y) { await mouse("mouseMoved", x, y); await mouse("mousePressed", x, y); await sleep(60); await mouse("mouseReleased", x, y); },
    async key(key, code = key, keyCode = 0) { for (const type of ["keyDown", "keyUp"]) await send("Input.dispatchKeyEvent", { type, key, code, windowsVirtualKeyCode: keyCode, nativeVirtualKeyCode: keyCode }, s); },
    async type(text) { await send("Input.insertText", { text }, s); },
    async wheel(x, y, dy) { await send("Input.dispatchMouseEvent", { type: "mouseWheel", x, y, deltaX: 0, deltaY: dy }, s); },
    async url() { return (await send("Target.getTargetInfo", { targetId })).targetInfo.url; },
    close() { proc.kill(); },
  };
}

async function firefox() {
  const bin = process.env.BIN;
  if (!bin) throw new Error("BIN=<firefox binary> needed");
  writeFileSync(join(profile, "user.js"), [
    `user_pref("media.autoplay.default", 0);`, `user_pref("media.autoplay.blocking_policy", 0);`,
    `user_pref("datareporting.policy.dataSubmissionEnabled", false);`, `user_pref("browser.shell.checkDefaultBrowser", false);`,
    `user_pref("media.volume_scale", "0.0");`,
    `user_pref("browser.download.dir", ${JSON.stringify(dlDir)});`, `user_pref("browser.download.folderList", 2);`,
    `user_pref("browser.download.useDownloadDir", true);`, `user_pref("browser.download.always_ask_before_handling_new_types", false);`,
    `user_pref("dom.disable_open_during_load", false);`, `user_pref("network.lna.enabled", false);`,
    // EXT: a fixed moz-extension:// host for the extension, its host permissions granted without a prompt
    `user_pref("extensions.webextensions.uuids", ${JSON.stringify(JSON.stringify({ "tubeless@tubeless.invalid": FF_UUID }))});`,
    `user_pref("extensions.originControls.grantByDefault", true);`,
  ].join("\n") + "\n");
  const proc = spawn(bin, ["--headless", "--profile", profile, "--remote-debugging-port", "0", "--no-remote", "--window-size", "1280,800"],
    { stdio: ["ignore", "pipe", "pipe"], env: { ...process.env, MOZ_REMOTE_ALLOW_SYSTEM_ACCESS: "1" } });
  const port = await new Promise((okp, no) => {
    const t = setTimeout(() => no(new Error("no WebDriver BiDi port")), 60000);
    proc.stderr.on("data", (d) => { const m = /WebDriver BiDi listening on ws:\/\/127\.0\.0\.1:(\d+)/.exec(d.toString()); if (m) { clearTimeout(t); okp(m[1]); } });
  });
  const ws = new WebSocket(`ws://127.0.0.1:${port}/session`);
  await new Promise((r) => ws.addEventListener("open", r, { once: true }));
  let seq = 0;
  const waiting = new Map(), net = [];
  ws.addEventListener("message", (e) => {
    const m = JSON.parse(e.data);
    if (m.id && waiting.has(m.id)) { waiting.get(m.id)(m); waiting.delete(m.id); }
    if (m.method === "log.entryAdded" && /\[tubeless\]/.test(m.params.text || "")) console.log("  console:", m.params.text.slice(0, 240));
    if (process.env.NETLOG && /googlevideo/.test(m.params?.request?.url || "") && m.method !== "network.beforeRequestSent")
      console.log("  gv:", m.method.split(".")[1], m.params.response?.status, m.params.errorText || "", (/itag=(\d+)/.exec(m.params.request.url) || [])[1], (m.params.request.headers || []).find((h) => /^range$/i.test(h.name))?.value?.value || "");
    if (m.method === "network.beforeRequestSent") net.push({ url: m.params.request.url, method: m.params.request.method, headers: Object.fromEntries((m.params.request.headers || []).map((h) => [h.name, h.value.value])) });
  });
  const send = (method, params = {}) => {
    const id = ++seq;
    ws.send(JSON.stringify({ id, method, params }));
    return new Promise((r) => waiting.set(id, r)).then((x) => { if (x.type === "error") throw new Error(method + ": " + x.message); return x.result; });
  };
  await send("session.new", { capabilities: {} });
  await send("session.subscribe", { events: ["log.entryAdded", "network.beforeRequestSent", "network.responseCompleted", "network.fetchError"] });
  if (process.env.EXT) {
    // a test copy whose app page a web page may open (WebDriver BiDi may not navigate to moz-extension:// itself)
    const ext = join(profile, "ext");
    cpSync(resolve(process.env.EXT), ext, { recursive: true });
    const mf = JSON.parse(readFileSync(join(ext, "manifest.json"), "utf8"));
    mf.web_accessible_resources = [...(mf.web_accessible_resources || []), { resources: ["app.html"], matches: ["<all_urls>"] }];
    writeFileSync(join(ext, "manifest.json"), JSON.stringify(mf));
    console.log("extension:", (await send("webExtension.install", { extensionData: { type: "path", path: ext } })).extension);
  }
  const ctx = (await send("browsingContext.create", { type: "tab" })).context;
  await send("browsingContext.setViewport", { context: ctx, viewport: { width: 1280, height: 800 } });
  const pointer = (actions) => send("input.performActions", { context: ctx, actions: [{ type: "pointer", id: "m", parameters: { pointerType: "mouse" }, actions }] });
  return {
    net,
    async open(url) { await send("browsingContext.navigate", { context: ctx, url, wait: "none" }); },
    // a bookmark click: the bookmark's script in the page (a real bookmark click is the browser's openTrustedLinkIn,
    // checked in docs/BUILD-STATUS.md BOOKMARKLET; it is exempt from the page's CSP just like this evaluation)
    async bookmark(url) { await send("script.evaluate", { expression: decodeURIComponent(url.slice("javascript:".length)), target: { context: ctx }, awaitPromise: false }); },
    async eval(expr) {
      const r = await send("script.evaluate", { expression: `(async () => (${expr}))()`, target: { context: ctx }, awaitPromise: true, resultOwnership: "none" });
      const v = r.result;
      if (r.type === "exception") return undefined;
      return v?.type === "string" || v?.type === "number" || v?.type === "boolean" ? v.value : v?.type === "null" ? null : v?.value;
    },
    async shot(file) { const r = await send("browsingContext.captureScreenshot", { context: ctx }); writeFileSync(file, Buffer.from(r.data, "base64")); },
    async click(x, y) { await pointer([{ type: "pointerMove", x: Math.round(x), y: Math.round(y) }, { type: "pointerDown", button: 0 }, { type: "pause", duration: 60 }, { type: "pointerUp", button: 0 }]); },
    async key(key) {
      const k = { ArrowDown: "", ArrowUp: "", Enter: "", Escape: "" }[key] || key;
      await send("input.performActions", { context: ctx, actions: [{ type: "key", id: "k", actions: [{ type: "keyDown", value: k }, { type: "keyUp", value: k }] }] });
    },
    async type(text) { await send("input.performActions", { context: ctx, actions: [{ type: "key", id: "k", actions: [...text].flatMap((c) => [{ type: "keyDown", value: c }, { type: "keyUp", value: c }]) }] }); },
    async wheel(x, y, dy) { await send("input.performActions", { context: ctx, actions: [{ type: "wheel", id: "w", actions: [{ type: "scroll", x: Math.round(x), y: Math.round(y), deltaX: 0, deltaY: dy }] }] }); },
    async url() { return (await send("browsingContext.getTree", { root: ctx })).contexts[0].url; },
    close() { try { ws.close(); } catch (e) { /* closed */ } proc.kill(); },
  };
}

// ------------------------------------------------------------------ helpers
async function waitFor(expr, secs, label) {
  for (let i = 0; i < secs * 4; i++) {
    const v = await drv.eval(expr).catch(() => undefined);
    if (v) return v;
    await sleep(250);
  }
  throw new Error("timed out: " + (label || expr));
}
const P = "window.tubeless && tubeless.player";
const state = () => drv.eval(`JSON.stringify((() => { const p = tubeless.player, v = p.video, a = p.audio;
  return { mode: p.mode, vt: v.currentTime, at: a.currentTime, vw: v.videoWidth, vh: v.videoHeight, vp: v.paused, ap: a.paused, vr: v.readyState, ar: a.readyState,
    vsrc: (v.currentSrc || '').slice(0, 60), want: p.want.playing, d: v.currentTime - a.currentTime, stats: p.stats, embed: tubeless.embed.on, broken: tubeless.health.broken } })())`).then((s) => JSON.parse(s || "{}"));
const shot = async (name) => { const f = join(shots, `instant-${browser}-${name}.png`); await drv.shot(f); console.log("  screenshot", f); };
const adHosts = () => drv.net.filter((r) => /doubleclick\.net|googleads|googlesyndication|\/pagead\/|\/api\/stats\/ads|adservice/.test(r.url)).map((r) => r.url.slice(0, 80));
async function appReady() {
  await waitFor(`!!document.querySelector('canvas') && !document.getElementById('tubeless-loading')`, 90, "app canvas");
  await sleep(1500);
}
async function playing(minT = 3, secs = 60) {
  return waitFor(`(() => { const p = tubeless.player; const m = p.mode === 'pair' || p.mode === 'audio' ? p.audio : p.video; return (p.mode !== 'none' && m.currentTime > ${minT} && !m.paused) || tubeless.embed.on })()`, secs, "playback");
}

// ------------------------------------------------------------------ scenarios
const VIDEO = process.env.VIDEO || "aqz-KE-bpKQ";  // Big Buck Bunny 60fps 4K (Blender, CC-BY)
// ------------------------------------------------------------------ Tubeless on youtube.com (the bookmark, site/bookmarklet.js)
// dist must be built with SITE_URL=http://127.0.0.1:<PORT>/tubeless-publik/ (and PORT set here) so the bookmark loads from this server
const bookmarklet = () => readFileSync(join(dist, "bookmarklet.txt"), "utf8").trim();
async function ytOpen(q = "") {
  if (XMODE) {
    const page = (browser === "chrome" ? `chrome-extension://${extId}` : `moz-extension://${FF_UUID}`) + "/app.html?lang=en" + q;
    if (browser === "chrome") await drv.open(page);
    else { await drv.open("https://example.com/"); await waitFor(`document.readyState === 'complete'`, 30, "example.com"); await drv.eval(`(location.href = ${JSON.stringify(page)}, 1)`); }
    await waitFor(`!!window.tubeless && !!document.querySelector('canvas') && !document.getElementById('tubeless-loading')`, 120, "Tubeless in the extension");
    await sleep(2500);
    return;
  }
  await drv.open("https://www.youtube.com/robots.txt?tubeless&lang=en" + q);
  await waitFor(`location.pathname === '/robots.txt' && document.readyState === 'complete'`, 40, "youtube.com/robots.txt");
  await drv.bookmark(bookmarklet());
  await waitFor(`!!window.tubeless && !!document.querySelector('canvas') && !document.getElementById('tubeless-loading')`, 120, "Tubeless in youtube.com");
  // no welcome page on the web (web/Main.kt marks it seen): no click here, it would open whatever Home shows there
  await sleep(2500);
}
const yv = () => drv.eval(`JSON.stringify((() => { const v = tubeless.player.video; return { t: v.currentTime, p: v.paused, w: v.videoWidth, h: v.videoHeight, rs: v.readyState } })())`).then((x) => JSON.parse(x || "{}"));
async function ytPlaying(minT = 3, secs = 90) { return waitFor(`(() => { const v = tubeless.player.video; return v.currentTime > ${minT} && !v.paused })()`, secs, "playback in youtube.com"); }

const S = {
  async home() {
    await drv.open(base + "?lang=en");
    await appReady();
    const bridge = await waitFor(`window.tubeless && tubeless.bridge.origin`, 20, "bridge");
    ok("bridge origin", bridge === "null", `sandboxed frame origin = ${bridge}`);
    // Home has loaded when YouTube's /browse answered through the bridge (a brand-new visitor may get YouTube's
    // own empty "Try searching to get started" Home, as on youtube.com)
    await waitFor(`tubeless.health.calls.browse === 200`, 60, "browse through the bridge");
    await waitFor(`performance.getEntriesByType('resource').filter(e => e.name.includes('i.ytimg.com/vi')).length > 0`, 60, "home thumbnails").catch(() => {});
    await sleep(5000);
    await shot("home");
    const calls = await drv.eval(`JSON.stringify(tubeless.health.calls)`);
    const thumbs = await drv.eval(`performance.getEntriesByType('resource').filter(e => e.name.includes('i.ytimg.com/vi')).length`);
    // signed out, Home is never empty: YouTube's own Home, or popular videos from its destination channels when that is empty
    ok("home (signed out)", !(await drv.eval(`tubeless.health.broken`)) && thumbs > 0, `InnerTube answers ${calls}; ${thumbs} thumbnails`);
  },
  async search() {
    await drv.open(base + "?lang=en&q=" + encodeURIComponent("big buck bunny"));
    await appReady();
    // a search page shows channels between the videos: two video thumbnails are enough to know it filled
    await waitFor(`performance.getEntriesByType('resource').filter(e => e.name.includes('i.ytimg.com/vi')).length >= 2`, 60, "search thumbnails");
    await sleep(2500);
    await shot("search");
    const searched = (await drv.eval(`tubeless.health.calls.search`)) === 200;
    ok("search", searched, "InnerTube /search through the bridge, results shown (screenshot)");
  },
  async watch() {
    drv.net.length = 0;
    await drv.open(base + `?lang=en&v=${VIDEO}`);
    await appReady();
    await playing(4, 60);
    await sleep(4000);
    let st = await state();
    if (st.mode === "pair" && st.vh < 1080) {
      // the quality menu's choice, as Kotlin makes it: 1080p
      await drv.eval(`tubeless.player.setHeight(1080)`); await sleep(6000); st = await state();
    }
    await shot("watch");
    ok("open video", st.mode === "pair" || st.mode === "single", `mode ${st.mode}, ${st.vw}x${st.vh}, video ${st.vt.toFixed(1)} s, audio ${st.at.toFixed(1)} s, drift ${(st.d * 1000).toFixed(0)} ms`);
    ok("1080p", st.vh >= 1080 || st.mode === "single", `${st.vw}x${st.vh} from ${st.vsrc.split("?")[0]}`);
    const ads = adHosts();
    ok("ad-free", ads.length === 0 && !st.embed, `ad requests: ${ads.length}; YouTube's embed used: ${st.embed}`);
    const pings = drv.net.filter((r) => /\/api\/stats\/(playback|watchtime)/.test(r.url));
    ok("watch-time pings", pings.length > 0, `${pings.length} pings sent (no-cors)`);
  },
  async seek() {
    const before = await state();
    await drv.eval(`tubeless.player.seek(300)`);
    await sleep(800);
    await waitFor(`(() => { const p = tubeless.player; return p.audio.currentTime > 300 && !p.audio.paused && p.video.readyState >= 3 })()`, 30, "after seek");
    await sleep(3000);
    const st = await state();
    await shot("seek");
    ok("seek", st.at > 302 && Math.abs(st.d) < 0.1, `from ${before.at.toFixed(1)} s to 300 s: now audio ${st.at.toFixed(2)} s, video ${st.vt.toFixed(2)} s, drift ${(st.d * 1000).toFixed(0)} ms`);
    // back and forth through the Compose controls' keys (←/→ are 5 s on YouTube)
    await drv.eval(`tubeless.player.seek(60)`); await sleep(2500);
    const st2 = await state();
    ok("seek back", st2.at > 59 && st2.at < 70 && Math.abs(st2.d) < 0.15, `audio ${st2.at.toFixed(2)} s, drift ${(st2.d * 1000).toFixed(0)} ms`);
  },
  async drift() {
    const secs = +(process.env.DRIFT_S || 600);
    const st0 = await state();
    if (st0.mode !== "pair") return ok("A/V drift", false, `not in pair mode (${st0.mode})`);
    // 1080p held (the quality menu's pick: no automatic step-down), so the run measures the pair at full size
    if (!process.env.DRIFT_AUTO) { await drv.eval(`(tubeless.player.setHeight(1080), 1)`); await sleep(5000); }
    await drv.eval(`Object.assign(tubeless.player.stats, { hardSeeks: 0, nudges: 0, samples: 0, sumAbs: 0, maxAbs: 0, stalls: 0 })`);
    const samples = [];
    const t0 = Date.now();
    while (Date.now() - t0 < secs * 1000) {
      await sleep(1000);
      const st = await state();
      if (!st.vp && !st.ap && st.vr >= 3) samples.push(st.d);
      if (st.ap && !st.at) break;
      if ((Date.now() - t0) % 60000 < 1000) console.log(`  drift t=${Math.round((Date.now() - t0) / 1000)}s audio=${st.at.toFixed(1)} d=${(st.d * 1000).toFixed(0)}ms ${st.vw}x${st.vh} hardSeeks=${st.stats.hardSeeks} stalls=${st.stats.stalls}`);
    }
    const st = await state();
    const abs = samples.map(Math.abs).sort((x, y) => x - y);
    const p = (q) => abs[Math.min(abs.length - 1, Math.floor(q * abs.length))] * 1000;
    const mean = abs.reduce((x, y) => x + y, 0) / Math.max(1, abs.length) * 1000;
    const detail = `${secs} s run, ${samples.length} samples: mean |d| ${mean.toFixed(1)} ms, p95 ${p(0.95).toFixed(0)} ms, max ${p(1).toFixed(0)} ms; ${st.stats.hardSeeks} hard resyncs, ${st.stats.stalls} stalls, ${st.vw}x${st.vh}`;
    writeFileSync(join(shots, `instant-${browser}-drift.json`), JSON.stringify({ secs, samples, stats: st.stats }, null, 0));
    await shot("drift");
    ok("A/V drift", samples.length > secs * 0.8 && p(0.95) <= 80, detail);
  },
  async sponsor() {
    // -5rtUVyxH5w: a sponsor segment at 92.8–107.4 s (SponsorBlock); the app skips it on its own
    drv.net.length = 0;
    await drv.open(base + `?lang=en&v=-5rtUVyxH5w`);
    await appReady();
    await playing(2, 60);
    await sleep(2000);
    // Chrome: straight to 86 s. Firefox: played from the start (a far seek in a long video is slow there: it reads
    // the throttled stream up to that point)
    if (browser === "chrome") await drv.eval(`tubeless.player.seek(86)`);
    await sleep(1500);
    const sb = drv.net.some((r) => /sponsor\.ajay\.app|sponsorblock/.test(r.url));
    let jumped = null;
    for (let i = 0; i < (browser === "chrome" ? 40 : 420); i++) {
      await sleep(500);
      const st = await state();
      if (i % 20 === 0) console.log(`  t=${st.at.toFixed(1)} paused=${st.ap} mode=${st.mode} rs=${st.vr}/${st.ar} want=${st.want} seeks=${st.stats.seeks} ct=${st.at}`);
      if (st.at > 106) { jumped = st.at; break; }
    }
    await shot("sponsor");
    ok("SponsorBlock skip", jumped !== null, sb ? `segment 92.8–107.4 s skipped: at ${jumped?.toFixed(1)} s` : "no SponsorBlock request seen");
  },
  async shorts() {
    await drv.open(base + "?lang=en&go=shorts");
    await appReady();
    await playing(1, 60);
    await sleep(2500);
    const first = await drv.eval(`tubeless.player.audio.currentSrc || tubeless.player.video.currentSrc`);
    await shot("shorts-1");
    // the "Next" button beside the Short (a wheel notch or ↓ does the same)
    await drv.click(1232, 460);
    await sleep(500);
    await playing(0.5, 30).catch(() => {});
    await sleep(3000);
    const second = await drv.eval(`tubeless.player.audio.currentSrc || tubeless.player.video.currentSrc`);
    await shot("shorts-2");
    const id = (u) => (/[?&]id=([^&]+)/.exec(u || "") || [])[1];
    ok("Shorts swipe", !!first && !!second && id(first) !== id(second), `first ${id(first)?.slice(0, 10)}, after ↓ ${id(second)?.slice(0, 10)}`);
  },
  async debug() {
    await drv.open(base + "?lang=en" + (process.env.Q || ""));
    await sleep(+(process.env.WAIT || 20) * 1000);
    await shot("debug");
    console.log(drv.net.filter((r) => !/127\.0\.0\.1/.test(r.url)).map((r) => r.method + " " + r.url.slice(0, 110)).join("\n"));
    console.log("calls", await drv.eval(`JSON.stringify(tubeless.health.calls)`));
    console.log(await drv.eval(`JSON.stringify(performance.getEntriesByType('resource').filter(e => !/127/.test(e.name)).map(e => e.initiatorType + ' ' + e.name.slice(0, 80)))`));
  },
  async look() {
    await drv.open(base + `?lang=en&v=${VIDEO}` + (process.env.Q || ""));
    await appReady();
    await playing(3, 60);
    await sleep(5000);
    await shot("look-1");
    await drv.eval(`(document.querySelector('canvas').style.opacity = '0', 1)`);
    await sleep(300);
    await shot("look-nocanvas");
    await drv.eval(`(document.querySelector('canvas').style.opacity = '1', 1)`);
    await drv.click(400, 300); await sleep(400);
    await shot("look-click");
  },
  async probe() {
    await drv.open(base + `?lang=en&v=${process.env.VIDEO || "-5rtUVyxH5w"}`);
    await appReady();
    await playing(2, 60);
    await drv.eval(`(tubeless.player.pause(), 1)`);
    const r = await drv.eval(`(async () => { const out = []; for (const f of tubeless.player.formats.audio) {
      const a = new Audio(); a.preload = 'auto'; a.src = f.url; await new Promise(r => a.onloadedmetadata = r); a.currentTime = ${process.env.AT || 86};
      await new Promise(r => setTimeout(r, 8000)); out.push(f.mime + ' ' + f.codecs + ' rs=' + a.readyState + ' t=' + a.currentTime + ' seeking=' + a.seeking + ' dur=' + a.duration + ' buf=' + (a.buffered.length ? a.buffered.end(a.buffered.length-1) : 0)); a.src = ''; } return JSON.stringify(out) })()`);
    console.log(r);
  },
  async probe2() {
    await drv.open(base + `?lang=en&v=-5rtUVyxH5w`);
    await appReady();
    await playing(2, 60);
    await drv.eval(`(tubeless.player.stop(), 1)`);
    const r = await drv.eval(`(async () => { const out = []; const f = tubeless.player.formats.audio.find(x => x.mime === 'audio/mp4');
      for (const variant of ['fresh-played', 'dom-played']) {
        const a = variant === 'fresh-played' ? new Audio() : tubeless.player.audio; a.muted = true; a.src = f.url;
        await new Promise(r => a.addEventListener('loadedmetadata', r, { once: true })); await a.play().catch(e => out.push('play ' + e.name)); await new Promise(r => setTimeout(r, 3000));
        a.currentTime = 86; await new Promise(r => setTimeout(r, 10000));
        out.push(variant + ' rs=' + a.readyState + ' t=' + a.currentTime.toFixed(1) + ' seeking=' + a.seeking + ' paused=' + a.paused + ' buf=' + Array.from({length: a.buffered.length}, (_, i) => a.buffered.start(i).toFixed(0) + '-' + a.buffered.end(i).toFixed(0)).join(','));
        a.pause(); }
      return JSON.stringify(out) })()`);
    console.log(r);
  },
  async full() {
    // Full mode: with the extension installed, the site hands over to the extension's app page
    await drv.open(base + "?lang=en");
    await sleep(4000);
    const url = await drv.url();
    ok("Full-mode hand-over", /^chrome-extension:\/\/.*\/app\.html\?lang=en/.test(url), `tab now at ${url.slice(0, 70)}`);
  },
  // Instant mode's Download (docs/BUILD-STATUS.md WEB-DL): the 360p MP4 lands in the browser's downloads folder
  // with picture and sound (ffprobe); a gir=yes video (no attachment header) opens in a new tab instead
  async save() {
    await drv.open(base + `?lang=en&v=${VIDEO}`);
    await appReady();
    await playing(2, 60);
    let how;
    if (process.env.SAVE_BTN) {
      // through the app: the Download chip under the video (1280x800 layout), then the dialog's "Save 360p"
      await drv.click(780, 603); await sleep(3000); await shot("save-dialog");
      const [x, y] = process.env.SAVE_BTN.split(",").map(Number);
      await drv.click(x, y); await sleep(2500); await shot("save-done");
      how = "saved";
    } else {
      await drv.eval(`tubeless.save.prepare('${VIDEO}')`);
      await sleep(3000);
      how = await drv.eval(`tubeless.save.go('${VIDEO}', 'Tubeless save – test').catch((e) => 'error:' + e.message)`);
    }
    let file = null;
    for (let i = 0; i < 240 && !file; i++) {
      await sleep(1000);
      const done = readdirSync(dlDir).filter((f) => f.endsWith(".mp4"));
      if (done.length && !readdirSync(dlDir).some((f) => /\.(crdownload|part)$/.test(f))) file = join(dlDir, done[0]);
    }
    let probe = "";
    if (file) try { probe = execFileSync("ffprobe", ["-v", "error", "-show_entries", "stream=codec_type,codec_name,width,height:format=duration", "-of", "compact", file]).toString().trim().replace(/\n/g, " | "); } catch (e) { probe = "ffprobe: " + e.message; }
    if (file && process.env.KEEP_SAVED) cpSync(file, join(process.env.KEEP_SAVED, `${browser}-${VIDEO}.mp4`));
    ok("save 360p", how === "saved" && /codec_type=video/.test(probe) && /codec_type=audio/.test(probe),
      `${how}; ${file ? file.split("/").pop() + " " + statSync(file).size + " bytes" : "no file"}; ${probe}`);
    const gir = process.env.GIR_VIDEO || "-5rtUVyxH5w";
    await drv.eval(`tubeless.save.prepare('${gir}')`);
    await sleep(3000);
    const how2 = await drv.eval(`tubeless.save.go('${gir}', 'x').catch((e) => 'error:' + e.message)`);
    ok("save (no attachment header)", how2 === "tab" || how2 === "saved", `${gir}: ${how2}`);
  },
  async ythome() {
    drv.net.length = 0;
    await ytOpen();
    await waitFor(`performance.getEntriesByType('resource').filter(e => e.name.includes('i.ytimg.com/vi')).length > 0`, 60, "home thumbnails").catch(() => {});
    await sleep(6000);
    await shot("yt-home");
    const homeThumbs = await drv.eval(`performance.getEntriesByType('resource').filter(e => e.name.includes('i.ytimg.com/vi')).length`);
    const env = JSON.parse(await drv.eval(`JSON.stringify({ origin: location.origin, cookies: document.cookie.split(';').filter(Boolean).length, sapisid: tubeless.auth.sapisid != null })`));
    const cred = await drv.eval(`fetch('https://www.youtube.com/youtubei/v1/browse?prettyPrint=false', { method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json', 'X-Origin': location.origin },
      body: JSON.stringify({ context: { client: { clientName: 'WEB', clientVersion: '2.20251120.01.00', hl: 'en' } }, browseId: 'FEwhat_to_watch' }) })
      .then(async (r) => { const t = await r.text(); return r.status + (t.includes('"logged_in","value":"1"') ? ' logged_in=1' : t.includes('"logged_in","value":"0"') ? ' logged_in=0' : ''); }).catch((e) => 'ERR ' + e)`);
    // a brand-new anonymous visitor gets YouTube's own empty Home ("Try searching to get started"), as on youtube.com: search fills the page
    await ytOpen("&q=" + encodeURIComponent("big buck bunny"));
    await waitFor(`performance.getEntriesByType('resource').filter(e => e.name.includes('i.ytimg.com/vi')).length >= 2`, 90, "search thumbnails");
    await sleep(3000);
    await shot("yt-search");
    const thumbs = await drv.eval(`performance.getEntriesByType('resource').filter(e => e.name.includes('i.ytimg.com/vi')).length`);
    ok("bookmark: Home + search in youtube.com", (XMODE || env.origin === "https://www.youtube.com") && homeThumbs > 0 && thumbs >= 2 && /^200/.test(cred),
      `origin ${env.origin}; Home: ${homeThumbs} thumbnails (an empty YouTube Home shows popular videos); search: ${thumbs} thumbnails; document.cookie readable (${env.cookies} cookies, values not printed); SAPISID ${env.sapisid ? "present" : "absent (signed out)"}; credentialed /browse ${cred}`);
  },
  async ywatch() {
    drv.net.length = 0;
    await ytOpen(`&v=${VIDEO}`);
    await ytPlaying(4, 120);
    const heights = JSON.parse(await drv.eval(`tubeless.player.tracks()`)).heights;
    const top = heights.find((h) => h >= 1080 && h <= 2160) || heights[0];
    await drv.eval(`(tubeless.player.setHeight(${top}), 1)`);
    let st;
    for (let i = 0; i < 40; i++) { await sleep(1000); st = await yv(); if (st.h >= top && !st.p) break; }
    await sleep(3000); st = await yv();
    await shot("yt-watch");
    const gv = drv.net.filter((r) => /googlevideo\.com/.test(r.url));
    const sabr = await drv.eval(`!!document.getElementById('tubeless-sabr')`);
    ok("bookmark: play", st.t > 4 && !st.p, `video at ${st.t.toFixed(1)} s, playing; ${gv.length} googlevideo requests (read by the page: Shaka/MSE${sabr ? ", SABR frame up" : ""})`);
    ok("bookmark: 1080p+", st.h >= 1080, `${st.w}x${st.h} (offered: ${heights.join("/")})`);
    const ads = adHosts();
    ok("bookmark: ad-free", ads.length === 0, `ad requests: ${ads.length}`);
  },
  // stable volume + voice boost (Web Audio on the MSE video): the speech band against the low band, effect off vs on
  async yfx() {
    if (!(await drv.eval(`!!(window.tubeless && tubeless.player.video.currentTime > 1)`))) { await ytOpen(`&v=${VIDEO}`); await ytPlaying(4, 120); }
    // the effect itself: the speech band (2.5–3.8 kHz) against the low band (60–200 Hz), after the effects minus before
    // them (the same sound at the same moment, so the music doesn't change the answer)
    const measure = `(async () => { const n = tubeless.player.fxState.n; if (!n) return null;
      const one = (an) => { an.fftSize = 4096; const f = new Float32Array(an.frequencyBinCount), hz = an.context.sampleRate / an.fftSize;
        an.getFloatFrequencyData(f); const band = (a, b) => { let s = 0, c = 0; for (let i = Math.floor(a / hz); i <= b / hz; i++) { s += Math.pow(10, f[i] / 10); c++; } return s / c; };
        const td = new Float32Array(an.fftSize); an.getFloatTimeDomainData(td); return [band(60, 200), band(2500, 3800), Math.sqrt(td.reduce((a, x) => a + x * x, 0) / td.length)]; };
      let pl = 0, pm = 0, ql = 0, qm = 0, rms = 0;
      for (let k = 0; k < 30; k++) { await new Promise((r) => setTimeout(r, 100)); const a = one(n.pre), b = one(n.out); pl += a[0]; pm += a[1]; ql += b[0]; qm += b[1]; rms += b[2]; }
      return JSON.stringify({ lift: 10 * Math.log10(qm / ql) - 10 * Math.log10(pm / pl), rms: rms / 30 }) })()`;
    await drv.eval(`(tubeless.player.fx(false, false), 1)`); await sleep(800);
    const off = JSON.parse((await drv.eval(measure)) || "null");
    await drv.eval(`(tubeless.player.fx(false, true), 1)`); await sleep(800);
    const on = JSON.parse((await drv.eval(measure)) || "null");
    await drv.eval(`(tubeless.player.fx(true, true), 1)`); await sleep(800);
    const both = JSON.parse((await drv.eval(measure)) || "null");
    const ctx = await drv.eval(`tubeless.player.fxState.ctx && tubeless.player.fxState.ctx.state`);
    const st = await yv();
    ok("bookmark: voice boost audible", !!on && on.rms > 0.001 && on.lift - off.lift >= 6 && !st.p,
      on && off ? `AudioContext ${ctx}, video playing at ${st.t.toFixed(0)} s; speech band vs low band after the effects: off ${off.lift.toFixed(1)} dB, voice boost +${on.lift.toFixed(1)} dB, + stable volume +${both.lift.toFixed(1)} dB; output RMS ${off.rms.toFixed(3)} / ${on.rms.toFixed(3)} / ${both.rms.toFixed(3)}` : `no Web Audio graph (AudioContext ${ctx})`);
  },
  // HD download through the app's own Download (Full mode: googlevideo → OPFS, joined by Mediabunny, handed to the
  // browser's downloads); DL_CLICKS="x,y;x,y": the Download chip, then the quality and the dialog's buttons (1280x800)
  async ydl() {
    await ytOpen(`&v=${VIDEO}`);
    await ytPlaying(2, 120);
    const clicks = (process.env.DL_CLICKS || "780,603;408,256;852,718").split(";").map((c) => c.split(",").map(Number));
    for (const [i, [x, y]] of clicks.entries()) { await drv.click(x, y); await sleep(2500); await shot(`yt-dl-${i}`); }
    // the app fetches picture then sound into OPFS (offline/<id>/video.*, audio.*); done when both stop growing
    const sizes = `(async () => { const out = {}; try { const d = await (await (await navigator.storage.getDirectory()).getDirectoryHandle('offline')).getDirectoryHandle('${VIDEO}');
      for await (const [n, h] of d.entries()) if (h.kind === 'file') out[n] = (await h.getFile()).size; } catch (e) {} return JSON.stringify(out) })()`;
    let last = "", same = 0, opfs = {};
    for (let i = 0; i < +(process.env.DL_WAIT || 600) / 5 && same < 4; i++) {
      await sleep(5000);
      const now = await drv.eval(sizes); opfs = JSON.parse(now || "{}");
      const has = Object.keys(opfs).some((n) => n.startsWith("audio.")) && Object.keys(opfs).some((n) => n.startsWith("video."));
      same = has && now === last ? same + 1 : 0; last = now;
      if (i % 6 === 0) console.log("  OPFS:", now, await drv.eval(`JSON.stringify(tubeless.dl.last || null)`));
    }
    // the Downloads page's "Save to device" (OfflineWeb.export): picture and sound joined into one file for the browser's downloads
    const vName = Object.keys(opfs).find((n) => n.startsWith("video.")), aName = Object.keys(opfs).find((n) => n.startsWith("audio."));
    if (vName && aName) console.log("  exported", await drv.eval(`tubeless.dl.exportJoined('offline/${VIDEO}/${vName}', 'offline/${VIDEO}/${aName}', 'Tubeless HD test').then(String).catch((e) => 'ERR ' + e.message)`), "bytes");
    let file = null;
    for (let i = 0; i < 120 && !file; i++) {
      await sleep(1000);
      const done = readdirSync(dlDir).filter((f) => /\.(mp4|webm)$/.test(f));
      if (done.length && !readdirSync(dlDir).some((f) => /\.(crdownload|part)$/.test(f))) file = join(dlDir, done[0]);
    }
    await shot("yt-dl-end");
    let probe = "", plays = "";
    if (file) {
      try { probe = execFileSync("ffprobe", ["-v", "error", "-show_entries", "stream=codec_type,codec_name,width,height:format=duration", "-of", "compact", file]).toString().trim().replace(/\n/g, " | "); } catch (e) { probe = "ffprobe: " + e.message; }
      // the whole file decodes (picture and sound), as a player would play it
      try { execFileSync("ffmpeg", ["-v", "error", "-xerror", "-i", file, "-f", "null", "-"], { timeout: 600000 }); plays = "decodes cleanly end to end"; } catch (e) { plays = "decode error: " + String(e.stderr || e.message).slice(0, 200); }
      if (process.env.KEEP_SAVED) cpSync(file, join(process.env.KEEP_SAVED, `yt-${browser}-${VIDEO}${extname(file)}`));
    }
    const h = +((/height=(\d+)/.exec(probe) || [])[1] || 0);
    ok("bookmark: HD download", !!file && h >= 1080 && /codec_type=audio/.test(probe) && plays.startsWith("decodes"),
      file ? `${file.split("/").pop()} ${statSync(file).size} bytes; ${probe}; ${plays}` : "no file");
  },
  // the bookmark pressed on youtube.com's own Home: Tubeless opens in a robots.txt frame over the page (same origin)
  async yover() {
    await drv.open("https://www.youtube.com/");
    await waitFor(`document.readyState === 'complete' && location.hostname === 'www.youtube.com'`, 40, "youtube.com");
    await sleep(3000);
    await drv.bookmark(bookmarklet());
    await waitFor(`(() => { const f = document.getElementById('tubeless-over'); const w = f && f.contentWindow; return !!(w && w.tubeless && w.document.querySelector('canvas') && !w.document.getElementById('tubeless-loading')) })()`, 120, "Tubeless over YouTube");
    await sleep(6000);
    await shot("yt-over");
    const where = await drv.eval(`location.href + ' / frame ' + document.getElementById('tubeless-over').contentWindow.location.href`);
    const yt = await drv.eval(`document.querySelectorAll('ytd-app, video').length`);
    ok("bookmark on youtube.com Home", /frame https:\/\/www\.youtube\.com\/robots\.txt/.test(where) && yt === 0, `app composed in a frame over YouTube (${where}); YouTube's own app and video removed underneath (screenshot)`);
  },
  // Instant mode's Sign in: the prompt offers Tubeless on youtube.com, the extension and the app (screenshot)
  async prompt() {
    await drv.open(base + "?lang=en");
    await appReady();
    await sleep(3000);
    const [x, y] = (process.env.CLICK || "1200,28").split(",").map(Number);
    await drv.click(x, y); await sleep(2000);
    await shot("prompt");
    ok("prompt", true, "screenshot");
  },
  async fallback() {
    // the null-origin trick failing: the bridge answers 403 → notice + YouTube's embedded player
    await drv.open(base + `?lang=en&simulate=broken&v=${VIDEO}`);
    await appReady();
    await waitFor(`tubeless.embed.on`, 40, "embed");
    await sleep(6000);
    const notice = await drv.eval(`document.getElementById('tubeless-notice').innerText`);
    await shot("fallback");
    ok("fallback (trick broken)", !!notice, `embed player on; notice: "${(notice || "").split("\n")[0].slice(0, 80)}"`);
  },
};

let code = 0;
try {
  for (const name of scenarios.length ? scenarios : ["home", "search", "watch", "seek", "sponsor", "shorts"]) {
    try { await S[name](); } catch (e) { ok(name, false, e.message); await shot(name + "-error").catch(() => {}); }
  }
} finally {
  writeFileSync(join(shots, `instant-${browser}-results.json`), JSON.stringify(results, null, 1));
  code = results.every((r) => r.pass) ? 0 : 1;
  drv.close();
  server.close();
  await sleep(500);
  rmSync(profile, { recursive: true, force: true });
}
process.exit(code);
