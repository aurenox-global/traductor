#!/usr/bin/env bash
# Compila llama.cpp y whisper.cpp como librerias estaticas para Android con el NDK.
# Salida: app/src/main/cpp/prebuilt/<lib>/<abi>/*.a
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CPP="$ROOT/app/src/main/cpp"
NDK="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/27.0.12077973}"
TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
BUILDDIR="$ROOT/.nativebuild"
PREBUILT="$CPP/prebuilt"
API=26
ABIS="${ABIS:-arm64-v8a}"
LIBS="${LIBS:-llama whisper}"

mkdir -p "$BUILDDIR" "$PREBUILT"

if [ ! -f "$TOOLCHAIN" ]; then echo "NDK toolchain no encontrado: $TOOLCHAIN" >&2; exit 1; fi

COMMON_FLAGS=(
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN"
  -DANDROID_PLATFORM="android-$API"
  -DANDROID_STL=c++_shared
  -DCMAKE_BUILD_TYPE=Release
  -DBUILD_SHARED_LIBS=OFF
)

for ABI in $ABIS; do
  echo "=================== ABI: $ABI ==================="
  for LIB in $LIBS; do
    SRC="$CPP/$LIB.cpp"
    B="$BUILDDIR/$LIB-$ABI"
    OUT="$PREBUILT/$LIB/$ABI"
    mkdir -p "$OUT"
    echo "--- configure $LIB ($ABI) ---"
    if [ "$LIB" = "llama" ]; then
      EXTRA=(-DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF -DLLAMA_BUILD_TOOLS=OFF -DGGML_OPENMP=OFF)
      TARGETS="llama"
    else
      EXTRA=(-DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_EXAMPLES=OFF -DWHISPER_BUILD_SERVER=OFF -DGGML_OPENMP=OFF)
      TARGETS="whisper"
    fi
    cmake -S "$SRC" -B "$B" -G Ninja \
      "${COMMON_FLAGS[@]}" \
      -DANDROID_ABI="$ABI" \
      "${EXTRA[@]}" \
      > "$B.configure.log" 2>&1 || { echo "CONFIGURE FAIL $LIB $ABI"; tail -40 "$B.configure.log"; exit 1; }

    echo "--- build $LIB ($ABI) ---"
    cmake --build "$B" --target $TARGETS -j "$(sysctl -n hw.ncpu)" > "$B.build.log" 2>&1 || { echo "BUILD FAIL $LIB $ABI"; tail -60 "$B.build.log"; exit 1; }

    rm -f "$OUT"/*.a
    # solo las librerias que necesitamos
    find "$B" -maxdepth 3 -name "libggml*.a" -o -maxdepth 3 -name "libllama.a" -o -maxdepth 3 -name "libwhisper.a" | while read -r f; do
      cp "$f" "$OUT/"
    done
    echo "--- $LIB $ABI artefactos: ---"
    ls -la "$OUT"
  done
done
echo "OK: librerias nativas en $PREBUILT"
