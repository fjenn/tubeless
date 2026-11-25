// Tubeless inside youtube.com: loaded by the bookmark (bookmarklet.js) into https://www.youtube.com/robots.txt, a
// youtube.com page without a Content-Security-Policy. Running as youtube.com, the app gets what only the extension had:
// the browser's own YouTube session (SAPISID is a script-readable cookie), readable googlevideo bytes (SABR, HD
// downloads into OPFS) and the video's sound for Web Audio (stable volume, voice boost). The extension's own host code
// (tubeless.js) runs unchanged; this file only stands in for the extension APIs it uses (globalThis.tubelessHost).
"use strict";
(() => {
  if (window.tubeless) return;
  const me = document.currentScript.src, B = me.slice(0, me.lastIndexOf("/") + 1);
  const cookie = (n) => { const m = ("; " + document.cookie).match(new RegExp("; " + n + "=([^;]*)")); return m ? decodeURIComponent(m[1]) : null; };
  const listeners = [];
  let last = cookie("SAPISID");
  setInterval(() => {
    const now = cookie("SAPISID");
    if (now !== last) { last = now; for (const f of listeners) f({ cookie: { domain: ".youtube.com", name: "SAPISID" } }); }
  }, 3000);
  globalThis.tubelessHost = {
    // header rules: none needed, this page is youtube.com (its own Origin and Referer go out by themselves)
    declarativeNetRequest: { updateSessionRules: async () => {} },
    cookies: {
      async get({ name }) { const v = cookie(name); return v == null ? null : { name, value: v }; },
      onChanged: { addListener: (f) => listeners.push(f) },
    },
    // the hidden SABR frame: what the extension's content scripts put there, put there by hand (same origin)
    sabrFrame(frame) {
      frame.addEventListener("load", () => {
        const d = frame.contentDocument;
        for (const f of ["sabr-page.js", "sabr-frame.js"]) {
          const s = d.createElement("script"); s.src = B + f; s.async = false; s.charset = "utf-8"; s.crossOrigin = "anonymous"; d.documentElement.appendChild(s);
        }
      }, { once: true });
    },
    host: "youtube.com",
  };

  // the page: robots.txt's text gives way to the app page (as extension/src/app.html); relative URLs go to the site
  const html = document.createElement("html");
  html.lang = "en";
  html.innerHTML = `<head><meta charset="utf-8"><base href="${B}"><meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Tubeless</title><link rel="icon" href="${B}icon-128.png"><meta name="theme-color" content="#0f0f0f">
<style>
  html, body { margin: 0; height: 100%; overflow: hidden; background: #0f0f0f; color: #aaa; font: 14px/1.4 Roboto, system-ui, -apple-system, sans-serif; }
  #tubeless-video { position: fixed; left: 0; top: 0; width: 0; height: 0; background: #000; z-index: 0; }
  body > :not(video):not(iframe) { position: relative; z-index: 1; }
  #tubeless-sabr { position: fixed; width: 1px; height: 1px; left: -10px; top: -10px; border: 0; opacity: 0; }
  #tubeless-loading { position: fixed; inset: 0; display: flex; align-items: center; justify-content: center; z-index: 0; }
</style></head><body><video id="tubeless-video" playsinline></video><div id="tubeless-loading">Loading Tubeless&hellip;</div></body>`;
  document.replaceChild(html, document.documentElement);
  const load = (src, module) => new Promise((ok, no) => {
    const s = document.createElement("script");
    if (module) s.type = "module";
    // robots.txt is not UTF-8 to the browser; GitHub Pages names the charset, a script's own attribute makes sure
    // CORS mode (Pages sends ACAO *): import() inside a cross-origin classic script resolves against it only then
    s.charset = "utf-8"; s.crossOrigin = "anonymous"; s.src = B + src; s.onload = ok; s.onerror = () => no(new Error("could not load " + src));
    document.head.appendChild(s);
  });
  load("vendor/shaka-player.compiled.js").then(() => load("tubeless.js")).then(() => load("app.js", true))
    .catch((e) => { document.getElementById("tubeless-loading").textContent = "Tubeless could not start: " + e.message; });
})();
