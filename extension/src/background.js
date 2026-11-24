// The toolbar button opens the app (the Compose app page) in a new tab.
const api = globalThis.browser ?? globalThis.chrome;
api.action.onClicked.addListener(() => api.tabs.create({ url: api.runtime.getURL("app.html") }));
// the Tubeless website (site.js) hands over to the app page: same tab, same video or search
api.runtime.onMessage.addListener((m, sender) => {
  if (m?.k !== "open-full" || !sender.tab?.id) return;
  const q = typeof m.search === "string" && /^\?[\w=&%.\-+]*$/.test(m.search) ? m.search : "";
  api.tabs.update(sender.tab.id, { url: api.runtime.getURL("app.html") + q });
});
