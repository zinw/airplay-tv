#!/usr/bin/env python3
"""Generate AirPlay TV launcher / banner raster assets from a cohesive cast-to-TV mark."""

from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app" / "src" / "main" / "res"
DOCS = ROOT / "docs" / "assets"

BG_TOP = (10, 22, 30, 255)
BG_BOTTOM = (6, 12, 18, 255)
TEAL = (61, 220, 255, 255)
AMBER = (255, 200, 87, 255)
FRAME = (36, 58, 72, 255)
SCREEN = (14, 28, 38, 255)
STAND = (58, 85, 104, 255)
WHITE = (242, 247, 250, 255)
MUTED = (168, 184, 196, 255)


def lerp(a: tuple, b: tuple, t: float) -> tuple:
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(4))


def vertical_gradient(size: int, top=BG_TOP, bottom=BG_BOTTOM) -> Image.Image:
    img = Image.new("RGBA", (size, size))
    px = img.load()
    for y in range(size):
        c = lerp(top, bottom, y / max(size - 1, 1))
        for x in range(size):
            px[x, y] = c
    return img


def draw_mark(base: Image.Image, cx: float, cy: float, scale: float) -> Image.Image:
    """TV screen + inbound cast arcs (receiver metaphor, not an AirPlay triangle)."""
    glow = Image.new("RGBA", base.size, (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    for i in range(16, 0, -1):
        rr = 11 * scale * (i / 16)
        gd.ellipse(
            [cx - rr, cy - 4 * scale - rr, cx + rr, cy - 4 * scale + rr],
            fill=(61, 220, 255, int(14 * i / 16)),
        )
    base = Image.alpha_composite(base, glow)
    d = ImageDraw.Draw(base)

    tw, th = 54 * scale, 36 * scale
    left, top = cx - tw / 2, cy - th / 2 - 2 * scale
    right, bottom = cx + tw / 2, cy + th / 2 - 2 * scale
    d.rounded_rectangle([left, top, right, bottom], radius=5 * scale, fill=FRAME)
    inset = 3.2 * scale
    d.rounded_rectangle(
        [left + inset, top + inset, right - inset, bottom - inset],
        radius=max(2.0, 3.5 * scale),
        fill=SCREEN,
    )

    d.rectangle(
        [cx - 1.6 * scale, bottom - 0.5 * scale, cx + 1.6 * scale, bottom + 5 * scale],
        fill=FRAME,
    )
    d.rounded_rectangle(
        [cx - 9 * scale, bottom + 5 * scale, cx + 9 * scale, bottom + 7.4 * scale],
        radius=1.2 * scale,
        fill=STAND,
    )

    ox, oy = cx, bottom - inset - 2.2 * scale
    for radius_r, width, alpha in (
        (9 * scale, max(2, int(2.0 * scale)), 255),
        (14 * scale, max(2, int(2.2 * scale)), 200),
        (19 * scale, max(2, int(2.4 * scale)), 120),
    ):
        pts = []
        for deg in range(220, 321, 2):
            rad = math.radians(deg)
            pts.append((ox + radius_r * math.cos(rad), oy + radius_r * math.sin(rad)))
        if len(pts) >= 2:
            d.line(pts, fill=(61, 220, 255, alpha), width=width, joint="curve")

    r = 2.6 * scale
    d.ellipse([ox - r, oy - r, ox + r, oy + r], fill=AMBER)
    return base


def make_launcher(size: int, round_mask: bool = False) -> Image.Image:
    img = vertical_gradient(size)
    glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    cx = cy = size / 2
    for i in range(20, 0, -1):
        rr = size * 0.32 * (i / 20)
        gd.ellipse(
            [cx - rr, cy - rr - size * 0.04, cx + rr, cy + rr - size * 0.04],
            fill=(61, 220, 255, int(28 * i / 20)),
        )
    img = Image.alpha_composite(img, glow)
    img = draw_mark(img, cx, cy, size / 108.0)

    if round_mask:
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
        out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        out.paste(img, mask=mask)
        return out
    return img


def make_banner(width: int = 320, height: int = 180) -> Image.Image:
    img = Image.new("RGBA", (width, height))
    px = img.load()
    for y in range(height):
        for x in range(width):
            t = (x / max(width - 1, 1) * 0.35) + (y / max(height - 1, 1) * 0.65)
            px[x, y] = lerp(BG_TOP, BG_BOTTOM, t)

    overlay = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    od = ImageDraw.Draw(overlay)
    gx, gy = width * 0.28, height * 0.48
    for i in range(24, 0, -1):
        rr = height * 0.4 * (i / 24)
        od.ellipse([gx - rr, gy - rr, gx + rr, gy + rr], fill=(61, 220, 255, int(18 * i / 24)))
    img = Image.alpha_composite(img, overlay)
    img = draw_mark(img, width * 0.28, height * 0.48, height / 78.0)

    draw = ImageDraw.Draw(img)
    title_size = 28 if width <= 320 else 72
    sub_size = 13 if width <= 320 else 28
    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", title_size)
        font_sm = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", sub_size)
    except OSError:
        font = ImageFont.load_default()
        font_sm = font

    tx = int(width * 0.48)
    ty = int(height * 0.36)
    underline_y = ty + (32 if width <= 320 else 78)
    underline_h = 3 if width <= 320 else 6
    underline_w = 56 if width <= 320 else 120
    sub_y = ty + (40 if width <= 320 else 96)

    draw.text((tx, ty), "AirPlay TV", fill=WHITE, font=font)
    draw.rounded_rectangle(
        [tx, underline_y, tx + underline_w, underline_y + underline_h],
        radius=2,
        fill=TEAL,
    )
    draw.text((tx, sub_y), "Cast • Mirror • Stream", fill=MUTED, font=font_sm)
    return img


def save(img: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, "PNG")
    print(f"wrote {path.relative_to(ROOT)} ({img.size[0]}x{img.size[1]})")


def main() -> None:
    densities = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    for folder, size in densities.items():
        save(make_launcher(size, round_mask=False), RES / folder / "ic_launcher.png")
        save(make_launcher(size, round_mask=True), RES / folder / "ic_launcher_round.png")

    save(make_banner(320, 180), RES / "drawable" / "banner.png")
    DOCS.mkdir(parents=True, exist_ok=True)
    save(make_launcher(512, round_mask=False), DOCS / "app_icon.png")
    save(make_launcher(1024, round_mask=False), DOCS / "app_icon_1024.png")
    save(make_banner(1280, 720), DOCS / "app_banner.png")
    save(make_banner(320, 180), DOCS / "banner_leanback.png")
    save(make_launcher(512, round_mask=True), DOCS / "github_avatar.png")


if __name__ == "__main__":
    main()
