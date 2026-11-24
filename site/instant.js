// Tubeless on the open web: the browser side (window.tubeless) of the same Kotlin/Wasm app the extension runs,
// for a plain static site. Two modes:
//  - Full mode: the Tubeless extension is installed. Its content script (extension/src/site.js) answers our ping,
//    and the page hands over to the extension's own app page (sign-in, SABR HD, downloads).
//  - Instant mode: no extension. YouTube's InnerTube answers a sandboxed frame (opaque origin "null") with
//    "Access-Control-Allow-Origin: null" for simple text/plain POSTs, so every InnerTube call goes through a hidden
//    <iframe sandbox="allow-scripts"> (postMessage RPC). No custom headers ever (they would need a preflight,
//    which YouTube refuses); the client name, version and visitorData are in the JSON body's context.
//    googlevideo's bytes can't be read from here, but <video src>/<audio src> need no CORS: the picture plays in a
//    muted <video> and the sound in an <audio>, kept in step (or one native HLS <video> on Safari).
// See docs/research/NO-EXTENSION-A.md and -B.md.
"use strict";
(() => {
  const params = new URLSearchParams(location.search);
  const $ = (id) => document.getElementById(id);
  const log = (...a) => console.log("[tubeless]", ...a);
  const gecko = /Gecko\/\d/.test(navigator.userAgent) && /Firefox\//.test(navigator.userAgent);
  const apple = /Apple/.test(navigator.vendor) && !/CriOS|FxiOS|EdgiOS/.test(navigator.userAgent);
  const ios = /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
  const LINKS = { extension: "https://github.com/fjenn/tubeless-publik/releases", app: "https://github.com/fjenn/tubeless-publik/releases" };

  // ------------------------------------------------------------------ notices (HTML over the canvas)
  const notice = (() => {
    const el = $("tubeless-notice");
    let shown = null;
    return {
      show(key, text, actions = true) {
        if (shown === key) return;
        shown = key;
        el.replaceChildren();
        const p = document.createElement("span");
        p.textContent = text;
        el.append(p);
        if (actions) {
          for (const [label, href] of [["Add the extension", LINKS.extension], ["Get the app", LINKS.app]]) {
            const a = document.createElement("a");
            a.textContent = label; a.href = href; a.target = "_blank"; a.rel = "noopener";
            el.append(a);
          }
        }
        const x = document.createElement("button");
        x.textContent = "×"; x.title = "Close"; x.onclick = () => notice.hide();
        el.append(x);
        el.hidden = false;
      },
      hide(key) { if (key && key !== shown) return; shown = null; el.hidden = true; },
    };
  })();

  // ------------------------------------------------------------------ Full mode: hand over to the extension
  const extension = new Promise((ok) => {
    if (params.has("instant")) return ok(null);
    const on = (e) => {
      if (e.source !== window || e.data?.k !== "tubeless-ext-here") return;
      window.removeEventListener("message", on);
      ok(e.data);
    };
    window.addEventListener("message", on);
    let n = 0;
    const ping = () => { window.postMessage({ k: "tubeless-ext-ping" }, location.origin); if (++n < 6) setTimeout(ping, 80); };
    ping();
    setTimeout(() => { window.removeEventListener("message", on); ok(null); }, 600);
  });

  // ------------------------------------------------------------------ the null-origin bridge
  const BRIDGE_SRC = `<script>
onmessage = async (e) => {
  const m = e.data;
  if (!m || m.k !== "tl-fetch") return;
  try {
    const init = { method: m.method, credentials: "omit", cache: "no-store", referrerPolicy: "no-referrer" };
    if (m.body != null) { init.body = m.body; init.headers = { "Content-Type": "text/plain;charset=UTF-8" }; }
    const r = await fetch(m.url, init);
    parent.postMessage({ k: "tl-ret", id: m.id, status: r.status, text: await r.text() }, "*");
  } catch (err) { parent.postMessage({ k: "tl-ret", id: m.id, error: String(err) }, "*"); }
};
parent.postMessage({ k: "tl-ready", origin: self.origin }, "*");
<\/script>`;
  const bridge = (() => {
    const f = document.createElement("iframe");
    f.id = "tubeless-bridge";
    f.setAttribute("sandbox", "allow-scripts");
    f.setAttribute("aria-hidden", "true");
    f.tabIndex = -1;
    f.srcdoc = BRIDGE_SRC;
    const waiting = new Map();
    let ids = 0, origin = null;
    const ready = new Promise((ok) => {
      window.addEventListener("message", (e) => {
        if (e.source !== f.contentWindow) return;
        const m = e.data;
        if (m?.k === "tl-ready") { origin = m.origin; ok(); }
        else if (m?.k === "tl-ret") { const w = waiting.get(m.id); waiting.delete(m.id); if (w) w(m); }
      });
    });
    document.documentElement.appendChild(f);
    return {
      ready,
      get origin() { return origin; },
      async fetch(method, url, body) {
        await ready;
        const id = ++ids;
        return new Promise((ok) => {
          waiting.set(id, ok);
          f.contentWindow.postMessage({ k: "tl-fetch", id, method, url, body }, "*");
          setTimeout(() => { if (waiting.delete(id)) ok({ error: "timeout" }); }, 30000);
        });
      },
    };
  })();

  // YouTube stopped answering the bridge (an allow-list change): say so once, and play through YouTube's embed
  const health = {
    fails: 0, broken: false, calls: {},
    seen(ok) {
      if (ok) { health.fails = 0; return; }
      if (++health.fails < 2 || health.broken) return;
      health.broken = true;
      log("InnerTube refuses this page now; videos go to YouTube's embedded player");
      notice.show("broken", "YouTube isn't answering this website right now, so videos play in YouTube's own player (ads possible). The extension and the app keep working.");
    },
  };

  // ------------------------------------------------------------------ net
  const YT_HOSTS = ["youtube.com", "googleapis.com", "youtube-nocookie.com"];
  const isYt = (h) => YT_HOSTS.some((d) => h === d || h.endsWith("." + d));
  const DROP = new Set(["cookie", "origin", "referer", "user-agent", "host", "content-length", "authorization"]);
  let jsonp = 0;
  function suggest(u) {
    // no ACAO on suggestqueries, but its callback= answer loads as a script; reshaped to client=firefox's [q, [..]]
    return new Promise((ok) => {
      const name = "__tlSuggest" + ++jsonp;
      const q = u.searchParams.get("q") || "";
      const s = document.createElement("script");
      const done = (arr) => { delete window[name]; s.remove(); ok({ status: 200, text: JSON.stringify(arr) }); };
      window[name] = (d) => done([q, (d?.[1] || []).map((x) => (Array.isArray(x) ? x[0] : x))]);
      s.onerror = () => done([q, []]);
      s.src = `https://suggestqueries-clients6.youtube.com/complete/search?client=youtube&ds=yt&hl=${encodeURIComponent(u.searchParams.get("hl") || "en")}&q=${encodeURIComponent(q)}&callback=${name}`;
      document.head.appendChild(s);
      setTimeout(() => window[name] && done([q, []]), 5000);
    });
  }
  // Signed in with a code (TvSignIn.kt): the app hands "Authorization: Bearer …"; a real header would need a
  // preflight YouTube refuses, so it rides Google's $httpHeaders query parameter instead (still a simple request)
  function withToken(url, headerList) {
    const parts = headerList ? headerList.split("\u0000") : [];
    for (let i = 0; i + 1 < parts.length; i += 2) {
      if (parts[i].toLowerCase() === "authorization" && parts[i + 1].startsWith("Bearer ")) {
        return url + (url.includes("?") ? "&" : "?") + "%24httpHeaders=" + encodeURIComponent("Authorization: " + parts[i + 1] + "\r\n");
      }
    }
    return url;
  }
  const empty = (binary, status) => (binary ? { status, bytes: new ArrayBuffer(0) } : { status, text: "" });
  const net = {
    async fetch(method, url, headerList, body, binary) {
      if (url.startsWith("opfs://") || url.startsWith("sabrcap://")) return empty(binary, 404);
      let u;
      try { u = new URL(url); } catch (e) { return empty(binary, 400); }
      const host = u.hostname;
      // watch-time and playback pings: fire and forget (no-cors, the answer is never read)
      if ((isYt(host) && /^\/(api\/stats\/|ptracking)/.test(u.pathname)) || host === "s.youtube.com") {
        fetch(withToken(url, headerList), { mode: "no-cors", credentials: "omit", keepalive: true, referrerPolicy: "no-referrer" }).catch(() => {});
        return empty(binary, 204);
      }
      if (host.startsWith("suggestqueries")) return suggest(u);
      // googlevideo's bytes are not readable from a page (no ACAO): only <video>/<audio> may use them
      if (host.endsWith("googlevideo.com")) return empty(binary, 0);
      // Google's sign-in endpoints answer any origin (CORS): straight from the page, as a form
      if (isYt(host) && host !== "oauth2.googleapis.com") {
        const inner = u.pathname.startsWith("/youtubei/");
        // ?simulate=broken: YouTube refusing the bridge, as it would after an allow-list change (tests the fallback)
        const r = inner && params.get("simulate") === "broken" ? { status: 403, text: "" } : await bridge.fetch(method, inner ? withToken(url, headerList) : url, method === "GET" ? null : body ?? "");
        if (inner) { health.seen(!r.error && r.status !== 403 && r.status !== 0); health.calls[u.pathname.split("/").pop()] = r.error ? "error" : r.status; }
        if (r.error) throw new Error(r.error);
        fonts.scan(r.text);
        if (binary) return { status: r.status, bytes: new TextEncoder().encode(r.text).buffer };
        return { status: r.status, text: r.text };
      }
      // everyone else (thumbnails, SponsorBlock, DeArrow, Return YouTube Dislike) allows CORS
      const h = {};
      const parts = headerList ? headerList.split("\u0000") : [];
      for (let i = 0; i + 1 < parts.length; i += 2) if (!DROP.has(parts[i].toLowerCase()) && !/^x-(youtube|goog)/i.test(parts[i])) h[parts[i]] = parts[i + 1];
      const r = await fetch(url, { method, headers: h, body: body ?? undefined, credentials: "omit", referrerPolicy: "no-referrer" });
      if (binary) return { status: r.status, bytes: await r.arrayBuffer() };
      const text = await r.text();
      fonts.scan(text);
      return { status: r.status, text };
    },
  };

  // ------------------------------------------------------------------ fs: the app's small files (subscriptions, history, playlists...) in IndexedDB
  const db = new Promise((ok, no) => {
    const q = indexedDB.open("tubeless-site", 1);
    q.onupgradeneeded = () => q.result.createObjectStore("files");
    q.onsuccess = () => ok(q.result);
    q.onerror = () => no(q.error);
  });
  const store = async (mode) => (await db).transaction("files", mode).objectStore("files");
  const fs = {
    put(path, text) { store("readwrite").then((s) => s.put(text, path)); },
    del(path) { store("readwrite").then((s) => s.delete(path)); },
    async loadAll() {
      try {
        const s = await store("readonly");
        const [keys, vals] = await Promise.all([s.getAllKeys(), s.getAll()].map((q) => new Promise((ok) => { q.onsuccess = () => ok(q.result); })));
        return keys.map((k, i) => [k, vals[i]]);
      } catch (e) { log("storage unavailable:", e.message); return []; }
    },
  };
  navigator.storage?.persist?.().catch(() => {});

  // ------------------------------------------------------------------ auth: none here (no cookies reach YouTube from a page)
  const auth = { sapisid: null, onChange: null, async refresh() { return null; }, openSignIn() { window.open(LINKS.extension, "_blank"); } };

  // ------------------------------------------------------------------ fonts: Noto families fetched when a script shows up
  const NOTO = "https://cdn.jsdelivr.net/gh/notofonts/notofonts.github.io@main/fonts/";
  const FONTS = {
    jp: [/[぀-ヿ]/, "https://cdn.jsdelivr.net/gh/notofonts/noto-cjk@main/Sans/SubsetOTF/JP/NotoSansJP-Regular.otf"],
    sc: [/[一-鿿]/, "https://cdn.jsdelivr.net/gh/notofonts/noto-cjk@main/Sans/SubsetOTF/SC/NotoSansSC-Regular.otf"],
    deva: [/[ऀ-ॿ]/, NOTO + "NotoSansDevanagari/hinted/ttf/NotoSansDevanagari-Regular.ttf"],
    arab: [/[؀-ۿ]/, NOTO + "NotoSansArabic/hinted/ttf/NotoSansArabic-Regular.ttf"],
    thai: [/[฀-๿]/, NOTO + "NotoSansThai/hinted/ttf/NotoSansThai-Regular.ttf"],
    hebr: [/[֐-׿]/, NOTO + "NotoSansHebrew/hinted/ttf/NotoSansHebrew-Regular.ttf"],
    beng: [/[ঀ-৿]/, NOTO + "NotoSansBengali/hinted/ttf/NotoSansBengali-Regular.ttf"],
    taml: [/[஀-௿]/, NOTO + "NotoSansTamil/hinted/ttf/NotoSansTamil-Regular.ttf"],
    emoji: [/[\u{1f300}-\u{1faff}\u{2600}-\u{27bf}]/u, "https://cdn.jsdelivr.net/gh/googlefonts/noto-emoji@v2.047/fonts/NotoColorEmoji.ttf"],
  };
  const fonts = {
    asked: new Set(),
    onNeed: null,
    scan(text) {
      if (!fonts.onNeed || !text || fonts.asked.size === Object.keys(FONTS).length) return;
      for (const [k, [re]] of Object.entries(FONTS)) if (!fonts.asked.has(k) && re.test(text)) fonts.need(k);
    },
    need(k) {
      if (fonts.asked.has(k) || !FONTS[k]) return;
      fonts.asked.add(k);
      fetch(FONTS[k][1], { credentials: "omit" }).then((r) => (r.ok ? r.arrayBuffer() : Promise.reject(new Error("HTTP " + r.status))))
        .then((b) => fonts.onNeed && fonts.onNeed(k, b)).catch((e) => { log("font", k, e.message); fonts.asked.delete(k); });
    },
  };

  // ------------------------------------------------------------------ YouTube's embedded player: the fallback
  const embed = (() => {
    const box = $("tubeless-embed");
    let yt = null, api = null, timer = null, onState = () => {}, current = null, rect = [0, 0, 0, 0];
    const load = () => api ||= new Promise((ok, no) => {
      window.onYouTubeIframeAPIReady = () => ok(window.YT);
      const s = document.createElement("script");
      s.src = "https://www.youtube.com/iframe_api";
      s.onerror = () => { api = null; no(new Error("iframe_api did not load")); };
      document.head.appendChild(s);
    });
    const tick = () => {
      if (!yt?.getCurrentTime) return;
      const st = yt.getPlayerState?.() ?? -1;
      onState(yt.getCurrentTime() || 0, yt.getDuration() || 0, st === 1, st === 3, st === 0, 0);
    };
    return {
      get on() { return current !== null; },
      onTime(f) { onState = f; },
      async play(id, start, autoplay) {
        current = id;
        box.hidden = false;
        embed.place(...rect);
        notice.show("embed", "This video plays in YouTube's own player, so ads are possible. The extension or the app plays it ad-free.");
        const YT = await load();
        if (current !== id) return;
        if (yt) { autoplay ? yt.loadVideoById({ videoId: id, startSeconds: start }) : yt.cueVideoById({ videoId: id, startSeconds: start }); }
        else {
          const inner = document.createElement("div");
          box.replaceChildren(inner);
          await new Promise((ok) => {
            yt = new YT.Player(inner, {
              videoId: id, width: "100%", height: "100%", host: "https://www.youtube-nocookie.com",
              playerVars: { autoplay: autoplay ? 1 : 0, start: Math.floor(start), playsinline: 1, rel: 0, modestbranding: 1 },
              events: { onReady: ok, onStateChange: tick, onError: (e) => log("embed error", e.data) },
            });
          });
        }
        clearInterval(timer);
        timer = setInterval(tick, 500);
      },
      stop() {
        current = null;
        clearInterval(timer);
        try { yt?.stopVideo?.(); } catch (e) { /* gone */ }
        box.hidden = true;
        notice.hide("embed");
      },
      call(name, ...a) { try { yt?.[name]?.(...a); } catch (e) { /* not ready */ } },
      place(x, y, w, h) {
        rect = [x, y, w, h];
        Object.assign(box.style, { left: x + "px", top: y + "px", width: w + "px", height: h + "px" });
        box.style.visibility = current && w > 0 && h > 0 ? "visible" : "hidden";
      },
    };
  })();

  // ------------------------------------------------------------------ player: a muted <video> + an <audio>, kept in step
  const player = (() => {
    const v = $("tubeless-video"), a = $("tubeless-audio");
    v.muted = true;
    // Safari (iOS above all) plays with sound only from a user gesture; the stream link arrives a moment after the
    // tap on a card. An element that saw load() inside any gesture may play later, so both are unlocked on the first one.
    const unlock = () => {
      for (const el of [v, a]) if (!el.getAttribute("src")) { try { el.load(); } catch (e) { /* nothing to unlock */ } }
      for (const t of ["pointerup", "touchend", "keydown"]) document.removeEventListener(t, unlock, true);
    };
    for (const t of ["pointerup", "touchend", "keydown"]) document.addEventListener(t, unlock, true);
    let onState = () => {};
    // what Kotlin asked for
    const want = { playing: false, rate: 1, volume: 1, muted: false, loop: false };
    let hint = { id: "", hls: "", client: "", audioOnly: false };
    let mode = "none";            // pair | audio | single (HLS) | embed
    let sets = { video: [], audio: [] }, curV = null, curA = null, wantHeight = 0, maxHeight = 1080;
    let stalls = [], tainted = false, captionUrl = null, loadToken = 0, lastJump = 0, started = false;
    const stats = { hardSeeks: 0, nudges: 0, samples: 0, sumAbs: 0, maxAbs: 0, last: 0, stalls: 0 };
    const master = () => (mode === "pair" || mode === "audio" ? a : v);

    const tick = () => {
      if (mode === "embed") return;
      const m = master();
      const dur = isFinite(m.duration) ? m.duration : 0;
      const buffering = want.playing && (m.readyState < 3 || m.seeking || (mode === "pair" && !document.hidden && (v.readyState < 3 || v.seeking)));
      onState(m.currentTime || 0, dur, want.playing && !m.ended, buffering, m.ended, curV ? curV.height : 0);
    };
    for (const el of [v, a]) for (const e of ["timeupdate", "play", "pause", "waiting", "playing", "ended", "durationchange", "seeked", "canplay"]) el.addEventListener(e, tick);
    embed.onTime((...s) => onState(...s));

    // ---- the keeper: every 100 ms, the picture follows the sound
    // a paused element may stop at HAVE_CURRENT_DATA (Firefox does), which is enough to start from
    const ready = (el) => !el.seeking && el.readyState >= (el.paused ? 2 : 3);
    function keep() {
      if (mode === "single" || mode === "embed" || mode === "none") return;
      if (mode === "audio") { if (want.playing && a.paused && !a.ended) a.play().catch(() => {}); return; }
      if (!want.playing || a.ended) { if (!v.paused) v.pause(); return; }
      // in the background the picture rests (the sound plays on); it catches up when the page shows again
      if (document.hidden) { if (!v.paused) v.pause(); if (a.paused && ready(a)) a.play().catch(() => {}); return; }
      const vr = ready(v), ar = ready(a);
      // a load that hangs (seen in Firefox after a far seek: the element stays at its metadata): start it afresh there
      for (const [el, r] of [[v, vr], [a, ar]]) {
        if (r || el.readyState >= 2 || !started) { el._waitSince = 0; continue; }
        el._waitSince ||= Date.now();
        if (Date.now() - el._waitSince > 12000) { el._waitSince = 0; log("load hangs, restarting the", el === v ? "picture" : "sound"); reload(el); }
      }
      if (!vr || !ar) {
        // one is waiting for data: the ready one waits for it, the waiting one keeps "playing" so the browser goes
        // on loading it (pausing it mid-seek leaves Firefox at its metadata)
        if (ar && !a.paused) { a.pause(); if (started && Date.now() - lastJump > 3000) { stats.stalls++; stalled(); } }
        if (vr && !v.paused) v.pause();
        if (!ar && a.paused) a.play().catch(() => {});
        if (!vr && v.paused) v.play().catch(() => {});
        return;
      }
      started = true;
      if (a.paused) a.play().catch((e) => { log("play():", e.name); if (e.name === "NotAllowedError") { want.playing = false; tick(); } });
      if (v.paused) v.play().catch(() => {});
      const d = v.currentTime - a.currentTime;   // > 0: the picture is ahead
      stats.samples++; stats.sumAbs += Math.abs(d); stats.maxAbs = Math.max(stats.maxAbs, Math.abs(d)); stats.last = d;
      if (Math.abs(d) > 0.5) {
        // too far for a nudge: the picture jumps to the sound (a little ahead, for the time the seek takes)
        v.currentTime = a.currentTime + Math.min(0.25, 0.08 * want.rate);
        v.playbackRate = want.rate;
        stats.hardSeeks++;
        lastJump = Date.now();
      } else if (Math.abs(d) > 0.02) {
        const k = Math.max(-0.08, Math.min(0.08, d * 1.2));
        v.playbackRate = want.rate * (1 - k);
        stats.nudges++;
      } else if (v.playbackRate !== want.rate) v.playbackRate = want.rate;
    }
    setInterval(keep, 100);
    document.addEventListener("visibilitychange", () => {
      if (!document.hidden && mode === "pair") { v.currentTime = a.currentTime; lastJump = Date.now(); keep(); }
    });

    // automatic quality steps down after repeated waits (no ABR without MSE)
    function stalled() {
      const now = Date.now();
      stalls = stalls.filter((t) => now - t < 30000);
      stalls.push(now);
      if (wantHeight || stalls.length < 3 || !curV) return;
      const lower = sets.video.filter((f) => f.height < curV.height).sort((x, y) => y.height - x.height)[0];
      if (lower) { log("waiting a lot: down to", lower.height + "p"); stalls = []; setVideo(lower); }
    }

    // ---- formats: from the DASH manifest Kotlin wrote (Dash.kt); each Representation's BaseURL is a direct link
    function parse(manifest) {
      const out = { video: [], audio: [] };
      const doc = new DOMParser().parseFromString(manifest, "application/xml");
      for (const as of doc.getElementsByTagName("AdaptationSet")) {
        const type = as.getAttribute("contentType"), mime = as.getAttribute("mimeType");
        const role = as.getElementsByTagName("Role")[0]?.getAttribute("value") || "main";
        for (const r of as.getElementsByTagName("Representation")) {
          const url = r.getElementsByTagName("BaseURL")[0]?.textContent;
          if (!url) continue;
          const f = { url, mime, codecs: r.getAttribute("codecs"), height: +r.getAttribute("height") || 0, width: +r.getAttribute("width") || 0,
            bandwidth: +r.getAttribute("bandwidth") || 0, fps: +r.getAttribute("frameRate") || 0,
            lang: as.getAttribute("lang"), label: as.getAttribute("label"), main: role === "main" };
          (type === "video" ? out.video : out.audio).push(f);
        }
      }
      return out;
    }
    const playsV = (f) => v.canPlayType(`${f.mime}; codecs="${f.codecs}"`) !== "";
    const playsA = (f) => a.canPlayType(`${f.mime}; codecs="${f.codecs}"`) !== "";
    function pickVideo(h) {
      const ok = sets.video.filter(playsV);
      if (!ok.length) return null;
      const cap = h || maxHeight || 1080;
      const under = ok.filter((f) => f.height <= cap);
      const pool = under.length ? under : [ok.reduce((x, y) => (x.height < y.height ? x : y))];
      return pool.sort((x, y) => y.height - x.height || y.fps - x.fps || y.bandwidth - x.bandwidth)[0];
    }
    function pickAudio(lang) {
      const ok = sets.audio.filter(playsA);
      if (!ok.length) return null;
      const byLang = lang ? ok.filter((f) => f.lang === lang) : [];
      const pool = byLang.length ? byLang : ok.filter((f) => f.main).length ? ok.filter((f) => f.main) : ok;
      // the sound's container follows the picture's (WebM opus with VP9, MP4 AAC with H.264), Safari prefers MP4
      const prefer = apple ? "audio/mp4" : curV?.mime === "video/mp4" ? "audio/mp4" : "audio/webm";
      return pool.find((f) => f.mime === prefer) || pool[0];
    }
    function setVideo(f) {
      if (!f || f === curV) return;
      curV = f;
      lastJump = Date.now();
      const t = a.currentTime || 0;
      v.src = f.url;
      v.addEventListener("loadedmetadata", () => { v.currentTime = a.currentTime || t; }, { once: true });
      tick();
    }
    function setAudio(f, keepTime = true) {
      if (!f || f === curA) return;
      const t = a.currentTime || 0;
      curA = f;
      a.src = f.url;
      if (keepTime) a.addEventListener("loadedmetadata", () => { a.currentTime = t; }, { once: true });
    }
    function clear(el) { el.pause(); el.removeAttribute("src"); el.load(); }
    function waitMeta(el, token) {
      return new Promise((ok) => {
        const done = (r) => { el.removeEventListener("loadedmetadata", m); el.removeEventListener("error", er); ok(r); };
        const m = () => done("");
        const er = () => { if (retries > 3) done("media error " + (el.error?.code || "")); };
        el.addEventListener("loadedmetadata", m);
        el.addEventListener("error", er);
        setTimeout(() => done(token === loadToken && el.readyState < 1 ? "timeout" : ""), 30000);
      });
    }
    function toEmbed(start) {
      const id = hint.id;
      clear(v); clear(a);
      mode = "embed";
      embed.play(id, start, want.playing).catch((e) => log("embed:", e.message));
    }
    // a link that fails (a slow or refused start, 403, expired): once more, then a lower quality for the picture,
    // and only then YouTube's own player takes over from there
    let retries = 0;
    function reload(el) {
      const t = a.currentTime || 0;
      lastJump = Date.now();
      // a media fragment (#t=) makes the browser's first request start near there instead of at byte 0
      el.src = el.getAttribute("src").replace(/#t=[\d.]+$/, "") + "#t=" + t.toFixed(2);
      el.addEventListener("loadedmetadata", () => { if (Math.abs(el.currentTime - t) > 0.5) el.currentTime = t; if (want.playing) el.play().catch(() => {}); }, { once: true });
    }
    function retry(el) {
      const t = a.currentTime || v.currentTime || 0;
      if (retries++ >= 3 || mode === "single") return false;
      lastJump = Date.now();
      if (el === v && retries >= 2) {
        const lower = sets.video.filter(playsV).filter((f) => f.height < (curV?.height || 0)).sort((x, y) => y.height - x.height)[0];
        if (lower) { log("picture failed, trying", lower.height + "p"); curV = null; setVideo(lower); return true; }
      }
      const src = el.getAttribute("src");
      el.src = src;
      el.addEventListener("loadedmetadata", () => { el.currentTime = el === v ? a.currentTime || t : t; if (want.playing && el === a) a.play().catch(() => {}); }, { once: true });
      return true;
    }
    for (const el of [v, a]) el.addEventListener("error", () => {
      if ((mode === "pair" || mode === "audio" || mode === "single") && el.getAttribute("src") && el.error && el.error.code !== 1) {
        log("media error", el.error.code, el.error.message || "", el === v ? "video" : "audio", (curV || {}).codecs || "");
        if (!retry(el)) toEmbed(a.currentTime || v.currentTime || 0);
      }
    });
    a.addEventListener("ended", () => {
      if (want.loop) { a.currentTime = 0; if (mode === "pair") v.currentTime = 0; a.play().catch(() => {}); }
      else { v.pause(); tick(); }
    });
    v.addEventListener("ended", () => { if (mode === "single" && want.loop) { v.currentTime = 0; v.play().catch(() => {}); } });

    return {
      instant: true,
      stats,
      supports(type) {
        // what a plain <video src> plays; AV1 is left out (often decoded in software), and Safari gets H.264
        if (/av01/.test(type)) return false;
        if (apple && /webm|vp9/.test(type)) return false;
        return v.canPlayType(type) !== "";
      },
      onTime(f) { onState = f; },
      hint(id, hls, client, audioOnly) { hint = { id, hls, client, audioOnly }; },
      async load(manifest, isHls, start, autoplay, maxH) {
        const token = ++loadToken;
        lastJump = Date.now();
        embed.stop();
        clear(v); clear(a);
        curV = curA = null; stalls = []; wantHeight = 0; retries = 0; started = false;
        maxHeight = maxH > 0 ? maxH : 1080;
        want.playing = autoplay;
        Object.assign(stats, { hardSeeks: 0, nudges: 0, samples: 0, sumAbs: 0, maxAbs: 0, last: 0, stalls: 0 });
        sets = isHls ? { video: [], audio: [] } : parse(manifest);
        if (hint.client === "EMBED" || health.broken && !sets.audio.length) { toEmbed(start); return ""; }
        const hls = isHls ? manifest : hint.hls;
        if (hls && (isHls || (apple && !hint.audioOnly && v.canPlayType("application/vnd.apple.mpegurl")))) {
          // Safari (and live videos): one native HLS <video> with the sound in it
          mode = "single";
          v.muted = want.muted;
          v.volume = want.volume;
          v.src = hls;
          const err = await waitMeta(v, token);
          if (token !== loadToken) return "";
          if (err && !isHls && sets.audio.length) { log("HLS failed (" + err + "), playing the formats"); }
          else {
            if (err) { toEmbed(start); return ""; }
            if (start > 0) v.currentTime = start;
            v.playbackRate = want.rate;
            if (autoplay) v.play().catch((e) => {
              log("play()", e.message);
              // ?testmuted=1 (the simulator test, which can't tap): muted playback where sound needs a tap
              if (params.has("testmuted")) { v.muted = true; v.play().catch(() => {}); return; }
              want.playing = false; tick();
            });
            tick();
            return "";
          }
        }
        mode = hint.audioOnly || !sets.video.length ? "audio" : "pair";
        v.muted = true;
        if (mode === "pair") curV = pickVideo(0);
        if (mode === "pair" && !curV) mode = "audio";
        curA = pickAudio(null);
        if (!curA) { toEmbed(start); return ""; }
        a.src = curA.url;
        a.volume = want.volume; a.muted = want.muted; a.playbackRate = want.rate;
        if (mode === "pair") v.src = curV.url;
        const errs = await Promise.all(mode === "pair" ? [waitMeta(a, token), waitMeta(v, token)] : [waitMeta(a, token)]);
        if (token !== loadToken) return "";
        if (errs.some(Boolean)) { log("formats failed:", errs.join(" ")); if (mode !== "embed") toEmbed(start); return ""; }
        if (start > 0) { a.currentTime = start; if (mode === "pair") v.currentTime = start; }
        log(`playing ${hint.id}: ${mode === "pair" ? curV.height + "p " + curV.codecs + " + " : ""}${curA.codecs}`);
        keep();
        tick();
        return "";
      },
      tracks() {
        const heights = [...new Set(sets.video.filter(playsV).map((f) => f.height))].sort((x, y) => y - x);
        const audio = [...new Map(sets.audio.filter((f) => f.lang).map((f) => [f.lang, f.label || f.lang])).entries()];
        return JSON.stringify({ heights: mode === "pair" ? heights : [], audio, lang: curA?.lang || null });
      },
      setHeight(h) { wantHeight = h; stalls = []; if (mode === "pair") setVideo(pickVideo(h)); },
      setAudio(lang) { if (mode === "pair" || mode === "audio") setAudio(pickAudio(lang)); },
      async setCaptions(vtt) {
        for (const t of [...v.querySelectorAll("track")]) t.remove();
        if (captionUrl) URL.revokeObjectURL(captionUrl);
        captionUrl = null;
        if (vtt == null) return;
        captionUrl = URL.createObjectURL(new Blob([vtt], { type: "text/vtt" }));
        const t = document.createElement("track");
        t.kind = "subtitles"; t.src = captionUrl; t.default = true; t.srclang = "und";
        v.appendChild(t);
        t.track.mode = "showing";
      },
      play() {
        want.playing = true;
        if (mode === "embed") return embed.call("playVideo");
        const m = master();
        if (m.ended) { m.currentTime = 0; if (mode === "pair") v.currentTime = 0; }
        // the sound starts inside the click (autoplay rules); the keeper brings the picture along
        m.play().catch((e) => { if (e.name === "NotAllowedError") { want.playing = false; tick(); } });
        keep();
      },
      pause() {
        want.playing = false;
        if (mode === "embed") return embed.call("pauseVideo");
        a.pause(); v.pause();
        if (mode === "pair") v.currentTime = a.currentTime;
        tick();
      },
      stop() { loadToken++; want.playing = false; mode = "none"; clear(v); clear(a); embed.stop(); },
      seek(t) {
        stats.seeks = (stats.seeks || 0) + 1;
        lastJump = Date.now();
        if (mode === "embed") return embed.call("seekTo", t, true);
        if (mode === "single") { v.currentTime = t; return; }
        a.currentTime = t;
        if (mode === "pair") { v.currentTime = t; v.playbackRate = want.rate; }
        tick();
      },
      speed(x) {
        want.rate = x;
        if (mode === "embed") return embed.call("setPlaybackRate", x);
        a.playbackRate = x; v.playbackRate = x;
      },
      loop(on) { want.loop = on; },
      volume(x) {
        want.volume = Math.max(0, Math.min(1, x));
        if (mode === "embed") return embed.call("setVolume", Math.round(want.volume * 100));
        a.volume = want.volume; if (mode === "single") v.volume = want.volume;
      },
      muted(on) {
        want.muted = on;
        if (mode === "embed") return embed.call(on ? "mute" : "unMute");
        a.muted = on; if (mode === "single") v.muted = on;
      },
      pip() { (document.pictureInPictureElement ? document.exitPictureInPicture() : v.requestPictureInPicture()).catch(() => {}); },
      place(x, y, w, h) {
        Object.assign(v.style, { left: x + "px", top: y + "px", width: w + "px", height: h + "px" });
        embed.place(x, y, w, h);
      },
      session(title, artist, art, onAction) {
        if (!("mediaSession" in navigator)) return;
        navigator.mediaSession.metadata = new MediaMetadata({ title, artist, artwork: art ? [{ src: art, sizes: "480x360", type: "image/jpeg" }] : [] });
        for (const x of ["play", "pause", "previoustrack", "nexttrack", "seekbackward", "seekforward"]) {
          try { navigator.mediaSession.setActionHandler(x, () => onAction(x)); } catch (e) { /* not offered here */ }
        }
      },
      // the ambient glow needs the picture's pixels; a cross-origin <video> doesn't give them (the canvas is tainted)
      frame(w, h) {
        if (tainted || mode !== "pair" && mode !== "single" || !v.videoWidth || v.readyState < 2) return null;
        try {
          const c = (this._c ||= new OffscreenCanvas(w, h));
          const g = c.getContext("2d", { willReadFrequently: true });
          g.drawImage(v, 0, 0, w, h);
          return g.getImageData(0, 0, w, h).data.buffer;
        } catch (e) { tainted = true; return null; }
      },
      get mode() { return mode; },
      get want() { return { ...want }; },
      get formats() { return sets; },
      video: v, audio: a,
    };
  })();

  // ------------------------------------------------------------------ what Instant mode can't do
  const sabr = {
    async prepare() { throw new Error("SABR needs the extension"); },
    async caption() { return ""; },
    forget() {},
  };
  const nope = async () => { throw new Error("Downloads need the Tubeless app or the extension"); };
  const dl = {
    fetchTo: async () => ({ status: 0, have: 0 }), writeText: nope, readText: nope, readBytes: nope,
    size: async () => 0, saveFirst: async () => false, exists: async () => false, remove: async () => {}, exportJoined: nope,
  };

  const ready = (async () => {
    const ext = await extension;
    if (ext) {
      // Full mode: the extension's own page (sign-in, SABR HD, downloads) takes this tab
      log("Tubeless extension found: opening it");
      $("tubeless-loading").textContent = "Opening Tubeless…";
      window.postMessage({ k: "tubeless-open-full", search: location.search, hash: location.hash }, location.origin);
      await new Promise(() => {});   // the page goes away
    }
    await bridge.ready;
    log("Instant mode; bridge origin:", bridge.origin, ios ? "(iOS)" : apple ? "(Safari)" : "");
  })();

  // ?report=<seconds>: test hook for browsers without a driver (the iPhone simulator): the player's state is posted
  // to /__report on this page's own server now and then
  if (params.get("report")) {
    setInterval(() => {
      const p = window.tubeless?.player;
      if (!p) return;
      const v = p.video, a = p.audio;
      const st = { mode: p.mode, vt: v.currentTime, at: a.currentTime, vw: v.videoWidth, vh: v.videoHeight, vp: v.paused, ap: a.paused,
        vsrc: (v.currentSrc || "").slice(0, 50), d: p.mode === "pair" ? v.currentTime - a.currentTime : 0, stats: p.stats, calls: health.calls,
        embed: embed.on, q: location.search, hidden: document.hidden, ua: navigator.userAgent };
      fetch("/__report", { method: "POST", body: JSON.stringify(st) }).catch(() => {});
    }, 1000 * +params.get("report"));
  }

  window.tubeless = { instant: true, net, fs, auth, fonts, player, sabr, dl, embed, bridge, health, notice, ready };
})();
