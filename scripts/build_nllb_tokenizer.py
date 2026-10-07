#!/usr/bin/env python3
"""
Convierte `tokenizer.json` (SentencePiece BPE de NLLB, 17 MB) en un binario
compacto para Android: `app/src/main/assets/nllb/tokenizer.bin`.

Formato (little-endian), pensado para carga secuencial sin parser JSON:
    magic      : 8 bytes  "NLLBTK1\n"
    u32 numTokens
    u32 numMerges
    tokens     : numTokens × (u16 len + len bytes UTF-8), en orden de id
    merges     : numMerges × (u32 left, u32 right, u32 result), en orden de rango
    langs      : u32 numLangs, y numLangs × (u16 len + bytes) con el código NLLB
                 (los ids de idioma ya están en `tokens`)
"""
from __future__ import annotations

import json
import os
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "nllb-onnx", "tokenizer.json")
DST = os.path.join(ROOT, "app", "src", "main", "assets", "nllb", "tokenizer.bin")

LANGS = [
    "spa_Latn", "eng_Latn", "fra_Latn", "deu_Latn", "ita_Latn", "por_Latn",
    "rus_Cyrl", "zho_Hans", "jpn_Jpan", "kor_Hang", "arb_Arab", "hin_Deva",
    "tur_Latn", "nld_Latn", "pol_Latn", "ukr_Cyrl", "ron_Latn", "bul_Cyrl",
    "hun_Latn", "swe_Latn", "dan_Latn", "fin_Latn", "nob_Latn", "ces_Latn",
    "ell_Grek", "heb_Hebr", "pes_Arab", "ind_Latn", "vie_Latn", "tha_Thai",
    "ben_Beng", "cat_Latn",
    "srp_Cyrl", "hrv_Latn", "slk_Latn", "slv_Latn", "lit_Latn", "lvs_Latn",
    "est_Latn", "zsm_Latn", "tgl_Latn", "swh_Latn", "urd_Arab", "tam_Taml",
    "tel_Telu", "mal_Mlym", "guj_Gujr", "kan_Knda", "mar_Deva", "npi_Deva",
    "sin_Sinh", "khm_Khmr", "mya_Mymr", "kat_Geor", "hye_Armn", "azj_Latn",
    "kaz_Cyrl", "uzn_Latn", "afr_Latn", "isl_Latn", "gle_Latn", "cym_Latn",
    "eus_Latn", "glg_Latn",
]


def main() -> int:
    j = json.load(open(SRC))
    vocab = j["model"]["vocab"]
    merges = j["model"]["merges"]

    n = max(vocab.values()) + 1
    tokens = [""] * n
    for t, i in vocab.items():
        tokens[i] = t
    # tokens añadidos que no estén en el vocab (por seguridad)
    for a in j.get("added_tokens", []):
        if a["id"] >= n:
            tokens.extend([""] * (a["id"] + 1 - n))
            n = a["id"] + 1
        tokens[a["id"]] = a["content"]

    out = bytearray()
    out += b"NLLBTK1\n"
    out += struct.pack("<II", len(tokens), len(merges))

    for t in tokens:
        b = t.encode("utf-8")
        if len(b) > 65535:
            raise ValueError("token demasiado largo")
        out += struct.pack("<H", len(b)) + b

    missing = 0
    for rank, m in enumerate(merges):
        a, b = m.split(" ")
        ra, rb = vocab.get(a), vocab.get(b)
        rr = vocab.get(a + b)
        if ra is None or rb is None or rr is None:
            missing += 1
            ra = rb = rr = 0
        out += struct.pack("<III", ra, rb, rr)
    if missing:
        print(f"AVISO: {missing} merges sin resolución en el vocab", file=sys.stderr)

    lang_ids = [(l, vocab[l]) for l in LANGS if l in vocab]
    out += struct.pack("<I", len(lang_ids))
    for l, i in lang_ids:
        b = l.encode("utf-8")
        out += struct.pack("<HI", len(b), i) + b

    os.makedirs(os.path.dirname(DST), exist_ok=True)
    with open(DST, "wb") as fh:
        fh.write(out)
    print(f"OK {DST}  {len(out)} bytes  ({len(tokens)} tokens, {len(merges)} merges, "
          f"{len(lang_ids)} idiomas)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
