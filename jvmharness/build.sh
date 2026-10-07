#!/bin/bash
# Compila el arnés JVM con el compilador Kotlin embebido de Gradle (sin tocar el build de Android).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
GDLIB="/Users/zota/.gradle/wrapper/dists/gradle-8.11.1-bin/bpt9gzteqjrbo1mjrsomdt32c/gradle-8.11.1/lib"
ORT="$HERE/libs/onnxruntime-1.20.0.jar"
STDLIB="$GDLIB/kotlin-stdlib-2.0.20.jar"

"$HERE/sync.sh"

rm -rf "$HERE/out"
mkdir -p "$HERE/out"

COMPILER_CP="$GDLIB/*"

java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -jvm-target 17 \
  -cp "$ORT:$STDLIB" \
  -d "$HERE/out" \
  $(find "$HERE/src" -name '*.kt' | sort)

echo "compiled -> $HERE/out"
