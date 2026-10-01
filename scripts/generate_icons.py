#!/usr/bin/env python3
"""Generate AirPlay TV launcher / banner raster assets from a cohesive cast-to-TV mark."""

from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app" / "src" / "main" / "res"
DOCS = ROOT / "docs" / "assets"

# Living-room TV palette (teal / amber — no purple)
BG_TOP = (10, 22, 30, 255)
BG_BOTTOM = (6, 12, 18, 255)
TEAL = (61, 220, 255, 255)
TEAL_SOFT = (61, 220, 255, 160)
TEAL_DIM = (61, 220, 255, 90)
AMBER = (255, 200, 87, 255)
FRAME = (36, 58, 72, 255)
SCREEN = (14, 28, 38, 255)
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


def draw_mark(draw: ImageDraw.ImageDraw, cx: float, cy: float, scale: float, mono: bool = False):
    """Draw TV + inbound cast arcs (not an AirPlay triangle)."""
    s = scale
    frame = WHITE if mono else FRAME
    screen = (0, 0, 0, 0) if mono else SCREEN
    arc = WHITE if mono else TEAL
    arc2 = (255, 255, 255, 160) if mono else TEAL_SOFT
    arc3 = (255, 255, 255, 90) if mono else TEAL_DIM
    led = WHITE if mono else AMBER

    # TV body
    tw, th = 54 * s, 36 * s
    left, top = cx - tw / 2, cy - th / 2 - 2 * s
    right, bottom = cx + tw / 2, cy + th / 2 - 2 * s
    radius = 5 * s
    draw.rounded_rectangle([left, top, right, bottom], radius=radius, fill=frame)
    inset = 3.2 * s
    draw.rounded_rectangle(
        [left + inset, top + inset, right - inset, bottom - inset],
        radius=max(2.0, radius - 1.5 * s),
        fill=screen if not mono else None,
        outline=WHITE if mono else None,
        width=max(1, int(1.5 * s)) if mono else 0,
    )

    # Stand
    neck_w, neck_h = 3.2 * s, 5 * s
    draw.rectangle(
        [cx - neck_w / 2, bottom - 0.5 * s, cx + neck_w / 2, bottom + neck_h],
        fill=frame,
    )
    base_w, base_h = 18 * s, 2.4 * s
    draw.rounded_rectangle(
        [cx - base_w / 2, bottom + neck_h, cx + base_w / 2, bottom + neck_h + base_h],
        radius=1.2 * s,
        fill=frame,
    )

    # Cast arcs rising into the screen (receiver metaphor)
    origin_x, origin_y = cx, bottom - inset - 2 * s
    for radius_r, width, color in (
        (9 * s, max(1, int(1.6 * s)), arc),
        (14 * s, max(1, int(1.8 * s)), arc2),
        (19 * s, max(1, int(2.0 * s)), arc3),
    ):
        bbox = [
            origin_x - radius_r,
            origin_y - radius_r,
            origin_x + radius_r,
            origin_y + radius_r,
        ]
        draw.arc(bbox, start=220, end=320, fill=color, width=width)

    # Source / receiver node
    r = 2.4 * s
    draw.ellipse([origin_x - r, origin_y - r, origin_x + r, origin_y + r], fill=led)

    # Soft screen glow (skip for mono)
    if not mono:
        glow_r = 10 * s
        gx, gy = cx, cy - 4 * s
        for i in range(8, 0, -1):
            alpha = int(18 * (i / 8))
            rr = glow_r * (i / 8)
            draw.ellipse(
                [gx - rr, gy - rr, gx + rr, gy + rr],
                fill=(61, 220, 255, alpha),
            )


def make_launcher(size: int, round_mask: bool = False) -> Image.Image:
    img = vertical_gradient(size)
    # Ambient glow
    glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    gdraw = ImageDraw.Draw(glow)
    cx = cy = size / 2
    for i in range(24, 0, -1):
        rr = size * 0.34 * (i / 24)
        alpha = int(40 * (i / 24))
        gdraw.ellipse([cx - rr, cy - rr - size * 0.04, cx + rr, cy + rr - size * 0.04], fill=(61, 220, 255, alpha))
    img = Image.alpha_composite(img, glow)

    draw = ImageDraw.Draw(img)
    draw_mark(draw, cx, cy, scale=size / 108.0)

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

    # Soft vignette glow behind mark
    overlay = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    od = ImageDraw.Draw(overlay)
    gx, gy = width * 0.28, height * 0.48
    for i in range(30, 0, -1):
        rr = height * 0.42 * (i / 30)
        od.ellipse([gx - rr, gy - rr, gx + rr, gy + rr], fill=(61, 220, 255, int(22 * i / 30)))
    img = Image.alpha_composite(img, overlay)

    draw = ImageDraw.Draw(img)
    draw_mark(draw, width * 0.28, height * 0.48, scale=height / 78.0)

    # Wordmark
    title = "AirPlay TV"
    subtitle = "Cast • Mirror • Stream"
    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 28)
        font_sm = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 13)
    except OSError:
        font = ImageFont.load_default()
        font_sm = font

    tx = int(width * 0.48)
    ty = int(height * 0.36)
    draw.text((tx, ty), title, fill=WHITE, font=font)
    draw.text((tx, ty + 36), subtitle, fill=MUTED, font=font_sm)
    # Accent underline
    draw.rounded_rectangle([tx, ty + 30, tx + 56, ty + 33], radius=2, fill=TEAL)
    return img


def save(img: Image.Image, path: Path):
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, "PNG")
    print(f"wrote {path.relative_to(ROOT)} ({img.size[0]}x{img.size[1]})")


def main():
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
    save(make_banner(1280, 720), DOCS / "app_banner.png")


if __name__ == "__main__":
    main()
