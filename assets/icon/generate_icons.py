#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Generador del icono de Traductor.

Estilo: squircle de azul brillante (#0A84FF) con degradado sutil y un glifo
blanco minimalista (dos bocadillos de chat superpuestos). Sin texto.

Salidas:
  app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png
  app/src/main/res/mipmap-{...}/ic_launcher_round.png
  assets/icon/ic_launcher_512.png   (tienda / Play Store)
  assets/icon/icon-web-512.png      (web; copia en docs/icon.png)
  assets/icon/preview.png           (vista previa de varias densidades)

Uso:  python3 assets/icon/generate_icons.py
Requiere: Pillow (PIL). 100% determinista, sin dependencias externas.
"""
from __future__ import annotations

import os
from PIL import Image, ImageDraw

# ---------------------------------------------------------------- constantes
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")
ICON_DIR = os.path.join(ROOT, "assets", "icon")
DOCS_DIR = os.path.join(ROOT, "docs")

BLUE_TOP = (79, 168, 255)     # #4FA8FF (azul claro sutil)
BLUE_BOT = (10, 111, 216)     # #0A6FD8
BLUE_MID = (10, 132, 255)     # #0A84FF (referencia)

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

SS = 4  # super-sampling (antialiasing)


def _squircle_mask(size: int, radius_ratio: float = 0.2237) -> Image.Image:
    """Mascara de 'squircle' (cuadrado redondeado tipo Apple)."""
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    r = int(round(size * radius_ratio))
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=r, fill=255)
    return m


def _circle_mask(size: int) -> Image.Image:
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    d.ellipse([0, 0, size - 1, size - 1], fill=255)
    return m


def _gradient(size: int) -> Image.Image:
    """Degradado vertical sutil de azul claro a azul intenso."""
    grad = Image.new("RGB", (1, size))
    px = grad.load()
    for y in range(size):
        t = y / max(1, size - 1)
        px[0, y] = (
            int(BLUE_TOP[0] + (BLUE_BOT[0] - BLUE_TOP[0]) * t),
            int(BLUE_TOP[1] + (BLUE_BOT[1] - BLUE_TOP[1]) * t),
            int(BLUE_TOP[2] + (BLUE_BOT[2] - BLUE_TOP[2]) * t),
        )
    return grad.resize((size, size), Image.BILINEAR)


def _draw_glyph(d: ImageDraw.ImageDraw, size: int) -> None:
    """Dos bocadillos de chat superpuestos (glifo blanco minimalista)."""
    u = size / 100.0  # unidad relativa

    # --- bocadillo trasero (blanco al 68%) ---
    back = [22 * u, 24 * u, 62 * u, 52 * u]
    back_r = 9 * u
    d.rounded_rectangle(back, radius=back_r, fill=(255, 255, 255, 174))
    # cola inferior izquierda
    d.polygon(
        [
            (32 * u, 50 * u),
            (40 * u, 50 * u),
            (30 * u, 63 * u),
        ],
        fill=(255, 255, 255, 174),
    )

    # --- separacion: contorno del bocadillo frontal en azul ---
    front = [38 * u, 44 * u, 80 * u, 76 * u]
    front_r = 10 * u
    sep = max(2, int(round(2.4 * u)))
    d.rounded_rectangle(front, radius=front_r, outline=BLUE_MID, width=sep)
    d.polygon(
        [
            (66 * u, 74 * u),
            (76 * u, 74 * u),
            (76 * u, 86 * u),
        ],
        fill=BLUE_MID,
    )

    # --- bocadillo frontal (blanco solido) ---
    d.rounded_rectangle(front, radius=front_r, fill=(255, 255, 255, 255))
    d.polygon(
        [
            (66 * u, 74 * u),
            (77 * u, 74 * u),
            (77 * u, 86 * u),
        ],
        fill=(255, 255, 255, 255),
    )


def make_icon(size: int, round_icon: bool = False) -> Image.Image:
    """Genera un icono RGBA de `size` px."""
    big = size * SS
    canvas = Image.new("RGBA", (big, big), (0, 0, 0, 0))

    # fondo con degradado + mascara
    bg = _gradient(big).convert("RGBA")
    mask = _circle_mask(big) if round_icon else _squircle_mask(big)
    canvas.paste(bg, (0, 0), mask)

    # glifo
    glyph = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glyph)
    _draw_glyph(gd, big)
    if round_icon:
        # recorta el glifo al circulo para que ninguna cola sobresalga
        glyph.putalpha(Image.composite(glyph.getchannel("A"), Image.new("L", (big, big), 0), mask))
    canvas.alpha_composite(glyph)

    return canvas.resize((size, size), Image.LANCZOS)


def main() -> None:
    os.makedirs(ICON_DIR, exist_ok=True)

    # --- mipmaps en todas las densidades ---
    for dens, px in DENSITIES.items():
        out = os.path.join(RES, "mipmap-" + dens)
        os.makedirs(out, exist_ok=True)
        make_icon(px, False).save(os.path.join(out, "ic_launcher.png"))
        make_icon(px, True).save(os.path.join(out, "ic_launcher_round.png"))
        print("mipmap-%s: ic_launcher.png / ic_launcher_round.png (%dpx)" % (dens, px))

    # --- 512 para tienda + web ---
    store = make_icon(512, False)
    store.save(os.path.join(ICON_DIR, "ic_launcher_512.png"))
    web = make_icon(512, False)
    web.save(os.path.join(ICON_DIR, "icon-web-512.png"))
    os.makedirs(DOCS_DIR, exist_ok=True)
    web.save(os.path.join(DOCS_DIR, "icon.png"))
    print("assets/icon/ic_launcher_512.png + icon-web-512.png + docs/icon.png")

    # --- vista previa (48 / 96 / 192 / 512) ---
    sizes = [48, 96, 192, 512]
    pad = 24
    total_w = sum(sizes) + pad * (len(sizes) + 1)
    max_h = max(sizes) + pad * 2
    preview = Image.new("RGBA", (total_w, max_h), (15, 17, 21, 255))
    x = pad
    for s in sizes:
        preview.alpha_composite(make_icon(s, False), (x, pad))
        x += s + pad
    preview.save(os.path.join(ICON_DIR, "preview.png"))
    print("assets/icon/preview.png")
    print("OK: iconos generados.")


if __name__ == "__main__":
    main()
