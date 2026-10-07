#!/usr/bin/env bash
#
# fetch_ocr_bundle.sh — prepara los assets que van DENTRO de la APK **LITE-OCR**.
#
# Se usa SOLO para construir la variante LITE-OCR:
#     ./gradlew :app:assembleDebug -Pocrbundle=true
# que añade `app/bundled-ocr/` como srcDir de assets y define BUNDLED_MODELS=true.
#
# Contenido (el motor OCR empaquetado, sin los ~900 MB de NLLB):
#   files/ppocr_v6_det.onnx          OCR PP-OCRv6 tiny · detección
#   files/ppocr_v6_rec.onnx          OCR PP-OCRv6 tiny · reconocimiento
#   files/ppocr_v6_rec.yml           diccionario OCR (se parsea on-device)
#   files/silero_vad.onnx            VAD Silero v5 (opcional, usado por el ASR)
#
# Layout idéntico al de la FULL (`bundled/files/…` + `bundled/manifest.json`) para
# que BundledAssets.kt lo copie sin cambios. Los ficheros NO se versionan
# (ver .gitignore): se regeneran con este script.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/bundled-ocr/bundled"
FILES="$DEST/files"
mkdir -p "$FILES"

VAD_URL="https://raw.githubusercontent.com/snakers4/silero-vad/master/src/silero_vad/data/silero_vad.onnx"
OCR_DET="https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_det_onnx/resolve/main/inference.onnx"
OCR_REC="https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_rec_onnx/resolve/main/inference.onnx"
OCR_YML="https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_rec_onnx/resolve/main/inference.yml"

log() { printf '\n=== %s\n' "$*"; }

dl() { # url dest [min_bytes]
  local url="$1" dest="$2" min="${3:-1024}"
  if [ -f "$dest" ] && [ "$(wc -c <"$dest" | tr -d ' ')" -ge "$min" ]; then
    echo "ya existe: $(basename "$dest")  ($(du -h "$dest" | cut -f1))"
    return 0
  fi
  echo "descargando $(basename "$dest") …"
  curl -fL --retry 5 --retry-delay 3 --retry-all-errors -C - -o "$dest.part" "$url"
  mv "$dest.part" "$dest"
}

log "motor OCR (PP-OCRv6 tiny) + VAD"
dl "$OCR_DET" "$FILES/ppocr_v6_det.onnx" 1000000
dl "$OCR_REC" "$FILES/ppocr_v6_rec.onnx" 1000000
dl "$OCR_YML" "$FILES/ppocr_v6_rec.yml" 10000
dl "$VAD_URL" "$FILES/silero_vad.onnx"   1000000

log "manifest"
python3 - "$DEST" <<'PY'
import json, os, sys
dest = sys.argv[1]
files = os.path.join(dest, "files")
entries = []
for name in sorted(os.listdir(files)):
    if name.startswith(".") or name.endswith(".part"):
        continue
    full = os.path.join(files, name)
    if os.path.isfile(full):
        entries.append({"p": "files/" + name, "s": os.path.getsize(full)})
total = sum(e["s"] for e in entries)
manifest = {"schema": 1, "variant": "lite-ocr", "voices": [],
            "totalBytes": total, "entries": entries}
with open(os.path.join(dest, "manifest.json"), "w", encoding="utf-8") as fh:
    json.dump(manifest, fh, indent=1, sort_keys=True)
    fh.write("\n")
print("manifest: %d entradas, %d bytes (%.1f MiB)" % (len(entries), total, total / 1048576.0))
PY

log "resumen"
du -sh "$FILES" "$DEST"
echo "OK: assets LITE-OCR en $DEST"
