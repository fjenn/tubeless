// The toolbar button opens the app (the Compose app page) in a new tab.
const api = globalThis.browser ?? globalThis.chrome;
api.action.onClicked.addListener(() => api.tabs.create({ url: api.runtime.getURL("app.html") }));
