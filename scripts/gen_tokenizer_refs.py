#!/usr/bin/env python3
"""Emite los vectores de referencia (texto -> ids) del tokenizer de NLLB para el
test de JVM `NllbTokenizerTest` (misma fuente que valida `bpe_proto.py`)."""
from __future__ import annotations

import sys

from tokenizers import Tokenizer

TEXTS = [
    "Hello, how are you today? I would like a coffee, please.",
    "Buenos días, ¿dónde está la estación de tren más cercana?",
    "Der Zug nach Berlin fährt jeden Morgen um acht Uhr ab.",
    "Minä pidän suomalaisesta saunasta ja järvistä, erityisesti kesällä.",
    "আমি বাংলা ভাষা শিখছি এবং আমার পরিবার বাংলাদেশে থাকে।",
    "The northern lights are a natural phenomenon.",
    "Buongiorno, vorrei un caffè e un cornetto, per favore.",
    "Доброе утро, где ближайшая станция метро?",
    "おはようございます、今日はいい天気ですね。",
    "مرحبا، أين أقرب محطة قطار؟",
    "Artificial intelligence is transforming the world.",
    "A",
    "one two three four five",
]


def esc(s: str) -> str:
    return s.replace("\\", "\\\\").replace('"', '\\"')


def main() -> int:
    path = sys.argv[1] if len(sys.argv) > 1 else "nllb-onnx/tokenizer.json"
    t = Tokenizer.from_file(path)
    out = ["        private val CASES = listOf("]
    for x in TEXTS:
        ids = t.encode(x, add_special_tokens=False).ids
        out.append('            "%s" to intArrayOf(%s),' % (esc(x), ", ".join(map(str, ids))))
    out.append("        )")
    print("\n".join(out))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
