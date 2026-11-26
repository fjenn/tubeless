"""iPhone Safari guide images from the UI test's screenshots + element frames (points; pixels = 3x).
python ios.py IOSDIR OUTDIR FONT PREFIX SUFFIX"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from PIL import Image
import ann
from ann import ring, arrow, badge, label
D, OUT, ann.FONT, P, SFX = sys.argv[1:6]
F = {}
for line in open(f"{D}/frames.txt"):
    k, v = line.split(); x, y, w, h = map(float, v.split(","))
    if k.startswith(P): F[k[len(P):]] = (x * 3, y * 3, (x + w) * 3, (y + h) * 3)
S = 2.0  # annotation scale on the 1206 px wide screenshots

def load(n): return Image.open(f"{D}/{P}{n}.png").convert("RGBA")
def save(im, y0, y1, name, width=600):
    im = im.crop((0, y0, im.width, y1)); im = im.resize((width, round(im.height * width / im.width)), Image.LANCZOS)
    im.convert("RGB").save(f"{OUT}/{name}{SFX}.webp", "WEBP", quality=84, method=6); print(name, im.size)
def mid(b): return ((b[0] + b[2]) / 2, (b[1] + b[3]) / 2)

# 1: copy
im = load("1b"); b = F["1b:copy"]
ring(im, b, S, radius=70); badge(im, (b[2] + 80, mid(b)[1]), 1, S); label(im, (b[2] + 140, mid(b)[1]), "Tap", S, "lm")
save(im, 900, 1950, "ios-1-copy")

# 2a: the page menu button left of the address
im = load("2a"); b = F["2a:menu"]
ring(im, b, S, pad=6, radius=80); badge(im, (mid(b)[0], b[1] - 190), 1, S)
label(im, (mid(b)[0] + 60, b[1] - 190), "Tap the menu button", S, "lm")
arrow(im, (mid(b)[0], b[1] - 140), (mid(b)[0], b[1] - 30), S)
save(im, 1500, 2622, "ios-2a-menu")

# 2b: Add Bookmark to...
im = load("2b"); b = F["2b:addto"]
ring(im, b, S, pad=6, radius=30); badge(im, (b[2] + 70, mid(b)[1]), 2, S)
save(im, 700, 2100, "ios-2b-add-bookmark")

# 2c: title Tubeless, Save
im = load("2c"); t = F["2c:title"]; s = F["2c:save"]
ring(im, (t[0], t[1], t[2], t[1] + 135), S, pad=8, radius=30); badge(im, (t[0] - 50, t[3] + 150), 1, S)
label(im, (t[0] + 10, t[3] + 150), "Type Tubeless", S, "lm")
ring(im, s, S, pad=8, radius=60); badge(im, (s[0] - 70, mid(s)[1] + 120), 2, S)
save(im, 120, 1000, "ios-2c-title-save")

# 3a: tap the address bar, then touch and hold Tubeless under Favorites
im = load("3a"); f = F["3a:fav"]; a = F["3a:addr"]
ring(im, a, S, pad=8); badge(im, (mid(a)[0], a[1] - 70), 1, S)
ring(im, f, S, pad=10, radius=40); badge(im, (f[2] + 60, mid(f)[1]), 2, S)
label(im, (f[2] + 120, mid(f)[1]), "Touch and hold", S, "lm")
save(im, 120, 1720, "ios-3a-hold-favorite")

# 3b: Edit
im = load("3b"); b = F["3b:edit"]
ring(im, b, S, pad=6, radius=30); badge(im, (b[2] + 80, mid(b)[1]), 3, S)
save(im, 1300, 2622, "ios-3b-edit")

# 3c-3e: clear the address, paste, Save
if "3d:paste" in F:
    im = load("3d"); a = F["3d:addrfield"]; p = F["3d:paste"]
    ring(im, a, S, pad=8, radius=30); badge(im, (a[0] - 50, mid(a)[1]), 1, S)
    ring(im, p, S, pad=8, radius=30); badge(im, (p[2] + 60, mid(p)[1]), 2, S)
    label(im, (p[2] + 120, mid(p)[1]), "Paste", S, "lm")
    save(im, 120, 1100, "ios-3c-paste")
if "3e:save" in F:
    im = load("3e"); a = F["3e:addrfield"]; s = F["3e:save"]
    ring(im, a, S, pad=8, radius=30)
    label(im, (a[0] + 40, a[3] + 90), "Address pasted", S, "lm")
    ring(im, s, S, pad=8, radius=60); badge(im, (s[0] - 70, mid(s)[1] + 120), 3, S)
    save(im, 120, 1000, "ios-3d-save")

# 4: Open YouTube for Tubeless -> plain text page
if "4a:go" in F:
    im = load("4a"); g = F["4a:go"]
    ring(im, g, S, pad=8, radius=80); badge(im, (min(g[2] + 70, 1140), g[1] - 70), 1, S)
    save(im, max(0, int(g[1]) - 900), min(2622, int(g[3]) + 300), "ios-4a-open-youtube")
    im = load("4b"); label(im, (603, 900), "This plain text page is expected", S, "mm")
    save(im, 0, 1400, "ios-4b-youtube-text")
# 5: address bar, tap Tubeless
if "5a:fav" in F:
    im = load("5a"); f = F["5a:fav"]
    ring(im, f, S, pad=10, radius=40); badge(im, (f[2] + 60, mid(f)[1]), 1, S)
    label(im, (f[2] + 120, mid(f)[1]), "Tap Tubeless", S, "lm")
    save(im, 120, 1720, "ios-5-run")
if os.path.exists(f"{D}/{P}5b.png"):
    im = load("5b"); save(im, 0, 700, "ios-6-tubeless")
