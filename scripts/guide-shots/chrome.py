"""Chrome + Finder guide images. python chrome.py SHOTS OUTDIR"""
import sys
from PIL import Image
import os; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ann
from ann import ring, arrow, badge, label, fill
ann.FONT = sys.argv[3]
S, OUT = sys.argv[1], sys.argv[2]

def load(name): return Image.open(f"{S}/{name}.png").convert("RGBA")
def blank_url(im, x1=760):
    c = im.getpixel((1200, 115)); fill(im, (300, 96, x1, 152), c)
def save(im, crop, name, width=1200):
    im = im.crop(crop)
    if im.width > width: im = im.resize((width, round(im.height * width / im.width)), Image.LANCZOS)
    im.convert("RGB").save(f"{OUT}/{name}.webp", "WEBP", quality=84, method=6)
    print(name, im.size)

for m in ("l", "d"):
    sfx = "" if m == "l" else "-dark"
    # 1: download button + the download bubble
    im = load(f"{m}_a1"); blank_url(im)
    s = 1.5
    ring(im, (572, 830, 1032, 926), s, radius=60); badge(im, (1100, 878), 1, s)
    label(im, (1150, 878), "Click", s, "lm")
    ring(im, (1420, 180, 2030, 290), s, pad=4, radius=24); badge(im, (1372, 236), 2, s)
    label(im, (1322, 236), "Tubeless.zip downloads", s, "rm")
    save(im, (430, 0, 2202, 1000), f"ext-1-download{sfx}")

    # 3: a new tab's address bar
    im = load(f"{m}_a3")
    s = 1.5
    ring(im, (242, 92, 2030, 161), s, pad=6)
    badge(im, (330, 300), 1, s); label(im, (380, 300), "Paste chrome://extensions here, press Enter", s, "lm")
    arrow(im, (420, 270), (420, 175), s)
    save(im, (0, 0, 2200, 640), f"ext-3-extensions-page{sfx}")

    # 4: Developer mode switch
    im = load(f"{m}_a4")
    s = 1.5
    ring(im, (1886, 196, 2186, 266), s, pad=6)
    badge(im, (1850, 330), 1, s)
    label(im, (1820, 330), "Switch on Developer mode", s, "rm")
    save(im, (0, 0, 2200, 620), f"ext-4-developer-mode{sfx}")

    # 5: Load unpacked
    im = load(f"{m}_a5")
    s = 1.5
    ring(im, (48, 308, 304, 377), s, radius=40); badge(im, (360, 343), 1, s)
    label(im, (410, 343), "Click Load unpacked", s, "lm")
    save(im, (0, 0, 1500, 600), f"ext-5-load-unpacked{sfx}")

    # 6: loaded; open it from the toolbar (stitched past the automation notice bar)
    im = load(f"{m}_a6")
    top = im.crop((0, 0, 2200, 174)); body = im.crop((0, 288, 2200, 1130))
    st = Image.new("RGBA", (2200, 174 + body.height)); st.paste(top, (0, 0)); st.paste(body, (0, 174))
    im = st; s = 1.5
    ring(im, (957, 693 - 288 + 174, 1757, 1108 - 288 + 174), s, pad=4, radius=20)
    label(im, (1790, 700), "Tubeless is added", s, "lm")
    ring(im, (1880, 100, 1926, 152), s, pad=8); badge(im, (1903, 420), 1, s)
    label(im, (1850, 420), "Click to open Tubeless", s, "rm")
    arrow(im, (1903, 378), (1903, 172), s)
    ring(im, (1954, 100, 2000, 152), s, pad=8, radius=16)
    save(im, (700, 0, 2200, 1000), f"ext-6-open{sfx}")

# 5b: the folder picker (macOS, light only): other folders in the owner's Documents are blanked
im = load("raw_dialog")
bg = im.getpixel((800, 1050))
row = im.crop((572, 905, 1040, 951))
fill(im, (560, 460, 1040, 1000), bg)
im.paste(row, (572, 465))
s = 1.5
ring(im, (572, 465, 1040, 511), s, pad=4, radius=14); badge(im, (640, 600), 1, s)
label(im, (690, 600), "Pick Tubeless", s, "lm")
ring(im, (1793, 1081, 1939, 1128), s, pad=6, radius=24); badge(im, (1740, 1104), 2, s)
label(im, (1700, 1104), "Click Select", s, "rm")
save(im, (556, 268, 2000, 1170), "ext-5b-pick-folder")

# 2: Finder: unzip, move the folder to Documents (light only)
im = load("raw_finder_l")
s = 1.2
ring(im, (610, 130, 775, 310), s, radius=24); badge(im, (810, 150), 1, s)
label(im, (850, 150), "Double-click Tubeless.zip", s, "lm")
ring(im, (395, 130, 540, 310), s, radius=24); badge(im, (468, 380), 2, s)
arrow(im, (430, 360), (260, 460), s, curve=-0.2)
label(im, (520, 470), "Drag the Tubeless folder to Documents", s, "lm")
save(im, (0, 0, 1840, 560), "ext-2-unzip")

for m in ("l", "d"):
    sfx = "" if m == "l" else "-dark"
    # desktop bookmark: show the bar, drag the button onto it
    im = load(f"{m}_d1"); blank_url(im)
    s = 1.5
    ring(im, (10, 166, 2190, 226), s, pad=2, radius=14); badge(im, (1700, 300), 1, s)
    label(im, (1660, 300), "Bookmarks bar", s, "rm")
    ring(im, (572, 608, 836, 692), s, radius=46); badge(im, (900, 650), 2, s)
    arrow(im, (700, 590), (700, 236), s, curve=0.0)
    label(im, (950, 650), "Hold, drag up, let go", s, "lm")
    save(im, (0, 0, 2200, 1120), f"bm-desktop-drag{sfx}")
    # on YouTube, click the bookmark
    im = load(f"{m}_d3")
    ring(im, (100, 172, 290, 222), s, pad=6, radius=22); badge(im, (360, 197), 1, s)
    label(im, (410, 197), "On YouTube, click Tubeless", s, "lm")
    save(im, (0, 0, 1500, 520), f"bm-desktop-click{sfx}")
