#!/bin/bash
# Sincroniza las fuentes REALES del motor (sin copias divergentes) al arnés.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$HERE/../app/src/main/java/com/zota/traductor"
DST="$HERE/src/traductor"
mkdir -p "$DST"
for f in NllbEngine.kt NllbTokenizer.kt NllbLangs.kt Languages.kt Prompts.kt; do
  cp -f "$APP/$f" "$DST/$f"
done
echo "synced: $(cd "$DST" && ls *.kt | tr '\n' ' ')"
