// On the Tubeless website only: tells the page the extension is here and, when the page asks, opens the
// extension's own app page in this tab (Full mode: sign-in, SABR HD, downloads). Nothing else is read or sent.
"use strict";
(() => {
  const api = globalThis.browser ?? globalThis.chrome;
  window.addEventListener("message", (e) => {
    if (e.source !== window || !e.data) return;
    if (e.data.k === "tubeless-ext-ping") window.postMessage({ k: "tubeless-ext-here", version: api.runtime.getManifest().version }, location.origin);
    else if (e.data.k === "tubeless-open-full") {
      const q = typeof e.data.search === "string" && /^\?[\w=&%.\-+]*$/.test(e.data.search) ? e.data.search : "";
      api.runtime.sendMessage({ k: "open-full", search: q });
    }
  });
})();
