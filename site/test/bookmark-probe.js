// The probe bookmarklet body: what can a javascript: bookmark do on this page? Results land in window.__bm.
(async () => {
  const R = (window.__bm = { ran: true, href: location.href });
  const t = async (k, f) => { try { R[k] = await f(); } catch (e) { R[k] = "ERR " + (e && e.name) + ": " + String(e && e.message).slice(0, 120); } };
  await t("connect", async () => (await fetch("https://opena.tech/tubeless/web/version.txt", { credentials: "omit" })).status);
  let pol = null;
  await t("ttPolicy", () => { pol = trustedTypes.createPolicy("tubeless-probe" + Math.random(), { createScript: (s) => s, createScriptURL: (s) => s, createHTML: (s) => s }); return "ok"; });
  await t("evalPlain", () => eval("1+1"));
  await t("evalTT", () => eval(pol.createScript("2+2")));
  await t("fnTT", () => new Function(pol.createScript("return 3"))());
  await t("wasm", async () => { const m = await WebAssembly.compile(new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0])); return !!m; });
  await t("scriptSrc", () => new Promise((ok) => {
    const s = document.createElement("script");
    window.__bmS = 0;
    s.src = pol ? pol.createScriptURL("data:text/javascript,window.__bmS=1") : "data:text/javascript,window.__bmS=1";
    s.onload = () => ok("load " + window.__bmS); s.onerror = () => ok("error");
    document.head.appendChild(s); setTimeout(() => ok("timeout"), 3000);
  }));
  await t("blobImport", async () => { const u = URL.createObjectURL(new Blob(["export const x = 5"], { type: "text/javascript" })); return (await import(pol ? pol.createScriptURL(u) : u)).x; });
  await t("cookieNames", () => document.cookie.split(";").map((c) => c.split("=")[0].trim()).filter(Boolean).join(","));
  await t("browse", async () => {
    const r = await fetch("/youtubei/v1/browse?prettyPrint=false", { method: "POST", credentials: "include", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ context: { client: { clientName: "WEB", clientVersion: "2.20251120.01.00", hl: "en" } }, browseId: "FEwhat_to_watch" }) });
    const j = await r.json(); return r.status + " logged_in=" + (JSON.stringify(j.responseContext || {}).match(/"logged_in","value":"(\d)"/) || [])[1];
  });
  let audioUrl = null;
  await t("player", async () => {
    const r = await fetch("/youtubei/v1/player?prettyPrint=false", { method: "POST", credentials: "omit", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ context: { client: { clientName: "ANDROID", clientVersion: "20.10.38", androidSdkVersion: 30, hl: "en" } }, videoId: "aqz-KE-bpKQ" }) });
    const j = await r.json();
    const fs = j.streamingData?.adaptiveFormats || [];
    audioUrl = fs.find((f) => f.mimeType.startsWith("audio/mp4"))?.url; window.__bmAudio = audioUrl;
    return r.status + " " + (j.playabilityStatus?.status) + " formats=" + fs.length + " max=" + Math.max(0, ...fs.map((f) => f.height || 0));
  });
  await t("gvRead", async () => { const r = await fetch(audioUrl, { headers: { Range: "bytes=0-65535" }, credentials: "omit" }); return r.status + " bytes=" + (await r.arrayBuffer()).byteLength; });
  await t("blob", async () => { const r = await fetch(audioUrl, { headers: { Range: "bytes=0-1999999" }, credentials: "omit" }); window.__bmBlob = URL.createObjectURL(new Blob([await r.arrayBuffer()], { type: "audio/mp4" })); return r.status; });
  await t("webAudio", () => new Promise((ok) => {
    const a = document.createElement("audio");
    a.crossOrigin = "anonymous"; a.src = window.__bmBlob; a.muted = false; a.volume = 1;
    const ctx = new AudioContext(); const src = ctx.createMediaElementSource(a); const an = ctx.createAnalyser(); src.connect(an); an.connect(ctx.destination);
    let peak = 0; const buf = new Float32Array(2048);
    a.onerror = () => ok("media error " + (a.error && a.error.code));
    a.play().catch((e) => ok("play " + e.message));
    const iv = setInterval(() => { an.getFloatTimeDomainData(buf); for (const x of buf) peak = Math.max(peak, Math.abs(x)); }, 50);
    setTimeout(() => { clearInterval(iv); a.pause(); ok("peak=" + peak.toFixed(3) + " t=" + a.currentTime.toFixed(1) + " ctx=" + ctx.state); }, 5000);
  }));
  await t("robotsFrame", () => new Promise((ok) => {
    const f = document.createElement("iframe");
    f.src = "/robots.txt?tubeless-probe";
    f.onload = () => {
      try {
        const d = f.contentDocument, s = d.createElement("script");
        s.src = "data:text/javascript,parent.__bmF=1"; d.documentElement.appendChild(s);
        const s2 = d.createElement("script"); s2.textContent = "parent.__bmF2=eval('7')"; d.documentElement.appendChild(s2);
        setTimeout(() => ok("src=" + window.__bmF + " inline=" + window.__bmF2), 1000);
      } catch (e) { ok("ERR " + e.message); }
    };
    document.body.appendChild(f); setTimeout(() => ok("timeout"), 8000);
  }));
  R.done = true;
})();
