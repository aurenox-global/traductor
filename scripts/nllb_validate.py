#!/usr/bin/env python3
"""
FASE 1 — validación en el Mac de la tubería NLLB-200-distilled-600M por ONNX.

Ejecuta los casos pedidos (en->es, es->en, de->es, fi->es, bn->es y un texto
largo), imprime un informe y guarda la evidencia en `nllb-evidence/`.
"""
from __future__ import annotations

import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from nllb_onnx import NllbOnnx  # noqa: E402

LONG_EN = (
    "The northern lights, also known as the aurora borealis, are a natural "
    "phenomenon that occurs when charged particles from the sun collide with "
    "gases in the Earth's atmosphere. They are most commonly seen in high "
    "latitudes, near the Arctic Circle, during the winter months.\n\n"
    "People travel from all over the world to witness this spectacular display "
    "of colours, which can range from pale green to vivid pink, red and violet. "
    "The best viewing conditions require a clear, dark sky, away from city "
    "lights, and a little patience."
)

CASES = [
    ("en->es", "en", "es",
     "Hello, how are you today? I would like a coffee, please."),
    ("es->en", "es", "en",
     "Buenos días, ¿dónde está la estación de tren más cercana? Necesito comprar un billete."),
    ("de->es", "de", "es",
     "Der Zug nach Berlin fährt jeden Morgen um acht Uhr ab, aber heute hat er Verspätung."),
    ("fi->es", "fi", "es",
     "Minä pidän suomalaisesta saunasta ja järvistä, erityisesti kesällä."),
    ("bn->es", "bn", "es",
     "আমি বাংলা ভাষা শিখছি এবং আমার পরিবার বাংলাদেশে থাকে।"),
    ("long en->es", "en", "es", LONG_EN),
]

BEAMS = int(os.environ.get("NLLB_BEAMS", "5"))
MAXNEW = int(os.environ.get("NLLB_MAXNEW", "256"))


def main() -> int:
    outdir = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                          "nllb-evidence")
    os.makedirs(outdir, exist_ok=True)

    eng = NllbOnnx(os.environ.get("NLLB_MODEL", "nllb-onnx"), verbose=True)

    lines = ["# FASE 1 — NLLB-200-distilled-600M por ONNX Runtime (Mac)", ""]
    lines.append(f"Modelo: Xenova/nllb-200-distilled-600M · encoder/decoder **int8 quantized**")
    lines.append(f"Decodificación: beam search (beams={BEAMS}, length_penalty=1.0)")
    lines.append(f"Fecha: {time.strftime('%Y-%m-%d %H:%M')}")
    lines.append("")
    results = []
    ok = 0
    for name, src, tgt, text in CASES:
        t0 = time.time()
        out = eng.translate(text, src, tgt, num_beams=BEAMS, max_new_tokens=MAXNEW)
        dt = time.time() - t0
        results.append({"case": name, "src": src, "tgt": tgt, "in": text,
                        "out": out, "seconds": round(dt, 2)})
        lines += [f"## {name}  ({dt:.1f} s)", "",
                  "**Entrada:**", "", f"> {text.strip().replace(chr(10), ' ')}", "",
                  "**Salida:**", "", f"> {out}", ""]
        print(f"[{name}] {dt:.1f}s -> {out[:120]}", flush=True)
        if out.strip():
            ok += 1

    lines.insert(3, f"Casos con salida no vacía: **{ok}/{len(CASES)}**\n")

    with open(os.path.join(outdir, "fase1-informe.md"), "w") as fh:
        fh.write("\n".join(lines) + "\n")
    with open(os.path.join(outdir, "fase1-resultados.json"), "w") as fh:
        json.dump(results, fh, ensure_ascii=False, indent=2)
    print(f"\nEvidencia en {outdir}/fase1-informe.md")
    return 0 if ok == len(CASES) else 1


if __name__ == "__main__":
    raise SystemExit(main())
