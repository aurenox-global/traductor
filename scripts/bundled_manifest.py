#!/usr/bin/env python3
"""Genera `app/bundled-assets/bundled/manifest.json` para la variante FULL.

El manifest guía la copia assets -> filesDir en runtime (BundledAssets.kt):
lista cada fichero con su tamaño (para el progreso) y el total.

Uso:  python3 scripts/bundled_manifest.py
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEST = os.path.join(ROOT, "app", "bundled-assets", "bundled")
OUT = os.path.join(DEST, "manifest.json")

VOICES = ["es_AR-daniela-high", "en_US-hfc_female-medium"]


def walk_files(base, prefix):
    out = []
    for dirpath, _dirs, files in os.walk(base):
        for name in files:
            if name.startswith(".") or name.endswith(".part"):
                continue
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, base).replace(os.sep, "/")
            out.append({"p": prefix + rel, "s": os.path.getsize(full)})
    return out


def main():
    entries = []
    files_dir = os.path.join(DEST, "files")
    if os.path.isdir(files_dir):
        entries += walk_files(files_dir, "files/")
    piper_dir = os.path.join(DEST, "piper")
    if os.path.isdir(piper_dir):
        entries += walk_files(piper_dir, "piper/")

    entries.sort(key=lambda e: e["p"])
    total = sum(e["s"] for e in entries)

    manifest = {
        "schema": 1,
        "variant": "full",
        "voices": VOICES,
        "totalBytes": total,
        "entries": entries,
    }
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=1, sort_keys=True)
        fh.write("\n")

    missing = [v for v in VOICES if not os.path.isfile(os.path.join(piper_dir, v, "model.onnx"))]
    print("manifest: %d entradas, %d bytes (%.1f MiB)" % (len(entries), total, total / 1048576.0))
    print("voces: %s" % ", ".join(VOICES))
    if missing:
        print("ERROR: faltan voces: %s" % ", ".join(missing), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
