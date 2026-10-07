# jvmharness — arnés JVM para NllbEngine

Ejecuta el **mismo** `NllbEngine.kt` / `NllbTokenizer.kt` del proyecto Android en la
JVM del escritorio, para depurar la inferencia NLLB sin necesidad de un dispositivo
arm64 (con `android.util.Log` y `Context`/`AssetManager` simulados).

No forma parte del build de Android: es una herramienta de desarrollo.

## Requisitos (no versionados)

- `jvmharness/libs/onnxruntime-1.20.0.jar` — el jar de ONNX Runtime para escritorio.
- El compilador de Kotlin embebido en Gradle (lo usa `build.sh`).

Ambos están en `.gitignore` (`jvmharness/libs/`, `jvmharness/out/`).

## Uso

```bash
./jvmharness/sync.sh    # copia NllbEngine/NllbTokenizer/... reales a src/traductor/
./jvmharness/build.sh   # compila a out/
./jvmharness/run.sh <modelDir> <tokenizer.bin> <src> <tgt> "<texto>"
```

`sync.sh` garantiza que el arnés use las fuentes reales del motor (sin copias
divergentes). `build.sh` y `run.sh` invocan el compilador/JVM con el classpath del
Gradle wrapper local; ajusta las rutas de `GDLIB` si tu versión de Gradle cambia.
