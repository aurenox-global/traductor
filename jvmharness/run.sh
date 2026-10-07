#!/bin/bash
# Ejecuta el arnés: run.sh <modelDir> <tokenizer.bin> <src> <tgt> <texto>
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
GDLIB="/Users/zota/.gradle/wrapper/dists/gradle-8.11.1-bin/bpt9gzteqjrbo1mjrsomdt32c/gradle-8.11.1/lib"
ORT="$HERE/libs/onnxruntime-1.20.0.jar"
STDLIB="$GDLIB/kotlin-stdlib-2.0.20.jar"
exec java -cp "$HERE/out:$ORT:$STDLIB" HarnessKt "$@"
