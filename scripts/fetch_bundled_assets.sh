#!/usr/bin/env bash
#
# fetch_bundled_assets.sh — descarga los modelos que van DENTRO de la APK FULL.
#
# NO se ejecuta en el build normal (LITE). Solo es necesario para construir la
# variante FULL (`./gradlew :app:assembleDebug -Pbundled=true`), que añade
# `app/bundled-assets/` como srcDir de assets.
#
# Contenido (todo lo que la app descargaría en el primer arranque):
#   files/nllb_models/nllb_encoder_model_quantized.onnx   NLLB-200 encoder (ONNX int8)
#   files/nllb_models/nllb_decoder_model_merged_quantized.onnx  NLLB-200 decoder (ONNX int8)
#   files/nllb_models/tokenizer.bin                  tokenizador NLLB (del APK)
#   files/ggml-base.bin                              Whisper base (ASR)
#   files/silero_vad.onnx                            VAD Silero v5
#   files/ppocr_v6_det.onnx                          OCR PP-OCRv6 tiny detección
#   files/ppocr_v6_rec.onnx                          OCR PP-OCRv6 tiny reconocimiento
#   files/ppocr_v6_rec.yml                           diccionario OCR (se parsea on-device)
#   piper/<id>/{model.onnx,voice.onnx.json,tokens.txt,espeak-ng-data/**}
#                                                    voces Piper ES y EN del catálogo
#
# Los ficheros NO se versionan (ver .gitignore): se regeneran con este script.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/bundled-assets/bundled"
FILES="$DEST/files"
NLLB_DIR="$FILES/nllb_models"
PIPER="$DEST/piper"
TMP="${TMPDIR:-/tmp}/traductor-bundled"
mkdir -p "$FILES" "$NLLB_DIR" "$PIPER" "$TMP"

HF_NLLB="https://huggingface.co/Xenova/nllb-200-distilled-600M/resolve/main"
HF_WHISPER="https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin"
VAD_URL="https://raw.githubusercontent.com/snakers4/silero-vad/master/src/silero_vad/data/silero_vad.onnx"
OCR_DET="https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_det_onnx/resolve/main/inference.onnx"
OCR_REC="https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_rec_onnx/resolve/main/inference.onnx"
OCR_YML="https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_rec_onnx/resolve/main/inference.yml"
SHERPA_TTS="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"
PIPER_VOICES=("es_AR-daniela-high" "en_US-hfc_female-medium")

log() { printf '\n=== %s\n' "$*"; }

# --- descarga simple con reanudación opcional ---
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

log "modelos de traducción (NLLB ONNX) / ASR / VAD / OCR"
dl "$HF_NLLB/onnx/encoder_model_quantized.onnx"                "$NLLB_DIR/nllb_encoder_model_quantized.onnx" 400000000
dl "$HF_NLLB/onnx/decoder_model_merged_quantized.onnx"         "$NLLB_DIR/nllb_decoder_model_merged_quantized.onnx" 400000000
# El tokenizador se genera en el APK (scripts/build_nllb_tokenizer.py): se copia el asset.
TK_SRC="$ROOT/app/src/main/assets/nllb/tokenizer.bin"
if [ -f "$TK_SRC" ]; then
  cp "$TK_SRC" "$NLLB_DIR/tokenizer.bin"
  echo "tokenizador copiado desde assets ($(du -h "$NLLB_DIR/tokenizer.bin" | cut -f1))"
else
  echo "AVISO: falta $TK_SRC (se omite tokenizer.bin del bundle)" >&2
fi
dl "$HF_WHISPER" "$FILES/ggml-base.bin"              140000000
dl "$VAD_URL"    "$FILES/silero_vad.onnx"            1000000
dl "$OCR_DET"    "$FILES/ppocr_v6_det.onnx"          1000000
dl "$OCR_REC"    "$FILES/ppocr_v6_rec.onnx"          1000000
dl "$OCR_YML"    "$FILES/ppocr_v6_rec.yml"           10000

log "voces Piper (ES + EN)"
for id in "${PIPER_VOICES[@]}"; do
  out="$PIPER/$id"
  if [ -f "$out/model.onnx" ] && [ -f "$out/tokens.txt" ]; then
    echo "voz ya existe: $id"
    continue
  fi
  tar="$TMP/vits-piper-$id.tar.bz2"
  dl "$SHERPA_TTS/vits-piper-$id.tar.bz2" "$tar" 10000000

  echo "extrayendo $id …"
  rm -rf "$TMP/$id"
  mkdir -p "$TMP/$id"
  tar -xjf "$tar" -C "$TMP/$id"
  src="$TMP/$id/vits-piper-$id"
  [ -d "$src" ] || src="$(find "$TMP/$id" -maxdepth 1 -type d -name 'vits-piper-*' | head -1)"

  rm -rf "$out"
  mkdir -p "$out"
  cp "$src/$id.onnx" "$out/model.onnx"
  [ -f "$src/$id.onnx.json" ] && cp "$src/$id.onnx.json" "$out/voice.onnx.json"
  [ -f "$src/tokens.txt" ] && cp "$src/tokens.txt" "$out/tokens.txt"
  [ -d "$src/espeak-ng-data" ] && cp -R "$src/espeak-ng-data" "$out/espeak-ng-data"
  rm -rf "$src" "$TMP/$id"
  echo "voz lista: $id ($(du -sh "$out" | cut -f1))"
done

log "manifest"
python3 "$ROOT/scripts/bundled_manifest.py"

log "resumen"
du -sh "$FILES" "$NLLB_DIR" "$PIPER" "$DEST"
echo "OK: assets bundleados en $DEST"
