# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 HexRelic
# Renders the store art in fastlane/metadata/android/en-US/images/: the 1024x500 feature
# graphic and the 1080x1920 phone screenshots, from the raw screenshots in design/screens/
# and the store icon. Needs Pillow, numpy, the Inter font (fonts-inter) and DejaVu Sans Mono
# (fonts-dejavu-core). Run from the project root: python3 tools/render-store-art.py
import hashlib
import math
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = "fastlane/metadata/android/en-US/images"
SHOTS = "design/screens"
K = 2  # draw at 2x, downscale for anti-aliasing
INTER = "/usr/share/fonts/opentype/inter/"
MONO = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"
MONO_B = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf"

# palette from app/src/main/kotlin/.../ui/Theme.kt and design/icon.svg
NAVY0, NAVY1 = "#0E2A47", "#061019"
MINT, TEAL, TEAL_D = "#5EEAD4", "#2DD4BF", "#0D9488"
WHITE, MUTED, SOFT = "#F8FAFC", "#8FA3B8", "#C9D6E3"
CARD_D, LINE_D, TEAL_BG_D = "#0F1A27", "#2A3F55", "#0F3B3A"
INK, INK_M, LINE_L, TEAL_BG_L = "#0B1826", "#5A6B7C", "#D5DEE7", "#D9F4F0"


def F(name, size): return ImageFont.truetype(name if name.startswith("/") else INTER + name, round(size * K))
def C(h, a=255): return (int(h[1:3], 16), int(h[3:5], 16), int(h[5:7], 16), a)
def S(*v): return [round(x * K) for x in v]


def gradient(w, h, c0, c1, mode="diag"):
    a, b = np.array(C(c0), float), np.array(C(c1), float)
    y, x = np.mgrid[0:h, 0:w]
    fx, fy = x / max(w - 1, 1), y / max(h - 1, 1)
    t = {"diag": fx * 0.6 + fy * 0.4, "vert": fy, "horiz": fx}[mode]
    return Image.fromarray((a + (b - a) * t[..., None]).astype(np.uint8), "RGBA")


