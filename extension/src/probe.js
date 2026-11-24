// S8 spike: InnerTube `player` from this extension page with a token-free client (VISIONOS, then
// ANDROID_VR), its plain adaptive URLs fed to a <video> through Media Source Extensions.
// - no CORS: host_permissions cover youtube.com and googlevideo.com
// - anonymous: credentials "omit", so the user's youtube.com cookies are never sent
// - headers a page can't set (Origin, Referer, User-Agent) are rewritten by session DNR rules that
//   match only requests this extension makes (initiatorDomains = our id), never youtube.com's own
const CLIENTS = [
  { name: "VISIONOS", id: 101, version: "1.02",
    ua: "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
    extra: { deviceMake: "Apple", deviceModel: "RealityDevice17,1", osName: "visionOS", osVersion: "26.5.23O471" } },
  { name: "ANDROID_VR", id: 28, version: "1.65.10",
    ua: "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
    extra: { deviceMake: "Oculus", deviceModel: "Quest 3", androidSdkVersion: 32, osName: "Android", osVersion: "12L" } },
];
// ?c=ANDROID_VR: that client only; ?nodnr=1: no header rules (to see whether they are needed)
const params = new URLSearchParams(location.search);
if (params.get("c")) CLIENTS.splice(0, CLIENTS.length, ...CLIENTS.filter((c) => c.name === params.get("c")));
const WEB = { name: "WEB", id: 1, version: "2.20251120.01.00", ua: navigator.userAgent, extra: {} };

const out = document.getElementById("log");
const result = (window.__spike = { steps: [], ok: false });
function log(s) { result.steps.push(s); out.textContent += s + "\n"; console.log("[spike]", s); }

async function headerRules(ua) {
  if (params.get("nodnr")) return;
  const ids = [1, 2];
  const set = (h, v) => ({ header: h, operation: "set", value: v });
  await chrome.declarativeNetRequest.updateSessionRules({
    removeRuleIds: ids,
    addRules: [
      { id: 1, priority: 1,
        action: { type: "modifyHeaders", requestHeaders: [set("Origin", "https://www.youtube.com"), set("Referer", "https://www.youtube.com/"), set("User-Agent", ua)] },
        condition: { requestDomains: ["youtube.com"], initiatorDomains: [location.hostname], resourceTypes: ["xmlhttprequest"] } },
      { id: 2, priority: 1,
        action: { type: "modifyHeaders", requestHeaders: [set("User-Agent", ua)] },
        condition: { requestDomains: ["googlevideo.com"], initiatorDomains: [location.hostname], resourceTypes: ["xmlhttprequest", "media"] } },
    ],
  });
}

function context(c, visitor) {
  return { client: { clientName: c.name, clientVersion: c.version, hl: "en", gl: "US", userAgent: c.ua, ...(visitor ? { visitorData: visitor } : {}), ...c.extra } };
}

async function innertube(endpoint, c, visitor, body) {
  await headerRules(c.ua);
  const r = await fetch(`https://www.youtube.com/youtubei/v1/${endpoint}?prettyPrint=false`, {
    method: "POST", credentials: "omit",
    headers: { "Content-Type": "application/json", "X-YouTube-Client-Name": String(c.id), "X-YouTube-Client-Version": c.version, ...(visitor ? { "X-Goog-Visitor-Id": visitor } : {}) },
    body: JSON.stringify({ context: context(c, visitor), ...body }),
  });
  if (!r.ok) throw new Error(`${endpoint}: HTTP ${r.status}`);
  return r.json();
}

async function stream(id) {
  const t0 = performance.now();
  const visitor = (await innertube("visitor_id", WEB, null, {})).responseContext?.visitorData;
  log(`visitor_id: ${visitor ? "ok" : "none"} (${Math.round(performance.now() - t0)} ms)`);
  for (const c of CLIENTS) {
    const t = performance.now();
    const d = await innertube("player", c, visitor, { videoId: id, contentCheckOk: true, racyCheckOk: true });
    const status = d.playabilityStatus?.status;
    const formats = d.streamingData?.adaptiveFormats || [];
    log(`player ${c.name}: ${status}, ${formats.length} adaptive formats, plain URLs: ${formats.every((f) => f.url)} (${Math.round(performance.now() - t)} ms)`);
    if (status === "OK" && formats.length && formats.every((f) => f.url)) return { client: c, d, formats };
  }
  throw new Error("no token-free client answered");
}

// a <video> through MSE: one H.264 picture format up to 1080p and AAC sound, fetched in pieces with &range=
async function play(id) {
  result.ok = false;
  const { client, d, formats } = await stream(id);
  result.client = client.name;
  result.title = d.videoDetails?.title;
  const video = formats.filter((f) => f.mimeType.startsWith("video/mp4") && f.mimeType.includes("avc1") && f.height <= 1080).sort((a, b) => b.height - a.height || b.bitrate - a.bitrate)[0];
  const audio = formats.find((f) => f.itag === 140) || formats.find((f) => f.mimeType.startsWith("audio/mp4"));
  log(`"${result.title}" via ${client.name}: video itag ${video.itag} ${video.qualityLabel}, audio itag ${audio.itag}`);
  result.itags = [video.itag, audio.itag];
  await headerRules(client.ua);
  const v = document.getElementById("v");
  const ms = new MediaSource();
  v.src = URL.createObjectURL(ms);
  await new Promise((r) => ms.addEventListener("sourceopen", r, { once: true }));
  ms.duration = Number(d.videoDetails.lengthSeconds);
  const feeds = [video, audio].map((f) => feed(ms, v, f));
  v.addEventListener("playing", () => log(`playing (${v.videoWidth}x${v.videoHeight})`), { once: true });
  v.play().catch((e) => log("play(): " + e.message));
  await Promise.race(feeds);
}

const PIECE = 1 << 20, AHEAD_S = 30;
async function feed(ms, v, f) {
  const sb = ms.addSourceBuffer(f.mimeType);
  const len = Number(f.contentLength);
  for (let at = 0; at < len; ) {
    // stay up to 30 s ahead of the playhead
    while (sb.buffered.length && sb.buffered.end(sb.buffered.length - 1) - v.currentTime > AHEAD_S) await new Promise((r) => setTimeout(r, 500));
    const end = Math.min(at + PIECE, len) - 1;
    const r = await fetch(`${f.url}&range=${at}-${end}`, { credentials: "omit" });
    if (!r.ok) throw new Error(`itag ${f.itag} ${at}-${end}: HTTP ${r.status}`);
    const buf = await r.arrayBuffer();
    if (at === 0) log(`itag ${f.itag}: first piece ${buf.byteLength} bytes, HTTP ${r.status}`);
    await new Promise((res, rej) => { sb.addEventListener("updateend", res, { once: true }); sb.addEventListener("error", rej, { once: true }); sb.appendBuffer(buf); });
    at = end + 1;
  }
}

document.getElementById("f").addEventListener("submit", (e) => { e.preventDefault(); start(document.getElementById("id").value.trim()); });
function start(id) { play(id).catch((e) => { log("ERROR " + e.message); result.error = e.message; }); }
document.getElementById("v").addEventListener("timeupdate", (e) => {
  result.currentTime = e.target.currentTime;
  if (e.target.currentTime > 3 && !result.ok) { result.ok = true; log(`ok: ${e.target.currentTime.toFixed(1)} s played`); }
});
start(params.get("v") || "aqz-KE-bpKQ");
