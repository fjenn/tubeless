"""Annotated install-guide screenshots for site/guide (Tubeless orange rings, arrows, numbered badges, labels).
Capture: chrome.py takes window screenshots of a throwaway Chrome profile (puppeteer over CDP); ios.py takes the
ios/ UI test output. Font: common/fonts/RobotoVariable.ttf."""
"""Tubeless guide annotations: orange rings, arrows, numbered badges, label pills. Coordinates are in source pixels."""
import math
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ORANGE = (247, 151, 29)
WHITE = (255, 255, 255)
FONT = None

def font(size, weight=700):
    f = ImageFont.truetype(FONT, size)
    try: f.set_variation_by_axes([weight, 100])
    except Exception: pass
    return f

def _shadow(base, draw_fn, blur=6, alpha=90):
    sh = Image.new("RGBA", base.size, (0, 0, 0, 0)); draw_fn(ImageDraw.Draw(sh), (0, 0, 0, alpha))
    sh = sh.filter(ImageFilter.GaussianBlur(blur)); base.alpha_composite(sh, (0, 3))

def ring(im, box, s=1.0, pad=10, radius=None):
    x0, y0, x1, y1 = box; x0 -= pad * s; y0 -= pad * s; x1 += pad * s; y1 += pad * s
    r = radius if radius is not None else min(28 * s, (y1 - y0) / 2)
    w = max(4, round(6 * s))
    _shadow(im, lambda d, c: d.rounded_rectangle((x0, y0, x1, y1), r, outline=c, width=w + 2), blur=5 * s, alpha=70)
    d = ImageDraw.Draw(im); d.rounded_rectangle((x0, y0, x1, y1), r, outline=ORANGE + (255,), width=w)
    return (x0, y0, x1, y1)

def arrow(im, start, end, s=1.0, curve=0.0):
    """Arrow from start to end (tip at end), optional bend."""
    w = max(5, round(7 * s)); head = 26 * s
    (sx, sy), (ex, ey) = start, end
    mx, my = (sx + ex) / 2, (sy + ey) / 2
    nx, ny = -(ey - sy), (ex - sx); L = math.hypot(nx, ny) or 1
    cx, cy = mx + nx / L * curve * L, my + ny / L * curve * L
    pts = [((1 - t) ** 2 * sx + 2 * (1 - t) * t * cx + t * t * ex, (1 - t) ** 2 * sy + 2 * (1 - t) * t * cy + t * t * ey) for t in [i / 40 for i in range(41)]]
    ang = math.atan2(ey - pts[-4][1], ex - pts[-4][0])
    tip = (ex, ey); back = (ex - head * math.cos(ang), ey - head * math.sin(ang))
    left = (back[0] + head * 0.6 * math.sin(ang), back[1] - head * 0.6 * math.cos(ang))
    right = (back[0] - head * 0.6 * math.sin(ang), back[1] + head * 0.6 * math.cos(ang))
    shaft = [p for p in pts if math.hypot(p[0] - ex, p[1] - ey) > head * 0.8]
    def drawit(d, c):
        d.line(shaft, fill=c, width=w, joint="curve")
        d.polygon([tip, left, right], fill=c)
    _shadow(im, drawit, blur=5 * s, alpha=70)
    drawit(ImageDraw.Draw(im), ORANGE + (255,))

def badge(im, center, n, s=1.0):
    r = 24 * s; x, y = center
    _shadow(im, lambda d, c: d.ellipse((x - r - 3 * s, y - r - 3 * s, x + r + 3 * s, y + r + 3 * s), fill=c), blur=6 * s, alpha=90)
    d = ImageDraw.Draw(im)
    d.ellipse((x - r - 3 * s, y - r - 3 * s, x + r + 3 * s, y + r + 3 * s), fill=WHITE + (255,))
    d.ellipse((x - r, y - r, x + r, y + r), fill=ORANGE + (255,))
    f = font(round(30 * s)); d.text((x, y + 1 * s), str(n), font=f, fill=WHITE, anchor="mm")

def label(im, pos, text, s=1.0, anchor="lm"):
    """Orange pill with white text; pos is the anchor point (lm: left-middle, rm, mm, lt...)."""
    f = font(round(26 * s)); d = ImageDraw.Draw(im)
    l, t, r, b = d.textbbox((0, 0), text, font=f, anchor="lt")
    pw, ph = r - l + 32 * s, 52 * s
    x, y = pos
    if anchor[0] == "m": x -= pw / 2
    elif anchor[0] == "r": x -= pw
    if anchor[1] == "m": y -= ph / 2
    elif anchor[1] == "b": y -= ph
    box = (x, y, x + pw, y + ph)
    _shadow(im, lambda d2, c: d2.rounded_rectangle(box, ph / 2, fill=c), blur=6 * s, alpha=80)
    d = ImageDraw.Draw(im); d.rounded_rectangle(box, ph / 2, fill=ORANGE + (255,))
    d.text((x + pw / 2, y + ph / 2), text, font=f, fill=WHITE, anchor="mm")
    return box

def fill(im, box, color):
    ImageDraw.Draw(im).rectangle(box, fill=color)
