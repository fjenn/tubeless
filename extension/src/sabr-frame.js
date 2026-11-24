// In the hidden https://www.youtube.com/robots.txt?tubeless frame, YouTube's page world, after
// sabr-page.js (common/sabr): connects window.__thSabr to the extension page that framed it.
// Calls come in as {k:"call", id, fn, args}; answers, pieces and ends go out to the parent.
(() => {
  if (window.top === window || !window.__thSabr) return;
  const up = (m, t) => window.parent.postMessage(m, "*", t || []);
  window.__thHost = (json) => up({ k: "tubeless-sabr", json });
  window.__thBin = { postMessage: (buf) => up({ k: "tubeless-sabr-bin", buf }, [buf]) };
  window.addEventListener("message", async (e) => {
    if (e.source !== window.parent || !e.data || e.data.k !== "tubeless-call") return;
    const { id, fn, args } = e.data;
    try { up({ k: "tubeless-ret", id, r: await window.__thSabr[fn](...args) }); }
    catch (x) { up({ k: "tubeless-ret", id, err: String(x && x.message || x) }); }
  });
  up({ k: "tubeless-sabr-ready" });
})();
