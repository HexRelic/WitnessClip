# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 HexRelic
# Renders design/icon.svg as the 512x512 store icon (fastlane/metadata/android/en-US/images/icon.png).
# Needs Pillow. Usage: python3 tools/render-icon.py fastlane/metadata/android/en-US/images/icon.png
import sys
from PIL import Image, ImageDraw, ImageChops

out = sys.argv[1]
S = 2048            # draw at 4x, downscale to 512 for anti-aliasing
k = S / 108         # SVG viewBox units -> pixels

def hexc(h): return tuple(int(h[i:i+2], 16) for i in (1, 3, 5))
def lerp(a, b, t): return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))

def gradient(c0, c1, diagonal):
    g = Image.new("RGB", (S, S))
    px = g.load()
    a, b = hexc(c0), hexc(c1)
    for y in range(S):
        if not diagonal:
            row = lerp(a, b, y / (S - 1))
        for x in range(S):
            px[x, y] = lerp(a, b, (x + y) / (2 * (S - 1))) if diagonal else row
    return g

# background: rounded square, diagonal gradient
bg = gradient("#0E2A47", "#071421", True)
mask = Image.new("L", (S, S), 0)
ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=24 * k, fill=255)
img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
img.paste(bg, (0, 0), mask)

# shield outline (cubic Béziers sampled), vertical gradient stroke
def cubic(p0, p1, p2, p3, n=64):
    return [tuple((1-t)**3*p0[i] + 3*(1-t)**2*t*p1[i] + 3*(1-t)*t**2*p2[i] + t**3*p3[i] for i in range(2))
            for t in (j / n for j in range(1, n + 1))]
pts = [(54, 20), (82, 30), (82, 52)]
pts += cubic((82, 52), (82, 70), (70.5, 82.5), (54, 89))
pts += cubic((54, 89), (37.5, 82.5), (26, 70), (26, 52))
pts += [(26, 30), (54, 20), (82, 30)]   # close, and repeat first edge so the joint is rounded
pts = [(x * k, y * k) for x, y in pts]
w = 5 * k
smask = Image.new("L", (S, S), 0)
d = ImageDraw.Draw(smask)
d.line(pts, fill=255, width=round(w))
for x, y in pts:                         # a round joint at every point: smooth curve, round corners
    d.ellipse([x - w / 2, y - w / 2, x + w / 2, y + w / 2], fill=255)
img.paste(gradient("#5EEAD4", "#14B8A6", False), (0, 0), smask)

# audio bars, round caps
d = ImageDraw.Draw(img)
w = 4.5 * k
for x, y0, y1 in [(38, 49, 57), (46, 43, 63), (54, 36, 70), (62, 44, 62), (70, 49, 57)]:
    x, y0, y1 = x * k, y0 * k, y1 * k
    d.line([(x, y0), (x, y1)], fill=hexc("#F8FAFC"), width=round(w))
    for y in (y0, y1):
        d.ellipse([x - w / 2, y - w / 2, x + w / 2, y + w / 2], fill=hexc("#F8FAFC"))

img.resize((512, 512), Image.LANCZOS).save(out, optimize=True)
print("written", out)
