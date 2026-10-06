#!/usr/bin/env bash
# Descarga (clona) las dependencias vendor en los commits fijados.
#
#   llama.cpp    -> 4f5406761517648c23dbd60ea5ade37f77a316c9
#   whisper.cpp  -> 4afec37b797ab531aaf363208d79d541fbc17ff4
#
# Se prefieren los submódulos git (.gitmodules). Si el repo no tiene los
# submódulos inicializados (o se descargó como ZIP, sin .git), este script
# clona los repositorios directamente en esos commits.
#
# Uso:
#   ./scripts/fetch-deps.sh          # submódulos si es posible, si no clona
#   ./scripts/fetch-deps.sh --force  # fuerza el clonado directo
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CPP="$ROOT/app/src/main/cpp"

LLAMA_URL="https://github.com/ggml-org/llama.cpp.git"
WHISPER_URL="https://github.com/ggml-org/whisper.cpp.git"
LLAMA_SHA="4f5406761517648c23dbd60ea5ade37f77a316c9"
WHISPER_SHA="4afec37b797ab531aaf363208d79d541fbc17ff4"

FORCE=0
[ "${1:-}" = "--force" ] && FORCE=1

clone_pinned() {
  local name="$1" url="$2" sha="$3" dest="$CPP/$1"
  echo "==> $name -> $sha"
  if [ -d "$dest/.git" ]; then
    git -C "$dest" fetch --all --tags --prune
  else
    rm -rf "$dest"
    git clone --filter=blob:none "$url" "$dest"
  fi
  git -C "$dest" checkout --force "$sha"
  git -C "$dest" submodule update --init --recursive || true
  echo "    OK: $(git -C "$dest" rev-parse HEAD)"
}

if [ "$FORCE" -eq 0 ] && [ -f "$ROOT/.gitmodules" ] && [ -d "$ROOT/.git" ]; then
  if git -C "$ROOT" submodule update --init --recursive; then
    echo "==> Submódulos inicializados."
    for d in llama.cpp whisper.cpp; do
      [ -e "$CPP/$d/CMakeLists.txt" ] || { echo "Falta $d, forzando clonado…"; FORCE=1; }
    done
  else
    echo "==> Submódulos no disponibles; clonado directo."
    FORCE=1
  fi
fi

if [ "$FORCE" -eq 1 ] || [ ! -e "$CPP/llama.cpp/CMakeLists.txt" ] || [ ! -e "$CPP/whisper.cpp/CMakeLists.txt" ]; then
  clone_pinned "llama.cpp" "$LLAMA_URL" "$LLAMA_SHA"
  clone_pinned "whisper.cpp" "$WHISPER_URL" "$WHISPER_SHA"
fi

echo
echo "Dependencias listas en: $CPP"
echo "Siguiente paso:  ./scripts/build_native.sh"
