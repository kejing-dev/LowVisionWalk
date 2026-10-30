#!/usr/bin/env python
"""Writes the app's vector drawables (res/drawable/ic_*.xml) and, with --png, the 512 px launcher icon.

    python -I tools/make_icons.py [--png <out.png>]

Launcher icon (design appIcon): white almond eye outline + solid pupil on #1E6E8C; around it the field
ring, whole on the right, broken into dashes on the left ("视野缺了一块"). Viewport 108, safe zone 66.
"""
import math
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
DRAW = os.path.join(ROOT, "app", "src", "main", "res", "drawable")
BG = "#1E6E8C"


def arc_path(cx, cy, r, a0, a1, steps=24):
    pts = []
    for i in range(steps + 1):
        a = math.radians(a0 + (a1 - a0) * i / steps)
        pts.append((cx + r * math.cos(a), cy - r * math.sin(a)))
    return "M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in pts)


def circle(cx, cy, r):
    return f"M{cx - r:.2f},{cy:.2f} a{r:.2f},{r:.2f} 0 1,0 {2 * r:.2f},0 a{r:.2f},{r:.2f} 0 1,0 {-2 * r:.2f},0 Z"


def eye_path(cx, cy, w, h):
    # almond: two quadratic arcs meeting at the corners
    return f"M{cx - w:.2f},{cy:.2f} Q{cx:.2f},{cy - h:.2f} {cx + w:.2f},{cy:.2f} Q{cx:.2f},{cy + h:.2f} {cx - w:.2f},{cy:.2f} Z"


