// Old "Tubeless" bookmarks load this file from GitHub Pages: the site moved to opena.tech, so load the real yt.js from
// there (it finds its base, opena.tech/tubeless/web/, from its own address). Re-add the bookmark from
// https://opena.tech/tubeless/web/install to skip this hop.
(function () {
  if (window.tubeless) return;
  var s = document.createElement("script");
  s.src = "https://opena.tech/tubeless/web/yt.js?" + Date.now();
  (document.head || document.documentElement).appendChild(s);
})();
