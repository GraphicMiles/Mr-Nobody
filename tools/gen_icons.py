#!/usr/bin/env python3
"""Generate the Mr Nobody launcher icons from the single source-of-truth mark.

The mark is the wireframe logo (crown + brim + glasses) defined on a 64x64
grid.  This script renders:

  * legacy square + round PNG mipmaps (mdpi..xxxhdpi) for pre-API-26 launchers
  * a Play-Store-ready 512px PNG (build artifact, not committed)

The adaptive icon (API 26+) and the in-app Flutter logo are vector/Dart
re-implementations of the same geometry — see
`app/android/app/src/main/res/drawable/ic_launcher_foreground.xml` and
`app/lib/widgets/brand_logo.dart`.

Usage:  python3 tools/gen_icons.py [output_res_dir]
Default output dir: app/android/app/src/main/res
"""

from __future__ import annotations

import os
import sys

from PIL import Image, ImageDraw

# --- geometry, on the 64x64 design grid ------------------------------------
CROWN = [
    ("M", (18, 30)),
    ("L", (24, 15)),
    ("C", (26, 20), (30, 21), (32, 12)),
    ("C", (34, 21), (38, 20), (40, 15)),
    ("L", (46, 30)),
]
BRIM = (14, 30, 50, 34)          # x0, y0, x1, y1  (rounded bar)
LENS_L = (24, 42, 7)             # cx, cy, r
LENS_R = (40, 42, 7)
BRIDGE = ((31, 42), (33, 42))
STROKE = 3                       # glasses stroke width on the design grid

SS = 8                           # supersampling factor
DENSITIES = {                    # legacy launcher icon sizes
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}
FG = (250, 250, 250, 255)        # near-white mark
BG = (0, 0, 0, 255)              # black plate


def _cubic(p0, p1, p2, p3, steps=48):
    pts = []
    for i in range(1, steps + 1):
        t = i / steps
        u = 1 - t
        x = u ** 3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t ** 3 * p3[0]
        y = u ** 3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t ** 3 * p3[1]
        pts.append((x, y))
    return pts


def crown_points():
    pts: list[tuple[float, float]] = []
    cur = (0.0, 0.0)
    for seg in CROWN:
        kind = seg[0]
        if kind == "M":
            cur = seg[1]
            pts.append(cur)
        elif kind == "L":
            cur = seg[1]
            pts.append(cur)
        elif kind == "C":
            c1, c2, end = seg[1], seg[2], seg[3]
            pts.extend(_cubic(cur, c1, c2, end))
            cur = end
    return pts


def render(size: int, scale: float = 1.0, plate: bool = True) -> Image.Image:
    """Render the mark at `size` px. `scale` shrinks the mark inside the plate."""
    canvas = size * SS
    img = Image.new("RGBA", (canvas, canvas), BG if plate else (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # design grid -> canvas, with the mark scaled about the grid centre
    k = canvas / 64.0 * scale
    off = (canvas - 64.0 * k) / 2.0

    def T(p):
        return (off + p[0] * k, off + p[1] * k)

    d.polygon([T(p) for p in crown_points()], fill=FG)

    x0, y0, x1, y1 = BRIM
    r = (y1 - y0) / 2.0
    d.rounded_rectangle([T((x0, y0)), T((x1, y1))], radius=r * k, fill=FG)

    w = max(1, int(round(STROKE * k)))
    for cx, cy, rad in (LENS_L, LENS_R):
        d.ellipse([T((cx - rad, cy - rad)), T((cx + rad, cy + rad))], outline=FG, width=w)
    d.line([T(BRIDGE[0]), T(BRIDGE[1])], fill=FG, width=w)

    return img.resize((size, size), Image.LANCZOS)


def round_icon(size: int) -> Image.Image:
    """Same mark, circular plate (ic_launcher_round)."""
    canvas = size * SS
    src = render(size, scale=0.86, plate=False).resize((canvas, canvas), Image.LANCZOS)
    plate = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    ImageDraw.Draw(plate).ellipse([0, 0, canvas - 1, canvas - 1], fill=BG)
    plate.alpha_composite(src)
    return plate.resize((size, size), Image.LANCZOS)


def main() -> int:
    res = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "app", "android", "app", "src", "main", "res")
    for density, size in DENSITIES.items():
        out = os.path.join(res, f"mipmap-{density}")
        os.makedirs(out, exist_ok=True)
        render(size, scale=0.82).save(os.path.join(out, "ic_launcher.png"))
        round_icon(size).save(os.path.join(out, "ic_launcher_round.png"))
        print(f"wrote {out}/ic_launcher[_round].png ({size}px)")
    store = os.path.join(res, "..", "..", "..", "..", "..", "build")
    os.makedirs(store, exist_ok=True)
    render(512, scale=0.82).save(os.path.join(store, "playstore-icon.png"))
    print("wrote build/playstore-icon.png (512px)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
