"""Mac Safari guide images (safaridriver window captures). python safari.py SHOTS OUT FONT
The automation window's orange address bar is repainted in Safari's normal grey so it doesn't read as an annotation."""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from PIL import Image, ImageDraw
import ann
from ann import ring, badge, label
S, OUT, ann.FONT = sys.argv[1:4]

def plain_address_bar(im, box=(655, 14, 1545, 90)):
    px = im.load(); x0, y0, x1, y1 = box
    inner = (x0 + 4, y0 + 4, x1 - 4, y1 - 4)
    mask = Image.new("L", im.size, 0); ImageDraw.Draw(mask).rounded_rectangle(inner, (y1 - y0) / 2 - 4, fill=255); m = mask.load()
    bg, fg = (236, 236, 238), (40, 40, 42)
    for y in range(y0 - 4, y1 + 4):
        for x in range(x0 - 4, x1 + 4):
            r, g, b, a = px[x, y]
            if m[x, y]:  # inside: orange -> grey, white text -> dark text
                t = max(0.0, min(1.0, (b - 70) / (255 - 70)))
                px[x, y] = tuple(round(bg[i] + (fg[i] - bg[i]) * t) for i in range(3)) + (255,)
            else:  # the antialiased edge: fade the orange tint to grey
                w = max(0.0, min(1.0, (r - b) / 180))
                px[x, y] = tuple(round(c * (1 - w) + bg[i] * w) for i, c in enumerate((r, g, b))) + (255,)

def save(im, crop, name, width=1200):
    im = im.crop(crop); im = im.resize((width, round(im.height * width / im.width)), Image.LANCZOS)
    im.convert("RGB").save(f"{OUT}/{name}.webp", "WEBP", quality=84, method=6); print(name, im.size)

s = 1.5
im = Image.open(f"{S}/m1.png").convert("RGBA"); plain_address_bar(im)
ring(im, (572, 692, 932, 776), s, radius=60); badge(im, (1000, 734), 1, s)
label(im, (1050, 734), "Click: the bookmark is copied", s, "lm")
save(im, (300, 0, 2000, 1000), "safari-1-copy")

im = Image.open(f"{S}/m4.png").convert("RGBA"); plain_address_bar(im)
label(im, (1300, 400), "This plain text page is expected", s, "mm")
save(im, (0, 0, 2200, 820), "safari-4-youtube-text")
