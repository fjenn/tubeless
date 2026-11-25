// The "Tubeless" bookmark (site/build.mjs turns this into the javascript: address on youtube.html; __BASE__ = the site).
// On www.youtube.com/robots.txt (a youtube.com page with no Content-Security-Policy) it loads yt.js there; on any other
// www.youtube.com page it opens that page in a frame over YouTube (same origin) and loads yt.js in the frame; anywhere
// else it goes to YouTube (click the bookmark once more there).
(function () {
  var B = "__BASE__", L = location, h = L.hostname;
  function go(d) { var s = d.createElement("script"); s.src = B + "yt.js?" + Date.now(); (d.head || d.documentElement).appendChild(s); }
  if (h == "www.youtube.com" && L.pathname == "/robots.txt") return go(document);
  // elsewhere: to YouTube itself (Safari, whose bookmarks obey YouTube's page rules, to the plain page that has none);
  // click the bookmark once more there
  if (h != "www.youtube.com") { L.href = /^((?!chrome|android|crios|fxios|edg).)*safari/i.test(navigator.userAgent) ? "https://www.youtube.com/robots.txt?tubeless" : "https://www.youtube.com/"; return; }
  if (document.getElementById("tubeless-over")) return;
  var q = "", m;
  if ((m = /[?&]v=([\w-]{11})/.exec(L.search)) || (m = /^\/(?:shorts|live)\/([\w-]{11})/.exec(L.pathname))) q = "&v=" + m[1];
  else if ((m = /[?&]search_query=([^&]+)/.exec(L.search))) q = "&q=" + m[1];
  var f = document.createElement("iframe");
  f.id = "tubeless-over";
  f.src = "/robots.txt?tubeless" + q;
  f.allow = "fullscreen; autoplay; picture-in-picture; clipboard-write; encrypted-media";
  f.style.cssText = "position:fixed;inset:0;width:100vw;height:100vh;border:0;margin:0;z-index:2147483647;background:#0f0f0f";
  f.onload = function () { go(f.contentDocument); f.focus(); };
  document.querySelectorAll("video,audio").forEach(function (v) { try { v.pause(); } catch (e) {} });
  document.documentElement.appendChild(f);
  document.title = "Tubeless";
  // YouTube's own page underneath stops drawing and playing
  setTimeout(function () { var a = document.querySelector("ytd-app, ytm-app, #page"); if (a) a.remove(); document.title = "Tubeless"; document.querySelectorAll("video,audio").forEach(function (v) { try { v.pause(); v.removeAttribute("src"); v.load(); } catch (e) {} }); }, 300);
})();