class Art:
    def __init__(self, w, h, dark):
        self.w, self.h, self.dark = w, h, dark
        self.img = gradient(w * K, h * K, NAVY0, NAVY1) if dark else gradient(w * K, h * K, "#E3F5F1", "#F5F8FB", "vert")

    @property
    def d(self): return ImageDraw.Draw(self.img)
    def layer(self): return Image.new("RGBA", self.img.size, (0, 0, 0, 0))
    def ink(self): return WHITE if self.dark else INK
    def muted(self): return MUTED if self.dark else INK_M
    def teal(self): return TEAL if self.dark else TEAL_D

    def glow(self, cx, cy, r, color, alpha):
        x0, y0 = max(0, round((cx - r) * K)), max(0, round((cy - r) * K))
        x1, y1 = min(self.img.width, round((cx + r) * K)), min(self.img.height, round((cy + r) * K))
        if x1 <= x0 or y1 <= y0:
            return
        y, x = np.mgrid[y0:y1, x0:x1]
        dist = np.sqrt((x - cx * K) ** 2 + (y - cy * K) ** 2) / (r * K)
        a = (np.clip(1 - dist, 0, 1) ** 2 * alpha * 255).astype(np.uint8)
        g = Image.new("RGBA", (x1 - x0, y1 - y0), C(color))
        g.putalpha(Image.fromarray(a, "L"))
        self.img.alpha_composite(g, (x0, y0))

    def ring(self, cx, cy, r, width, color, alpha):
        l = self.layer()
        ImageDraw.Draw(l).ellipse(S(cx - r, cy - r, cx + r, cy + r), outline=C(color, round(alpha * 255)), width=round(width * K))
        self.img.alpha_composite(l)

    def disc(self, cx, cy, r, color, alpha):
        l = self.layer()
        ImageDraw.Draw(l).ellipse(S(cx - r, cy - r, cx + r, cy + r), fill=C(color, round(alpha * 255)))
        self.img.alpha_composite(l)

    def card(self, x0, y0, x1, y1, r, fill=None, outline=None, shadow=0.45, blur=26, dy=18):
        fill = fill or (C(CARD_D) if self.dark else C("#FFFFFF"))
        outline = outline or (C(LINE_D) if self.dark else C(LINE_L))
        if shadow:
            l = self.layer()
            col = (0, 0, 0, round(255 * shadow)) if self.dark else (11, 40, 60, round(255 * shadow * 0.45))
            ImageDraw.Draw(l).rounded_rectangle(S(x0 + 4, y0 + dy, x1 + 4, y1 + dy), radius=r * K, fill=col)
            self.img.alpha_composite(l.filter(ImageFilter.GaussianBlur(blur * K)))
        l = self.layer()
        ImageDraw.Draw(l).rounded_rectangle(S(x0, y0, x1, y1), radius=r * K, fill=fill, outline=outline, width=round(1.5 * K))
        self.img.alpha_composite(l)

    def text(self, x, y, s, font, fill, anchor="la"):
        self.d.text(S(x, y), s, font=font, fill=C(fill) if isinstance(fill, str) else fill, anchor=anchor)

    def gtext(self, x, y, s, font, c0, c1, anchor="la"):
        m = Image.new("L", self.img.size, 0)
        md = ImageDraw.Draw(m)
        md.text(S(x, y), s, font=font, fill=255, anchor=anchor)
        bx = md.textbbox(S(x, y), s, font=font, anchor=anchor)
        g = Image.new("RGBA", self.img.size, (0, 0, 0, 0))
        g.paste(gradient(bx[2] - bx[0] + 1, bx[3] - bx[1] + 1, c0, c1, "horiz"), (bx[0], bx[1]))
        self.img.paste(g, (0, 0), m)

    def tlen(self, s, font): return self.d.textlength(s, font=font) / K

    def stroke(self, pts, width, color):
        pts = [(round(x * K), round(y * K)) for x, y in pts]
        w = round(width * K)
        d = self.d
        d.line(pts, fill=C(color), width=w, joint="curve")
        for x, y in (pts[0], pts[-1]):
            d.ellipse([x - w / 2, y - w / 2, x + w / 2, y + w / 2], fill=C(color))

    def check(self, cx, cy, size, color, width):
        u = size / 10
        self.stroke([(cx - 3.6 * u, cy + 0.2 * u), (cx - 1 * u, cy + 2.8 * u), (cx + 3.8 * u, cy - 2.6 * u)], width, color)

    def badge(self, cx, cy, r, bg, fg):
        self.d.ellipse(S(cx - r, cy - r, cx + r, cy + r), fill=C(bg))
        self.check(cx, cy, r * 1.15, fg, r * 0.22)

    def globe(self, cx, cy, r, bg, fg):
        self.d.ellipse(S(cx - r, cy - r, cx + r, cy + r), fill=C(bg))
        g, w = r * 0.58, round(r * 0.13 * K)
        d = self.d
        d.ellipse(S(cx - g, cy - g, cx + g, cy + g), outline=C(fg), width=w)
        d.ellipse(S(cx - g * 0.45, cy - g, cx + g * 0.45, cy + g), outline=C(fg), width=w)
        d.line(S(cx - g, cy, cx + g, cy), fill=C(fg), width=w)

    def shield(self, cx, cy, size, color, width, tick=True):
        k = size / 62
        def P(x, y): return (cx + (x - 54) * k, cy + (y - 54.5) * k)
        def bez(p0, p1, p2, p3):
            return [tuple((1 - t) ** 3 * p0[j] + 3 * (1 - t) ** 2 * t * p1[j] + 3 * (1 - t) * t ** 2 * p2[j] + t ** 3 * p3[j]
                          for j in (0, 1)) for t in (i / 24 for i in range(1, 25))]
        pts = [P(54, 20), P(82, 30), P(82, 52)]
        pts += bez(P(82, 52), P(82, 70), P(70.5, 82.5), P(54, 89))
        pts += bez(P(54, 89), P(37.5, 82.5), P(26, 70), P(26, 52))
        pts += [P(26, 30), P(54, 20)]
        self.stroke(pts, width, color)
        if tick:
            self.check(cx, cy + size * 0.04, size * 0.5, color, width)

    def wave(self, x0, x1, cy, maxh, step, width, color, alpha, seed=0.0):
        l = self.layer()
        ld = ImageDraw.Draw(l)
        w = round(width * K)
        for i, x in enumerate(np.arange(x0, x1, step)):
            amp = 0.5 + 0.5 * math.sin(i * 0.21 + seed) * math.sin(i * 0.053 + 1.3 + seed)
            h = maxh * 0.1 + maxh * 0.9 * abs(amp) * (0.55 + 0.45 * math.sin(i * 0.9 + seed) ** 2)
            a = alpha(x) if callable(alpha) else alpha
            col = C(color, round(255 * a))
            p0, p1 = (round(x * K), round((cy - h / 2) * K)), (round(x * K), round((cy + h / 2) * K))
            ld.line([p0, p1], fill=col, width=w)
            for px, py in (p0, p1):
                ld.ellipse([px - w / 2, py - w / 2, px + w / 2, py + w / 2], fill=col)
        self.img.alpha_composite(l)

    def phone(self, shot, height, cx, cy, angle=0.0, dim=0.0):
        sc = Image.open(f"{SHOTS}/{shot}").convert("RGBA")
        sh = round(height * K)
        sw = round(sh * sc.width / sc.height)
        sc = sc.resize((sw, sh), Image.LANCZOS)
        b, r = round(height * 0.016 * K), height * 0.042
        ph = Image.new("RGBA", (sw + 2 * b, sh + 2 * b), (0, 0, 0, 0))
        pd = ImageDraw.Draw(ph)
        pd.rounded_rectangle([0, 0, ph.width - 1, ph.height - 1], radius=round((r + height * 0.016) * K), fill=C("#0A121C"))
        pd.rounded_rectangle([K, K, ph.width - 1 - K, ph.height - 1 - K], radius=round((r + height * 0.016) * K) - K,
                             outline=C("#2A3F55"), width=K)
        m = Image.new("L", sc.size, 0)
        ImageDraw.Draw(m).rounded_rectangle([0, 0, sw - 1, sh - 1], radius=round(r * K), fill=255)
        ph.paste(sc, (b, b), m)
        if dim:
            shade = Image.new("RGBA", ph.size, (6, 16, 25, 0))
            shade.putalpha(Image.fromarray((np.array(ph.getchannel("A")) * dim).astype(np.uint8)))
            ph = Image.alpha_composite(ph, shade)
        rot = ph.rotate(angle, resample=Image.BICUBIC, expand=True)
        x, y = round(cx * K - rot.width / 2), round(cy * K - rot.height / 2)
        shadow = Image.new("RGBA", rot.size, (0, 0, 0, 0))
        shadow.putalpha(rot.getchannel("A").point(lambda v: v * (0.55 if self.dark else 0.28)))
        shadow = shadow.filter(ImageFilter.GaussianBlur(height * 0.035 * K))
        l = self.layer()
        off = round(height * 0.02 * K)
        l.paste(shadow, (x + off // 2, y + off), shadow)
        l.paste(rot, (x, y), rot)
        self.img.alpha_composite(l)

    def save(self, path, fmt="PNG"):
        out = self.img.convert("RGB").resize((self.w, self.h), Image.LANCZOS)
        out.save(path, optimize=True) if fmt == "PNG" else out.save(path, quality=92, optimize=True, progressive=True)
        print(path)


# ---------- portrait screenshots ----------

def portrait(dark):
    a = Art(1080, 1920, dark)
    if dark:
        a.glow(540, 1250, 700, "#14B8A6", 0.30)
        a.glow(80, 80, 600, "#1E4E7A", 0.40)
        a.ring(1010, 1720, 420, 46, TEAL, 0.06)
        a.ring(40, 560, 260, 30, TEAL, 0.05)
    else:
        a.disc(980, 1700, 520, TEAL, 0.16)
        a.disc(-40, 640, 300, TEAL, 0.10)
        a.ring(980, 1700, 640, 40, TEAL, 0.08)
    return a


def headline(a, l1, l2, sub):
    size = 96
    while max(a.tlen(l1, F("InterDisplay-Bold.otf", size)), a.tlen(l2, F("InterDisplay-Bold.otf", size))) > 960:
        size -= 2
    f = F("InterDisplay-Bold.otf", size)
    a.text(540, 190, l1, f, a.ink(), "mm")
    a.gtext(540, 300, l2, f, *((MINT, "#14B8A6") if a.dark else ("#14B8A6", "#0F766E")), "mm")
    fs = F("Inter-Medium.otf", 38)
    for i, line in enumerate(sub.split("\n")):
        a.text(540, 410 + i * 54, line, fs, a.muted(), "mm")


def chip(a, x, y, label, right=False, h=84, icon="check"):
    f = F("Inter-SemiBold.otf", 32)
    w = a.tlen(label, f) + 34 + 52 + 30
    x0 = x - w if right else x
    a.card(x0, y, x0 + w, y + h, h / 2, shadow=0.5, blur=20, dy=14)
    (a.globe if icon == "globe" else a.badge)(x0 + 34 + 20, y + h / 2, 22, TEAL_BG_D if a.dark else TEAL_BG_L, a.teal())
    a.text(x0 + 34 + 52, y + h / 2, label, f, a.ink(), "lm")


def slide1():
    a = portrait(True)
    a.wave(0, 1080, 1180, 300, 16, 5, MINT, lambda x: 0.04 + 0.04 * abs(x - 540) / 540, 0.4)
    headline(a, "Recordings that", "prove themselves.",
             "Record a conversation and get a file\nthat proves it hasn't been touched.")
    a.phone("record.jpg", 1330, 540, 1240)
    chip(a, 1040, 720, "Lossless 24-bit FLAC", right=True)
    # sealed card
    x0, y0, x1, y1 = 46, 1300, 560, 1450
    a.card(x0, y0, x1, y1, 30, shadow=0.6)
    a.d.rounded_rectangle(S(x0 + 24, y0 + 24, x0 + 126, y0 + 126), radius=24 * K, fill=C(TEAL_BG_D))
    a.shield(x0 + 75, y0 + 75, 66, TEAL, 6)
    a.text(x0 + 152, y0 + 46, "Sealed", F("Inter-SemiBold.otf", 42), WHITE)
    a.text(x0 + 152, y0 + 98, "SHA-256 · RFC 3161 · eIDAS", F("Inter-Medium.otf", 26), MUTED)
    a.save(f"{OUT}/phoneScreenshots/1.png")


def slide2():
    a = portrait(False)
    headline(a, "Sealed every", "second.",
             "Each second is hashed and chained the\nmoment it is recorded. Change a single\nsample and the chain breaks.")
    # live waveform card
    a.card(90, 600, 990, 770, 34)
    a.d.rounded_rectangle(S(124, 640, 236, 690), radius=25 * K, fill=C("#FDE2E6"))
    a.d.ellipse(S(142, 657, 158, 673), fill=C("#E11D48"))
    a.text(168, 665, "REC", F("Inter-Bold.otf", 26), "#E11D48", "lm")
    a.text(124, 728, "00:00:06", F(MONO_B, 30), INK, "lm")
    a.wave(270, 960, 685, 120, 13, 6, TEAL_D, 0.9, 2.0)
    # chain of seconds
    f_t, f_h = F(MONO_B, 30), F(MONO, 27)
    a.stroke([(190, 770), (190, 1530)], 4, "#9ADFD5")
    for i in range(6):
        y = 806 + i * 124
        h = hashlib.sha256(f"witnessclip second {i + 1}".encode()).hexdigest()
        a.card(130, y, 950, y + 100, 26, shadow=0.35, blur=16, dy=10)
        a.text(170, y + 50, f"00:00:0{i + 1}", f_t, INK, "lm")
        a.text(372, y + 50, f"{h[:8]} {h[8:16]} … {h[-8:]}", f_h, INK_M, "lm")
        a.badge(904, y + 50, 22, TEAL_BG_L, TEAL_D)
    # signature card
    x0, y0, x1, y1 = 90, 1594, 990, 1806
    a.card(x0, y0, x1, y1, 34, fill=C(INK), outline=C(INK), shadow=0.8)
    a.d.rounded_rectangle(S(x0 + 34, y0 + 46, x0 + 154, y0 + 166), radius=28 * K, fill=C(TEAL_BG_D))
    a.shield(x0 + 94, y0 + 106, 80, TEAL, 7)
    a.text(x0 + 186, y0 + 62, "Signed every 10 seconds", F("Inter-SemiBold.otf", 40), WHITE)
    a.text(x0 + 186, y0 + 116, "by a key locked inside the phone's", F("Inter-Medium.otf", 29), MUTED)
    a.text(x0 + 186, y0 + 154, "secure hardware", F("Inter-Medium.otf", 29), MUTED)
    a.save(f"{OUT}/phoneScreenshots/2.png")


def slide3():
    a = portrait(True)
    headline(a, "Certified time,", "EU qualified.",
             "Qualified timestamps when you start, every\n5 minutes, and when you stop. Only a hash\nis sent, never the audio.")
    a.phone("dashboard.jpg", 1300, 790, 1290, angle=-6, dim=0.15)
    stops = [("START", "22:07:03", "Sectigo"), ("+ 5 MIN", "22:12:03", "ACCV"),
             ("+ 10 MIN", "22:17:03", "Izenpe"), ("STOP", "22:19:41", "Sectigo + ACCV")]
    a.stroke([(86, 690), (86, 1490)], 5, "#1F6F69")
    for i, (lab, t, who) in enumerate(stops):
        y = 640 + i * 214
        a.card(130, y, 610, y + 168, 30, shadow=0.6)
        a.d.ellipse(S(70, y + 68, 102, y + 100), fill=C(TEAL), outline=C(NAVY1), width=6 * K)
        a.text(166, y + 40, lab, F("Inter-Bold.otf", 24), TEAL, "lm")
        a.text(166, y + 92, t, F(MONO_B, 48), WHITE, "lm")
        a.text(166, y + 140, f"{who} · qualified", F("Inter-Medium.otf", 25), MUTED, "lm")
        a.badge(560, y + 50, 22, TEAL_BG_D, TEAL)
    # eIDAS pill
    f = F("Inter-SemiBold.otf", 30)
    label = "eIDAS · Regulation (EU) 910/2014"
    w = a.tlen(label, f) + 60
    a.card(70, 1560, 70 + w, 1640, 40, shadow=0.6, blur=18, dy=12)
    a.text(70 + 30, 1600, label, f, SOFT, "lm")
    a.save(f"{OUT}/phoneScreenshots/3.png")


def slide4():
    a = portrait(False)
    headline(a, "Anyone can", "check it.",
             "Verify in the app, or on any computer\nwith free standard tools. No account,\nno trust in us required.")
    a.phone("check.jpg", 1240, 380, 1260, angle=-5)
    # result card
    x0, y0, x1, y1 = 470, 760, 1036, 940
    a.card(x0, y0, x1, y1, 32, shadow=0.7)
    a.badge(x0 + 80, y0 + 90, 46, TEAL_BG_L, TEAL_D)
    a.text(x0 + 152, y0 + 70, "Original recording", F("Inter-SemiBold.otf", 38), INK, "lm")
    a.text(x0 + 152, y0 + 118, "Not a single sample changed", F("Inter-Medium.otf", 27), INK_M, "lm")
    # terminal card
    x0, y0, x1, y1 = 250, 1290, 1046, 1730
    a.card(x0, y0, x1, y1, 30, fill=C("#0B1420"), outline=C("#1C2B3B"), shadow=0.8)
    for i, col in enumerate(("#FF5F57", "#FEBC2E", "#28C840")):
        a.d.ellipse(S(x0 + 34 + i * 36, y0 + 30, x0 + 56 + i * 36, y0 + 52), fill=C(col))
    f = F(MONO, 26)
    lines = [("$ sha256sum recording.flac", SOFT),
             ("9f3a07c2…41de  recording.flac", "#6B7F94"),
             ("", SOFT),
             ("$ openssl ts -verify \\", SOFT),
             ("    -data manifest.bin \\", SOFT),
             ("    -in close.tsr -CAfile tsa.pem", SOFT),
             ("Verification: OK", TEAL)]
    for i, (t, col) in enumerate(lines):
        a.text(x0 + 36, y0 + 104 + i * 42, t, F(MONO_B, 26) if col == TEAL else f, col, "lm")
    a.save(f"{OUT}/phoneScreenshots/4.png")


def slide5():
    a = portrait(True)
    headline(a, "Yours alone.", "Free and open.",
             "No account, no ads, no tracking.\nRecordings stay on your phone.")
    a.phone("settings.jpg", 1300, 540, 1250, dim=0.1)
    chips = [("No account", False, "check"), ("No ads", True, "check"), ("No tracking", False, "check"),
             ("Works offline", True, "check"), ("Multilingual: English, Italiano", False, "globe"),
             ("App lock", True, "check"), ("Open source · GPL-3.0", False, "check")]
    for i, (label, right, icon) in enumerate(chips):
        chip(a, 1040 if right else 40, 640 + i * 166, label, right=right, icon=icon)
    a.save(f"{OUT}/phoneScreenshots/5.png")


# ---------- feature graphic ----------

def feature():
    a = Art(1024, 500, True)
    a.glow(820, 250, 380, "#14B8A6", 0.30)
    a.glow(120, 40, 360, "#1E4E7A", 0.35)
    a.wave(6, 1024, 300, 164, 9, 3, MINT, lambda x: 0.035 + 0.05 * (x / 1024))
    a.phone("dashboard.jpg", 470, 925, 285, angle=-8, dim=0.5)
    a.phone("record.jpg", 520, 755, 300, angle=6)
    x0, y0 = 560, 372
    a.card(x0, y0, x0 + 262, y0 + 68, 18, shadow=0.6, blur=14, dy=12)
    a.d.rounded_rectangle(S(x0 + 12, y0 + 12, x0 + 56, y0 + 56), radius=12 * K, fill=C(TEAL_BG_D))
    a.shield(x0 + 34, y0 + 34, 30, TEAL, 3)
    a.text(x0 + 70, y0 + 13, "Sealed", F("Inter-SemiBold.otf", 18), WHITE)
    a.text(x0 + 70, y0 + 38, "SHA-256 · RFC 3161 · eIDAS", F("Inter-Medium.otf", 12), MUTED)
    icon = Image.open(f"{OUT}/icon.png").convert("RGBA").resize((64 * K, 64 * K), Image.LANCZOS)
    a.img.alpha_composite(icon, tuple(S(60, 56)))
    a.text(138, 88, "WitnessClip", F("Inter-SemiBold.otf", 30), WHITE, "lm")
    head = F("InterDisplay-Bold.otf", 54)
    a.text(58, 150, "Recordings that", head, WHITE)
    a.gtext(58, 210, "prove themselves.", head, MINT, "#14B8A6")
    for i, t in enumerate(["Sealed second by second, while you record",
                           "EU qualified timestamps",
                           "Verifiable by anyone, without the app"]):
        y = 320 + i * 38
        a.badge(72, y + 12, 12, TEAL_BG_D, TEAL)
        a.text(98, y + 12, t, F("Inter-Medium.otf", 19), SOFT, "lm")
    a.save(f"{OUT}/featureGraphic.png")


if __name__ == "__main__":
    feature()
    for f in (slide1, slide2, slide3, slide4, slide5):
        f()
