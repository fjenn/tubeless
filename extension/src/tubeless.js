// The browser side of the Kotlin/Wasm app (window.tubeless). The Kotlin code calls in through js()
// functions in common/web/app/tubehaus/web/*.kt and web/src/**; bytes (video pieces, downloads)
// stay on this side, Kotlin gets text and small files.
"use strict";
(() => {
  // the extension's APIs, or the stand-ins of yt-host.js when Tubeless runs inside youtube.com (the bookmark, site/)
  const api = globalThis.tubelessHost ?? globalThis.browser ?? globalThis.chrome;
  // our own requests only: the extension page's host (Chrome: the extension id, Firefox: its uuid)
  const SELF = location.hostname;
  const YT = ["youtube.com", "googleapis.com"];
  const set = (header, value) => ({ header, operation: "set", value });

  // ------------------------------------------------------------------ header rules (declarativeNetRequest)
  // 1: youtube.com calls carry YouTube's Origin and Referer (a chrome-extension:// Origin gets 403)
  // 2: the hidden SABR frame (https://www.youtube.com/robots.txt) may be framed by this page
  // 100+: the User-Agent of a player client, for requests marked with &kua=<n>_
  const BASE_RULES = [
    { id: 1, priority: 1,
      action: { type: "modifyHeaders", requestHeaders: [set("Origin", "https://www.youtube.com"), set("Referer", "https://www.youtube.com/")] },
      condition: { requestDomains: YT, initiatorDomains: [SELF], resourceTypes: ["xmlhttprequest"] } },
    { id: 2, priority: 1,
      action: { type: "modifyHeaders", requestHeaders: [{ header: "Cookie", operation: "remove" }],
        responseHeaders: [{ header: "X-Frame-Options", operation: "remove" }, { header: "Content-Security-Policy", operation: "remove" }, { header: "Set-Cookie", operation: "remove" }] },
      condition: { urlFilter: "|https://www.youtube.com/robots.txt?tubeless", initiatorDomains: [SELF], resourceTypes: ["sub_frame"] } },
  ];
  const uas = new Map();
  let ruleQueue = Promise.resolve();
  function rules(add, remove = []) {
    ruleQueue = ruleQueue.then(() => api.declarativeNetRequest.updateSessionRules({ removeRuleIds: [...remove, ...add.map((r) => r.id)], addRules: add }))
      .catch((e) => console.log("[tubeless] rules:", e.message));
    return ruleQueue;
  }
  async function uaMark(ua) {
    let n = uas.get(ua);
    if (n === undefined) {
      n = uas.size;
      uas.set(ua, n);
      await rules([{ id: 100 + n, priority: 2,
        action: { type: "modifyHeaders", requestHeaders: [set("User-Agent", ua)] },
        condition: { urlFilter: `kua=${n}_`, initiatorDomains: [SELF], resourceTypes: ["xmlhttprequest"] } }]);
    }
    return `kua=${n}_`;
  }

  // ------------------------------------------------------------------ net
  const WEB_FAMILY = new Set(["1", "67"]); // WEB, WEB_REMIX
  const FORBIDDEN = new Set(["cookie", "origin", "referer", "user-agent", "host", "content-length"]);
  const net = {
    async fetch(method, url, headerList, body, binary) {
      const h = {};
      const parts = headerList ? headerList.split("\u0000") : [];
      let ua = null, signed = false;
      for (let i = 0; i + 1 < parts.length; i += 2) {
        const k = parts[i], v = parts[i + 1], l = k.toLowerCase();
        if (l === "user-agent") ua = v;
        else if (!FORBIDDEN.has(l)) h[k] = v;
        if (l === "authorization" && v.startsWith("SAPISIDHASH")) signed = true;
      }
      // signed-in rule: only WEB-family calls (feeds, next, actions) carry the session; the stream
      // clients (VISIONOS, ANDROID_VR, KIDS, TV) stay anonymous even when they ask www.youtube.com
      const client = h["X-YouTube-Client-Name"];
      if (signed && client && !WEB_FAMILY.has(client)) {
        signed = false;
        for (const k of Object.keys(h)) if (/^(authorization|x-goog-authuser|x-origin)$/i.test(k)) delete h[k];
      }
      if (url.startsWith("opfs://")) {
        const path = decodeURIComponent(url.slice(7));
        try { return binary ? { status: 200, bytes: await dl.readBytes(path) } : { status: 200, text: await dl.readText(path) }; }
        catch (e) { return binary ? { status: 404, bytes: new ArrayBuffer(0) } : { status: 404, text: "" }; }
      }
      if (url.startsWith("sabrcap://")) {
        const [, id, u] = url.match(/^sabrcap:\/\/([^/]+)\/(.*)$/);
        const text = await sabr.caption(id, decodeURIComponent(u));
        return text ? { status: 200, text } : { status: 502, text: "" };
      }
      const host = new URL(url).hostname;
      if (ua && YT.some((d) => host === d || host.endsWith("." + d)) && ua !== navigator.userAgent) {
        url += (url.includes("?") ? "&" : "?") + (await uaMark(ua));
      }
      // the user's youtube.com cookies go only with a signed-in call; everything else is anonymous
      const r = await fetch(url, { method, headers: h, body: body ?? undefined, credentials: signed ? "include" : "omit" });
      if (binary) return { status: r.status, bytes: await r.arrayBuffer() };
      const text = await r.text();
      fonts.scan(text);
      return { status: r.status, text };
    },
  };

  // ------------------------------------------------------------------ fs: the app's small files, kept in IndexedDB
  const db = new Promise((ok, no) => {
    const q = indexedDB.open("tubeless", 1);
    q.onupgradeneeded = () => q.result.createObjectStore("files");
    q.onsuccess = () => ok(q.result);
    q.onerror = () => no(q.error);
  });
  const store = async (mode) => (await db).transaction("files", mode).objectStore("files");
  const fs = {
    put(path, text) { store("readwrite").then((s) => s.put(text, path)); },
    del(path) { store("readwrite").then((s) => s.delete(path)); },
    async loadAll() {
      const s = await store("readonly");
      const [keys, vals] = await Promise.all([s.getAllKeys(), s.getAll()].map((q) => new Promise((ok) => { q.onsuccess = () => ok(q.result); })));
      return keys.map((k, i) => [k, vals[i]]);
    },
  };

  // ------------------------------------------------------------------ auth: the browser's own youtube.com session
  // SAPISID is read with the cookies permission (never sent anywhere but youtube.com); Kotlin builds
  // SAPISIDHASH from it (InnerTube.authHeaders). Off until the user turns it on in Settings.
  const auth = {
    sapisid: null,
    onChange: null,
    async refresh() {
      try {
        const get = (name) => api.cookies.get({ url: "https://www.youtube.com", name });
        const c = (await get("SAPISID")) || (await get("__Secure-3PAPISID"));
        auth.sapisid = c ? c.value : null;
      } catch (e) { auth.sapisid = null; }
      return auth.sapisid;
    },
    openSignIn() { window.open("https://accounts.google.com/ServiceLogin?service=youtube&continue=https://www.youtube.com/", "_blank"); },
  };
  api.cookies?.onChanged?.addListener((ch) => {
    if (ch.cookie.domain.endsWith("youtube.com") && /APISID$/.test(ch.cookie.name)) auth.refresh().then(() => auth.onChange && auth.onChange());
  });

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
      if (!fonts.onNeed || fonts.asked.size === Object.keys(FONTS).length) return;
      for (const [k, [re]] of Object.entries(FONTS)) if (!fonts.asked.has(k) && re.test(text)) fonts.need(k);
    },
    need(k) {
      if (fonts.asked.has(k) || !FONTS[k]) return;
      fonts.asked.add(k);
      fetch(FONTS[k][1], { credentials: "omit" }).then((r) => r.ok ? r.arrayBuffer() : Promise.reject(new Error("HTTP " + r.status)))
        .then((b) => fonts.onNeed && fonts.onNeed(k, b)).catch((e) => { console.log("[tubeless] font", k, e.message); fonts.asked.delete(k); });
    },
  };

  // ------------------------------------------------------------------ player: Shaka on the DASH manifest Dash.kt writes
  const player = (() => {
    const v = document.getElementById("tubeless-video");
    shaka.polyfill.installAll();
    const sp = new shaka.Player();
    let onState = () => {}, captionUrl = null, wantHeight = 0;
    sp.attach(v);
    sp.configure({ streaming: { bufferingGoal: 60, rebufferingGoal: 2, bufferBehind: 30 }, abr: { enabled: true } });
    // googlevideo answers ranges as &range= too, and wants no credentials
    sp.getNetworkingEngine().registerRequestFilter((type, req) => { req.allowCrossSiteCredentials = false; });
    const tick = () => {
      const t = sp.getVariantTracks().find((x) => x.active);
      onState(v.currentTime || 0, isFinite(v.duration) ? v.duration : 0, !v.paused, sp.isBuffering(), v.ended, t ? t.height || 0 : 0);
    };
    for (const e of ["timeupdate", "play", "pause", "waiting", "playing", "ended", "durationchange", "seeked"]) v.addEventListener(e, tick);
    sp.addEventListener("buffering", tick);
    sp.addEventListener("adaptation", tick);
    sp.addEventListener("error", (e) => console.log("[tubeless] shaka", e.detail && e.detail.code, e.detail && e.detail.data && String(e.detail.data[0]).slice(0, 200)));
    let lastUrl = null;

    // stable volume and voice boost: Web Audio on the video's own sound (MSE bytes are this page's, so they can be
    // read). The graph is built only once the AudioContext runs (after a click): a suspended context would mute the video.
    const fx = { want: [false, false], ctx: null, n: null };
    async function fxEnsure() {
      if (!fx.want[0] && !fx.want[1] && !fx.n) return;
      const AC = window.AudioContext || window.webkitAudioContext;
      if (!AC) return;
      try {
        fx.ctx ||= new AC();
        if (fx.ctx.state !== "running") await Promise.race([fx.ctx.resume(), new Promise((r) => setTimeout(r, 300))]);
        if (fx.ctx.state !== "running") return;
        if (!fx.n) {
          const c = fx.ctx;
          const n = { src: c.createMediaElementSource(v), hp: c.createBiquadFilter(), low: c.createBiquadFilter(), mid: c.createBiquadFilter(),
            comp: c.createDynamicsCompressor(), gain: c.createGain(), out: c.createAnalyser(), pre: c.createAnalyser() };
          n.hp.type = "highpass"; n.low.type = "lowshelf"; n.mid.type = "peaking";
          n.src.connect(n.hp); n.hp.connect(n.low); n.low.connect(n.mid); n.mid.connect(n.comp); n.comp.connect(n.gain); n.gain.connect(n.out); n.out.connect(c.destination);
          n.src.connect(n.pre);   // the sound before the effects (tests compare the two)
          fx.n = n;
        }
        const [stable, voice] = fx.want, n = fx.n, t = fx.ctx.currentTime;
        // voice: rumble and boom down, the speech band (2–4 kHz) up; off: every filter flat
        n.hp.frequency.setValueAtTime(voice ? 110 : 10, t);
        n.low.frequency.setValueAtTime(220, t); n.low.gain.setValueAtTime(voice ? -6 : 0, t);
        n.mid.frequency.setValueAtTime(3000, t); n.mid.Q.setValueAtTime(0.8, t); n.mid.gain.setValueAtTime(voice ? 7 : 0, t);
        // stable: loud parts held down, the whole lifted back (as YouTube's "Stable volume"); off: ratio 1, no lift
        n.comp.threshold.setValueAtTime(stable ? -30 : 0, t); n.comp.knee.setValueAtTime(stable ? 12 : 0, t);
        n.comp.ratio.setValueAtTime(stable ? 6 : 1, t); n.comp.attack.setValueAtTime(0.004, t); n.comp.release.setValueAtTime(0.25, t);
        n.gain.gain.setValueAtTime(stable ? 1.9 : 1, t);
      } catch (e) { console.log("[tubeless] fx", e.message); }
    }
    v.addEventListener("play", fxEnsure);
    for (const e of ["pointerdown", "keydown"]) document.addEventListener(e, () => { if (!fx.n || fx.ctx?.state !== "running") fxEnsure(); }, true);

    return {
      // the sound effects (Perks.fx on the web); applied now if the page may play sound, else on the next click or play
      fx(stable, voice) { fx.want = [!!stable, !!voice]; fxEnsure(); },
      fxState: fx,
      supports(type) { return MediaSource.isTypeSupported(type); },
      onTime(f) { onState = f; },
      async load(manifest, isHls, start, autoplay, maxHeight) {
        if (lastUrl) URL.revokeObjectURL(lastUrl);
        const url = isHls ? manifest : (lastUrl = URL.createObjectURL(new Blob([manifest], { type: "application/dash+xml" })));
        wantHeight = 0;
        sp.configure({ abr: { enabled: true, restrictions: { maxHeight: maxHeight > 0 ? maxHeight : Infinity } } });
        v.autoplay = autoplay;
        try {
          await sp.load(url, start > 0 ? start : null, isHls ? "application/x-mpegurl" : "application/dash+xml");
          if (autoplay) v.play().catch((e) => console.log("[tubeless] play()", e.message));
          tick();
          return "";
        } catch (e) { return "Shaka " + (e.code || e.message); }
      },
      // the variant heights and audio languages, as JSON for Kotlin
      tracks() {
        const vt = sp.getVariantTracks();
        return JSON.stringify({
          heights: [...new Set(vt.map((t) => t.height).filter(Boolean))].sort((a, b) => b - a),
          audio: [...new Map(vt.filter((t) => t.language).map((t) => [t.language, t.label || t.language])).entries()],
          lang: (vt.find((t) => t.active) || {}).language || null,
        });
      },
      setHeight(h) {
        wantHeight = h;
        if (!h) { sp.configure({ abr: { enabled: true } }); return; }
        sp.configure({ abr: { enabled: false } });
        const lang = (sp.getVariantTracks().find((t) => t.active) || {}).language;
        const best = sp.getVariantTracks().filter((t) => t.height === h && (!lang || t.language === lang)).sort((a, b) => b.bandwidth - a.bandwidth)[0];
        if (best) sp.selectVariantTrack(best, true);
      },
      setAudio(lang) { sp.selectAudioLanguage(lang); if (wantHeight) this.setHeight(wantHeight); },
      async setCaptions(vtt) {
        if (captionUrl) URL.revokeObjectURL(captionUrl);
        captionUrl = null;
        if (vtt == null) { sp.setTextTrackVisibility(false); return; }
        captionUrl = URL.createObjectURL(new Blob([vtt], { type: "text/vtt" }));
        try {
          const t = await sp.addTextTrackAsync(captionUrl, "und", "subtitles", "text/vtt");
          sp.selectTextTrack(t);
          sp.setTextTrackVisibility(true);
        } catch (e) { console.log("[tubeless] captions", e.code || e.message); }
      },
      play() { v.play().catch(() => {}); },
      pause() { v.pause(); },
      stop() { sp.unload().catch(() => {}); },
      seek(t) { v.currentTime = t; },
      speed(x) { v.playbackRate = x; },
      loop(on) { v.loop = on; },
      volume(x) { v.volume = Math.max(0, Math.min(1, x)); },
      muted(on) { v.muted = on; },
      pip() { (document.pictureInPictureElement ? document.exitPictureInPicture() : v.requestPictureInPicture()).catch(() => {}); },
      // under the canvas where Compose laid out VideoPlayer.View (CSS pixels); the canvas leaves a hole there
      place(x, y, w, h) { Object.assign(v.style, { left: x + "px", top: y + "px", width: w + "px", height: h + "px" }); },
      // the media keys, the system's now-playing panel and background play
      session(title, artist, art, onAction) {
        if (!("mediaSession" in navigator)) return;
        navigator.mediaSession.metadata = new MediaMetadata({ title, artist, artwork: art ? [{ src: art, sizes: "480x360", type: "image/jpeg" }] : [] });
        for (const a of ["play", "pause", "previoustrack", "nexttrack", "seekbackward", "seekforward"]) {
          try { navigator.mediaSession.setActionHandler(a, () => onAction(a)); } catch (e) { /* not offered here */ }
        }
      },
      // a small, softened copy of the frame for the ambient glow: RGBA bytes of a w×h picture
      frame(w, h) {
        if (!v.videoWidth || v.readyState < 2) return null;
        const c = (this._c ||= new OffscreenCanvas(w, h));
        const g = c.getContext("2d", { willReadFrequently: true });
        g.drawImage(v, 0, 0, w, h);
        return g.getImageData(0, 0, w, h).data.buffer;
      },
      video: v,
    };
  })();


  // ------------------------------------------------------------------ SABR: the fallback for videos the token-free clients can't play
  // sabr-page.js (common/sabr: googlevideo + BotGuard, as on the desktop) runs in a hidden youtube.com frame;
  // its pieces are kept here per format (sparse, by byte), and Shaka reads them as sabr://<video>/<index>.
  const sabr = (() => {
    let frame = null, ready = null, calls = 0, runIds = 0;
    const waiting = new Map(), tracks = new Map(), runs = new Map();
    const AHEAD = 24 << 20, BACK_MS = 2000;
    function open() {
      if (ready) return ready;
      ready = new Promise((ok, no) => {
        const t = setTimeout(() => no(new Error("SABR frame did not start")), 30000);
        window.addEventListener("message", function on(e) {
          if (e.source !== frame?.contentWindow || e.data?.k !== "tubeless-sabr-ready") return;
          window.removeEventListener("message", on); clearTimeout(t); ok();
        });
      }).then(async () => {
        const solver = await (await fetch("yt-solver.txt")).text();
        if ((await call("setSolver", solver)) !== "ok") throw new Error("solver refused");
      });
      ready.catch(() => { ready = null; frame?.remove(); frame = null; });
      frame = document.createElement("iframe");
      frame.id = "tubeless-sabr";
      frame.src = "https://www.youtube.com/robots.txt?tubeless";
      api.sabrFrame?.(frame);   // the bookmark: no content scripts, so its host puts sabr-page.js in the frame
      document.body.appendChild(frame);
      return ready;
    }
    function call(fn, ...args) {
      const id = ++calls;
      return new Promise((ok, no) => { waiting.set(id, [ok, no]); frame.contentWindow.postMessage({ k: "tubeless-call", id, fn, args }, "https://www.youtube.com"); });
    }
    window.addEventListener("message", (e) => {
      if (!frame || e.source !== frame.contentWindow) return;
      const m = e.data;
      if (m.k === "tubeless-ret") { const w = waiting.get(m.id); waiting.delete(m.id); if (w) m.err ? w[1](new Error(m.err)) : w[0](m.r); }
      else if (m.k === "tubeless-sabr-bin") { const dv = new DataView(m.buf); const n = dv.getUint32(0);
        const o = JSON.parse(new TextDecoder().decode(new Uint8Array(m.buf, 4, n))); piece(o, new Uint8Array(m.buf, 4 + n)); }
      else if (m.k === "tubeless-sabr") { const o = JSON.parse(m.json);
        if (o.t === "piece") piece(o, Uint8Array.from(atob(o.data), (c) => c.charCodeAt(0)));
        else if (o.t === "end") end(o);
        else if (o.t === "log") console.log("[tubeless] sabr", o.run, o.msg); }
    });
    // one format of one video: its bytes as they arrive, [start, end) ranges, and [byte, ms] points to place a new fetch
    function track(id, index) {
      const k = id + "|" + index;
      let t = tracks.get(k);
      if (!t) tracks.set(k, (t = { id, index, len: 0, durMs: 0, chunks: [], ranges: [], points: [], headEnd: 0, run: null, wake: [] }));
      return t;
    }
    function add(t, a, b) {
      const all = [...t.ranges, [a, b]].sort((x, y) => x[0] - y[0]), out = [];
      for (const r of all) { const last = out[out.length - 1]; if (last && r[0] <= last[1]) last[1] = Math.max(last[1], r[1]); else out.push([...r]); }
      t.ranges = out;
    }
    const covered = (t, a, b) => t.ranges.some((r) => r[0] <= a && b <= r[1]);
    function piece(o, u8) {
      const r = runs.get(o.run);
      if (!r) return call("stop", o.run).catch(() => {});
      const t = r.track, at = o.init ? 0 : o.at;
      t.chunks.push([at, u8.slice()]);
      add(t, at, at + u8.length);
      if (o.init) t.headEnd = Math.max(t.headEnd, u8.length);
      if (o.ms >= 0 && !o.init) t.points.push([at, o.ms]);
      r.head = Math.max(r.head, at + u8.length);
      t.wake.splice(0).forEach((f) => f());
    }
    function end(o) {
      const r = runs.get(o.run);
      if (!r) return;
      runs.delete(o.run);
      if (r.track.run === r) r.track.run = null;
      r.track.failed = o.bad ? "refused" : o.error || null;
      r.track.wake.splice(0).forEach((f) => f());
    }
    function timeOf(t, n) {
      let lo = [t.headEnd, 0], hi = [Math.max(t.len, 1), t.durMs];
      for (const p of t.points) { if (p[0] <= n && p[0] >= lo[0]) lo = p; if (p[0] >= n && p[0] <= hi[0]) hi = p; }
      return Math.max(0, Math.round(lo[1] + (hi[1] - lo[1]) * ((n - lo[0]) / Math.max(1, hi[0] - lo[0]))) - BACK_MS);
    }
    function startRun(t, n) {
      if (t.run) { runs.delete(t.run.id); call("stop", t.run.id).catch(() => {}); }
      const r = { id: ++runIds, track: t, head: n };
      runs.set(r.id, r);
      t.run = r; t.failed = null;
      call("start", r.id, t.id, t.index, n <= t.headEnd ? 0 : timeOf(t, n)).catch(() => {});
      call("allow", r.id, n + AHEAD).catch(() => {});
    }
    // bytes [a, b] of a format, as soon as they are here (Shaka's sabr: scheme)
    async function range(id, index, a, b) {
      const t = track(id, index);
      b = Math.min(b, t.len - 1);
      for (let i = 0; ; i++) {
        if (covered(t, a, b + 1)) {
          const out = new Uint8Array(b - a + 1);
          for (const [at, u8] of t.chunks) {
            const s = Math.max(a, at), e = Math.min(b + 1, at + u8.length);
            if (s < e) out.set(u8.subarray(s - at, e - at), s - a);
          }
          if (t.run) call("allow", t.run.id, b + AHEAD).catch(() => {});
          return out.buffer;
        }
        if (t.failed) throw new Error("SABR " + t.failed);
        // the fetch on its way reaches here soon, or a new one starts where the player is
        if (!t.run || t.run.head > a + (1 << 20) || a > t.run.head + (4 << 20)) startRun(t, a);
        else call("allow", t.run.id, b + AHEAD).catch(() => {});
        await new Promise((ok) => { t.wake.push(ok); setTimeout(ok, 5000); });
        if (i > 24) throw new Error("SABR timed out");
      }
    }
    shaka.net.NetworkingEngine.registerScheme("sabr", (uri, req) => {
      const [, id, index] = uri.match(/^sabr:\/\/([^/]+)\/(\d+)/);
      const m = /bytes=(\d+)-(\d*)/.exec(req.headers.Range || req.headers.range || "");
      const a = m ? +m[1] : 0, b = m && m[2] ? +m[2] : track(id, +index).len - 1;
      const p = range(id, +index, a, b).then((data) => ({ uri, originalUri: uri, data, headers: {}, fromCache: false }));
      return shaka.util.AbortableOperation.notAbortable(p.catch((e) => {
        throw new shaka.util.Error(shaka.util.Error.Severity.CRITICAL, shaka.util.Error.Category.NETWORK, shaka.util.Error.Code.HTTP_ERROR, uri, e.message);
      }));
    });
    return {
      async prepare(id, hl, gl) {
        await open();
        const text = await call("prepare", id, hl, gl);
        try {
          const d = JSON.parse(text);
          (d.streamingData?.adaptiveFormats || []).forEach((f, i) => {
            const t = track(id, i);
            t.len = +f.contentLength || t.len;
            t.durMs = +f.approxDurationMs || t.durMs;
          });
        } catch (e) { /* the Kotlin side reads the answer */ }
        return text;
      },
      async caption(id, url) { await open(); return await call("caption", id, url); },
      // drop what is kept for videos other than [keep] (the player moved on)
      forget(keep) { for (const [k, t] of tracks) if (t.id !== keep) { if (t.run) { runs.delete(t.run.id); call("stop", t.run.id).catch(() => {}); } tracks.delete(k); } },
    };
  })();

  // ------------------------------------------------------------------ downloads: googlevideo → OPFS, joined with Mediabunny on export
  const dl = (() => {
    const CHUNK = 10 << 20;
    async function dir(path, create) {
      let d = await navigator.storage.getDirectory();
      const parts = path.split("/").filter(Boolean), name = parts.pop();
      for (const p of parts) d = await d.getDirectoryHandle(p, { create });
      return [d, name];
    }
    async function file(path) { const [d, n] = await dir(path, false); return (await d.getFileHandle(n)).getFile(); }
    shaka.net.NetworkingEngine.registerScheme("opfs", (uri, req) => {
      const p = (async () => {
        const f = await file(decodeURIComponent(uri.slice("opfs://".length)));
        const m = /bytes=(\d+)-(\d*)/.exec(req.headers.Range || req.headers.range || "");
        const data = await (m ? f.slice(+m[1], m[2] ? +m[2] + 1 : f.size) : f).arrayBuffer();
        return { uri, originalUri: uri, data, headers: {}, fromCache: false };
      })();
      return shaka.util.AbortableOperation.notAbortable(p.catch((e) => {
        throw new shaka.util.Error(shaka.util.Error.Severity.CRITICAL, shaka.util.Error.Category.NETWORK, shaka.util.Error.Code.HTTP_ERROR, uri, e.message);
      }));
    });
    navigator.storage?.persist?.().catch(() => {});
    return {
      // [url] from byte [from] into the OPFS file [path] (appended), in 10 MB ranges; {status, have}
      async fetchTo(path, url, from, len, progress) {
        const [d, n] = await dir(path, true);
        const fh = await d.getFileHandle(n, { create: true });
        const w = await fh.createWritable({ keepExistingData: from > 0 });
        if (from > 0) await w.seek(from); else await w.truncate(0);
        let have = from;
        try {
          while (len <= 0 || have < len) {
            const to = len > 0 ? Math.min(len - 1, have + CHUNK - 1) : have + CHUNK - 1;
            // a server that stops sending (seen: 1 MB in, then nothing for minutes) is dropped after 20 s of
            // silence; the throw lands in OfflineWeb.fetch, which carries on from the bytes written
            const ac = new AbortController();
            let quiet;
            const wake = () => { clearTimeout(quiet); quiet = setTimeout(() => ac.abort(new Error("no data for 20 s")), 20000); };
            wake();
            let got = 0, r;
            try {
              dl.last = { path, at: have, phase: "fetch", t: Date.now() };
              r = await fetch(url, { headers: { Range: `bytes=${have}-${to}` }, credentials: "omit", signal: ac.signal });
              if (r.status !== 200 && r.status !== 206) { console.log(`[tubeless] download ${path}: HTTP ${r.status} at ${have}`); return { status: r.status, have }; }
              if (len <= 0) len = +(r.headers.get("Content-Range") || "").split("/").pop() || +r.headers.get("Content-Length") || 0;
              const rd = r.body.getReader();
              for (;;) {
                wake();
                dl.last = { path, at: have + got, phase: "read", t: Date.now() };
                const { done, value } = await rd.read();
                if (done) break;
                dl.last.phase = "write";
                await w.write(value);
                got += value.length;
                progress(have + got);
              }
            } catch (e) {
              console.log(`[tubeless] download ${path} stopped at ${have + got} of ${len}: ${e && e.message}`);
              throw e;
            } finally { clearTimeout(quiet); }
            have += got;
            if (r.status === 200 || got === 0) break;
          }
          return { status: 200, have };
        } finally { await w.close(); }
      },
      async writeText(path, text) { const [d, n] = await dir(path, true); const w = await (await d.getFileHandle(n, { create: true })).createWritable(); await w.write(text); await w.close(); },
      async readText(path) { return (await file(path)).text(); },
      async readBytes(path) { return (await file(path)).arrayBuffer(); },
      async size(path) { try { return (await file(path)).size; } catch (e) { return 0; } },
      // the first of [urls] that answers with a real picture, saved at [path]
      async saveFirst(path, urls) {
        for (const u of urls) {
          try {
            const r = await fetch(u, { credentials: "omit" });
            const b = r.ok ? await r.arrayBuffer() : null;
            if (b && b.byteLength > 2000) {
              const [d, n] = await dir(path, true);
              const w = await (await d.getFileHandle(n, { create: true })).createWritable();
              await w.write(b); await w.close();
              return true;
            }
          } catch (e) { /* the next one */ }
        }
        return false;
      },
      async exists(path) { try { await file(path); return true; } catch (e) { return false; } },
      async remove(path) { try { const [d, n] = await dir(path, false); await d.removeEntry(n, { recursive: true }); } catch (e) { /* gone already */ } },
      // the picture and the sound in one file (no re-encoding), handed to the browser's downloads
      async exportJoined(vPath, aPath, name) {
        const mb = await import("./vendor/mediabunny.min.mjs");
        const vIn = new mb.Input({ source: new mb.BlobSource(await file(vPath)), formats: mb.ALL_FORMATS });
        const aIn = new mb.Input({ source: new mb.BlobSource(await file(aPath)), formats: mb.ALL_FORMATS });
        const vt = await vIn.getPrimaryVideoTrack(), at = await aIn.getPrimaryAudioTrack();
        const webm = (await vIn.getMimeType()).includes("webm");
        const out = new mb.Output({ format: webm ? new mb.WebMOutputFormat() : new mb.Mp4OutputFormat({ fastStart: "in-memory" }), target: new mb.BufferTarget() });
        const vs = new mb.EncodedVideoPacketSource(await vt.getCodec()), as = new mb.EncodedAudioPacketSource(await at.getCodec());
        out.addVideoTrack(vs); out.addAudioTrack(as);
        await out.start();
        const vi = new mb.EncodedPacketSink(vt).packets()[Symbol.asyncIterator](), ai = new mb.EncodedPacketSink(at).packets()[Symbol.asyncIterator]();
        const vMeta = { decoderConfig: await vt.getDecoderConfig() }, aMeta = { decoderConfig: await at.getDecoderConfig() };
        let v = await vi.next(), a = await ai.next(), firstV = true, firstA = true;
        // interleaved by time, as a player reads them
        while (!v.done || !a.done) {
          if (!v.done && (a.done || v.value.timestamp <= a.value.timestamp)) { await vs.add(v.value, firstV ? vMeta : undefined); firstV = false; v = await vi.next(); }
          else { await as.add(a.value, firstA ? aMeta : undefined); firstA = false; a = await ai.next(); }
        }
        await out.finalize();
        const blob = new Blob([out.target.buffer], { type: webm ? "video/webm" : "video/mp4" });
        const el = document.createElement("a");
        el.href = URL.createObjectURL(blob); el.download = name + (webm ? ".webm" : ".mp4"); el.click();
        setTimeout(() => URL.revokeObjectURL(el.href), 60000);
        return blob.size;
      },
    };
  })();

  window.tubeless = { api, net, fs, auth, fonts, player, sabr, dl, rules, ready: Promise.all([rules(BASE_RULES), auth.refresh()]) };
})();
