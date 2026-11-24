// Tubeless desktop JS host (JsHost.kt): what common/sabr/sabr-page.js expects of a browser page on
// https://www.youtube.com, inside an in-process Node.js runtime (Javet) instead of a hidden Chrome.
//  - fetch: relative addresses resolve on www.youtube.com, with a browser's user agent, Origin and Referer
//  - window/self: the global object; Worker: yt-dlp's EJS solver in a function of its own
//  - __thMakeMinter: BotGuard under JSDOM (rustypipe-botguard's bundle, MIT), re-rolled until the
//    token lands in the class googlevideo accepts (short), as Spotless does (KI-1); more at ACCEPTED_MAX_IT
// The host supplies __thHost(json) and __thBin.postMessage(ArrayBuffer) before this runs, and __thUA.
(() => {
  const g = globalThis;
  const UA = g.__thUA || "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36";
  const BASE = "https://www.youtube.com/";
  // an error nobody caught must not end the app (in Node.js it ends the process)
  g.process.on("uncaughtException", (e) => { try { g.__thHost(JSON.stringify({ t: "log", msg: "uncaught: " + (e?.stack || e) })); } catch (x) { /* no host */ } });
  g.process.on("unhandledRejection", () => {});
  const log = (msg) => { try { g.__thHost(JSON.stringify({ t: "log", msg: String(msg) })); } catch (e) { /* no host */ } };
  g.__thLog = log;
  if (g.window === undefined) g.window = g;
  if (g.self === undefined) g.self = g;
  // a window is an EventTarget (BotGuard listens on it)
  if (typeof g.addEventListener !== "function") {
    const et = new EventTarget();
    for (const k of ["addEventListener", "removeEventListener", "dispatchEvent"]) g[k] = et[k].bind(et);
  }

  const nodeFetch = g.fetch;
  const ours = /(^|\.)(youtube\.com|googlevideo\.com|google\.com|ytimg\.com)$/;
  g.fetch = (u, o = {}) => {
    const url = new URL(typeof u === "string" || u instanceof URL ? String(u) : u.url, BASE);
    const h = new Headers(o.headers || {});
    if (!h.has("user-agent")) h.set("user-agent", UA);
    if (!h.has("accept-language")) h.set("accept-language", "en-US,en;q=0.9");
    if (ours.test(url.hostname)) {
      if (!h.has("origin") && (o.method || "GET") !== "GET") h.set("origin", "https://www.youtube.com");
      if (!h.has("referer")) h.set("referer", BASE);
      // past the EU consent page, as a browser that clicked "Reject all" once
      if (url.hostname === "www.youtube.com" && !h.has("cookie")) h.set("cookie", "SOCS=CAI");
    }
    const { credentials, mode, cache, ...rest } = o;
    const p = nodeFetch(url, { ...rest, headers: h });
    // a watch page carries a BotGuard challenge too: kept for the next bootstrap (one home page fetch less)
    if (url.hostname === "www.youtube.com" && url.pathname === "/watch") {
      p.then((r) => r.ok && r.clone().text().then((t) => { const m = t.match(AT_N); if (m) { pool.push(m[1]); if (pool.length > 4) pool.shift(); } })).catch(() => {});
    }
    return p;
  };
  const AT_N = /window\.ytAtN\(\s*({[\s\S]*?})\s*\)/;
  const pool = [];

  // ------------------------------------------------------------ Worker (the solver only)
  const blobs = new Map();
  let blobN = 0;
  const createObjectURL = URL.createObjectURL;
  URL.createObjectURL = (b) => { const id = "blob:tubeless/" + (++blobN); blobs.set(id, b); return id; };
  // The solver runs in this same context, inside a function of its own (Node's vm contexts may not build code
  // from text here, and the solver needs that); its globals stay inside that function.
  g.Worker = class {
    constructor(url) {
      const blob = blobs.get(url);
      blobs.delete(url);
      this.onmessage = null;
      this.queue = [];
      this.handler = null;
      this.dead = false;
      blob.text().then((src) => {
        const post = (d) => { if (!this.dead) setImmediate(() => this.onmessage && this.onmessage({ data: d })); };
        // "var onmessage" first: it keeps the solver's "use strict" from applying to the whole body
        this.handler = (new Function("postMessage", "var onmessage;\n" + src + "\n;return onmessage;"))(post);
        for (const d of this.queue.splice(0)) this.postMessage(d);
      }).catch((e) => log("worker: " + e));
    }
    postMessage(d) {
      if (this.dead) return;
      if (!this.handler) { this.queue.push(d); return; }
      const copy = structuredClone(d);
      setImmediate(() => { if (!this.dead) try { this.handler({ data: copy }); } catch (e) { log("worker: " + e); } });
    }
    terminate() { this.dead = true; this.handler = null; }
  };

  // ------------------------------------------------------------ Cipher (the TV client's locked links)
  // yt-dlp's solver in a context of its own, for Cipher.kt's JsSolver: load(solver, player), then solve(requests)
  let cipherCtx = null, cipherPre = null;
  g.__thCipher = {
    load(solverSrc, playerJs) {
      if (!cipherCtx) cipherCtx = { jsc: (new Function(solverSrc + "\n;return jsc;"))() };
      cipherPre = cipherCtx.jsc({ type: "player", player: playerJs, requests: [], output_preprocessed: true }).preprocessed_player;
      return cipherPre ? "ok" : "";
    },
    solve(requests) {
      if (!cipherPre) return "";
      return JSON.stringify(cipherCtx.jsc({ type: "preprocessed", preprocessed_player: cipherPre, requests: JSON.parse(requests) }));
    },
  };

  // ------------------------------------------------------------ calls from the app
  // __thAsk(id, "a.b.c", ...args): calls globalThis.a.b.c(...args) and answers __thReply(id, text, error)
  g.__thAsk = (id, path, ...args) => {
    Promise.resolve().then(() => {
      let self = g, f = g;
      for (const k of path.split(".")) { self = f; f = f[k]; }
      return f.apply(self, args);
    }).then((v) => g.__thReply(id, v == null ? "" : String(v), ""), (e) => g.__thReply(id, "", String(e?.message || e) || "error"));
  };

  // memory use in MB, for the logs (PlayProbe)
  g.__thMem = () => { const m = g.process.memoryUsage(); return [m.rss, m.heapUsed, m.heapTotal, m.external, m.arrayBuffers].map((x) => Math.round(x / 1048576)).join("/"); };

  // the app wakes this runtime's thread (waiting in the event loop) with a byte on this socket
  g.__thListenWake = () => {
    const net = g.process.getBuiltinModule("node:net");
    const srv = net.createServer((sock) => { sock.on("data", () => {}); sock.on("error", () => {}); });
    srv.listen(0, "127.0.0.1", () => g.__thWakePort(srv.address().port));
    // and once a minute in any case (the app's idle check)
    setInterval(() => {}, 60e3);
  };

  // ------------------------------------------------------------ BotGuard
  // The class googlevideo takes shows in a pot's length (Spotless KI-1): pot = identifier + integrity part + 14.
  // Measured here 2026-11-23 on SABR (WEB), 24 bootstraps each started 344 s into a video (past the free first
  // minute): an integrity part of 67-68 bytes streamed (9 of 9), 69-72 was refused at once (15 of 15). Fresh
  // challenges land in the short class about one time in three; re-using one challenge never did.
  const PROBE = "limusicprobe";
  const ACCEPTED_MAX_IT = 68;
  const b64len = (s) => { try { return Buffer.from(s.replace(/-/g, "+").replace(/_/g, "/"), "base64").length; } catch (e) { return -1; } };
  // what sabr-page.js reads from window (the global): JSDOM replaces window with its own, so these follow it
  const hostKeys = ["__thHost", "__thBin", "__thSabr", "__thMakeMinter", "__thLog", "yt"];

  const BG_HEADERS = { "content-type": "application/json+protobuf", "x-goog-api-key": "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw", "x-user-agent": "grpc-web-javascript/0.1" };
  const scripts = new Map();   // the interpreter's address -> its text (it rarely changes)
  async function interpreter(src) {
    const url = src.startsWith("//") ? "https:" + src : src;
    let t = scripts.get(url);
    if (!t) { t = await (await fetch(url)).text(); scripts.clear(); scripts.set(url, t); }
    return t;
  }
  // a fresh challenge from the home page, as YouTube's own pages get it. (Not from the attestation API,
  // api/jnn/v1/Create: its challenges' tokens look short but SABR refused every one, 6 of 6, 2026-11-23.)
  async function challenge() {
    let found = pool.pop();
    if (found) return parseChallenge(found);
    const r = await fetch("/", { redirect: "manual" });
    // google.com/sorry: too many pages asked from here lately; no more bootstraps for now
    if (r.status >= 300 && r.status < 400) throw new Error("YouTube asks to slow down (" + r.status + ")");
    const html = await r.text();
    // BotGuard's program reads the page's config (EVENT_ID), as on YouTube's pages
    const cfg = html.match(/ytcfg\.set\(({.+?})\);/s)?.[1];
    if (cfg) { try { g.yt = g.yt || {}; g.yt.config_ = Object.assign(g.yt.config_ || {}, JSON.parse(cfg)); } catch (e) { /* not needed */ } }
    const at = html.match(AT_N);
    if (!at) throw new Error("no BotGuard challenge on YouTube's home page");
    return parseChallenge(at[1]);
  }
  async function parseChallenge(text) {
    let R = (new Function("return (" + text + ")"))().R;
    if (typeof R === "string") R = JSON.parse(R);
    const ch = R.bgChallenge;
    return { program: ch.program, globalName: ch.globalName, interp: await interpreter(ch.interpreterUrl.privateDoNotAccessOrElseTrustedResourceUrlWrappedValue) };
  }

  async function bootOnce() {
    const ch = await challenge();
    const saved = Object.fromEntries(hostKeys.map((k) => [k, g[k]]));
    const resp = await g.runBotguard(ch.interp, ch.program, ch.globalName, UA);
    // JSDOM's window is the page's window now: the host's functions go with it
    const w = g.window;
    if (w !== g) for (const k of hostKeys) if (saved[k] !== undefined) try { w[k] = saved[k]; } catch (e) { /* read-only */ }
    const r = await fetch("https://www.youtube.com/api/jnn/v1/GenerateIT", {
      method: "POST",
      headers: BG_HEADERS,
      body: JSON.stringify(["O43z0dpjhgX20SCx4KAo", resp]),
    });
    const [integrityToken, estimatedTtlSecs] = await r.json();
    if (!integrityToken) throw new Error("no integrity token");
    await g.newMinter(integrityToken);
    const m = g.minter;
    const pot = await m.mintAsWebsafeString(PROBE);
    const itLen = b64len(pot) - PROBE.length - 14;
    return { m, at: Date.now(), ttl: (estimatedTtlSecs || 3600) * 1000, good: false, itLen, raw: b64len(integrityToken) };
  }

  g.__thBootstraps = [];
  const shortClass = (m) => m.itLen <= (g.__thShortClass || ACCEPTED_MAX_IT);
  const fresh = (m) => m && Date.now() - m.at < m.ttl * 0.8;
  let good = null;      // a minter in the short class
  let current = null;   // the best there is while looking for one
  let rolling = null;
  async function one(i) {
    const m = await bootOnce();
    g.__thBootstraps.push(m.itLen);
    log(`botguard bootstrap ${i + 1}: integrity token ${m.itLen} bytes (${shortClass(m) ? "accepted" : "rejected"} class)`);
    if (shortClass(m)) good = m;
    current = m;
    return m;
  }
  // more bootstraps until one lands in the short class (about one in three), at most 8 in all
  async function roll(from) {
    for (let i = from; i < 8 && !fresh(good); i++) {
      // not in a burst: a page a second at most, as a person clicking about
      await new Promise((ok) => setTimeout(ok, 1000));
      try { await one(i); } catch (e) {
        log("botguard bootstrap failed: " + (e?.message || e) + " " + (e?.cause?.code || ""));
        if (/slow down/.test(String(e?.message))) break;
      }
    }
  }
  // the minter sabr-page.js holds: it always mints with the best one there is by then
  const wrap = (m) => ({
    m: { mint: (v) => (good || current).m.mint(v), mintAsWebsafeString: (v) => (good || current).m.mintAsWebsafeString(v) },
    at: m.at, ttl: m.ttl, good: false,
  });
  g.__thMakeMinter = async () => {
    if (fresh(good)) return wrap(good);
    if (rolling) { await rolling; if (fresh(good) || fresh(current)) return wrap(good || current); }
    good = null;
    let first = null;
    for (let i = 0; i < 3 && !first; i++) {
      try { first = await one(i); } catch (e) {
        log("botguard bootstrap failed: " + (e?.message || e) + " " + (e?.cause?.code || ""));
        if (i === 2 || /slow down/.test(String(e?.message))) throw e;
        await new Promise((ok) => setTimeout(ok, 1000));
      }
    }
    // a token of the long class still gets a video's first minute: playback starts with it while better ones
    // are looked for, and the pots minted after that come from the better one
    if (!shortClass(first)) rolling ??= roll(1).finally(() => { rolling = null; });
    return wrap(first);
  };
})();