def vector(size, viewport, paths):
    body = "\n".join(paths)
    return f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{size}dp"
    android:height="{size}dp"
    android:viewportWidth="{viewport}"
    android:viewportHeight="{viewport}">
{body}
</vector>
'''


def fill(d, color="#FFFFFFFF"):
    return f'    <path android:fillColor="{color}" android:pathData="{d}" />'


def stroke(d, w, color="#FFFFFFFF", cap="round"):
    return f'    <path android:strokeColor="{color}" android:strokeWidth="{w}" android:strokeLineCap="{cap}" android:strokeLineJoin="round" android:fillColor="#00000000" android:pathData="{d}" />'


def launcher_parts():
    c = 54
    parts = []
    # field ring r 30: right half solid (-90..90), left half dashes
    parts.append(stroke(arc_path(c, c, 30, -90, 90, 48), 4.2))
    for a in range(100, 270, 22):
        parts.append(stroke(arc_path(c, c, 30, a, a + 11, 6), 4.2))
    parts.append(stroke(eye_path(c, c, 20, 15), 4.3))
    parts.append(fill(circle(c, c, 6.2)))
    return parts


def write(name, text):
    with open(os.path.join(DRAW, name + ".xml"), "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def ui_icons():
    W = "#FFFFFFFF"
    s = lambda d, w=2.0: stroke(d, w, W)
    icons = {
        "ic_glaucoma": [s(circle(12, 12, 9)), fill(circle(12, 12, 9), "#59FFFFFF"), fill(circle(12, 12, 4.2), "#FF101418"), s(circle(12, 12, 4.2), 1.6)],
        "ic_amd": [s(circle(12, 12, 9)), fill(circle(12, 12, 3.8))],
        "ic_hemianopia": [s(circle(12, 12, 9)), fill(f"M12,3 A9,9 0 0,0 12,21 Z")],
        "ic_mug": [s("M5,7 L15,7 L14,19 Q14,20 13,20 L7,20 Q6,20 6,19 Z"), s("M15,10 Q20,10 19,14 Q18,16 14.6,16")],
        "ic_sign": [s("M3,6 L21,6 L21,15 L3,15 Z"), s("M12,15 L12,21"), s("M7,10.5 L17,10.5")],
        "ic_door": [s("M6,21 L6,3 L18,3 L18,21", 2.0), s("M3,21 L21,21"), fill(circle(15, 12.5, 1.2))],
        "ic_eye": [s(eye_path(12, 12, 9.5, 7), 2.0), fill(circle(12, 12, 3))],
        "ic_home": [s("M4,11 L12,4 L20,11"), s("M6,10 L6,20 L18,20 L18,10"), s("M10,20 L10,14 L14,14 L14,20")],
        "ic_list": [s("M8,7 L20,7"), s("M8,12 L20,12"), s("M8,17 L20,17"), fill(circle(4.5, 7, 1.3)), fill(circle(4.5, 12, 1.3)), fill(circle(4.5, 17, 1.3))],
        "ic_more": [fill(circle(5, 12, 2)), fill(circle(12, 12, 2)), fill(circle(19, 12, 2))],
        "ic_sound_on": [fill("M3,9 L7,9 L12,5 L12,19 L7,15 L3,15 Z"), s("M15.5,8.5 Q18,12 15.5,15.5"), s("M18,6 Q22.5,12 18,18")],
        "ic_sound_off": [fill("M3,9 L7,9 L12,5 L12,19 L7,15 L3,15 Z"), s("M15,9 L21,15"), s("M21,9 L15,15")],
        "ic_close": [s("M6,6 L18,18"), s("M18,6 L6,18")],
        "ic_check": [s("M5,12.5 L10,17 L19,7", 2.4)],
        # recall: a card with an arrow pulling it towards you (down-left, the viewer)
        "ic_recall": [s("M9,4 L20,4 L20,14 L9,14 Z"), s("M12,17 L5,20"), s("M5,15.5 L5,20 L9.5,20")],
    }
    # settings gear: 8 teeth polygon + hole
    pts = []
    for i in range(16):
        a0 = math.radians(i * 22.5 - 6)
        a1 = math.radians(i * 22.5 + 6)
        r = 9.6 if i % 2 == 0 else 7.4
        pts += [(12 + r * math.cos(a0), 12 + r * math.sin(a0)), (12 + r * math.cos(a1), 12 + r * math.sin(a1))]
    gear = "M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in pts) + " Z"
    icons["ic_settings"] = [stroke(gear, 1.8, W), s(circle(12, 12, 3), 1.8)]
    for name, parts in icons.items():
        write(name, vector(24, 24, parts))


def main():
    os.makedirs(DRAW, exist_ok=True)
    write("ic_launcher_foreground", vector(108, 108, launcher_parts()))
    ui_icons()
    if "--png" in sys.argv:
        out = sys.argv[sys.argv.index("--png") + 1]
        png(out)


def png(out, size=512):
    from PIL import Image, ImageDraw
    k = 4
    S = size * k
    img = Image.new("RGBA", (S, S), BG)
    d = ImageDraw.Draw(img)
    sc = S / 108.0
    def P(x, y):
        return (x * sc, y * sc)
    def arc(cx, cy, r, a0, a1, w):
        box = [P(cx - r, cy - r), P(cx + r, cy + r)]
        d.arc([box[0][0], box[0][1], box[1][0], box[1][1]], start=-a1, end=-a0, fill="white", width=int(w * sc))
    arc(54, 54, 30, -90, 90, 4.2)
    for a in range(100, 270, 22):
        arc(54, 54, 30, a, a + 11, 4.2)
    # almond eye as a polyline of the two quadratic curves
    def quad(p0, p1, p2, n=40):
        return [((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0], (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1]) for t in [i / n for i in range(n + 1)]]
    top = quad((34, 54), (54, 39), (74, 54))
    bot = quad((74, 54), (54, 69), (34, 54))
    d.line([P(*p) for p in top + bot], fill="white", width=int(4.3 * sc), joint="curve")
    r = 6.2
    d.ellipse([P(54 - r, 54 - r), P(54 + r, 54 + r)], fill="white")
    img = img.resize((size, size), Image.LANCZOS).convert("RGB")
    img.save(out)
    print(out)


if __name__ == "__main__":
    main()
