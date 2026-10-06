# Traductor — v0.7 (Android nativo, 100% offline)

**Estado: build VERDE.** Se evoluciona el proyecto v0.6 (no se reescribe). La novedad de
v0.7 es el **catálogo de voces Piper**: se añaden 8 idiomas nuevos (polaco, árabe, turco,
coreano, búlgaro, húngaro, rumano y japonés), con preferencia por voces **femeninas**
(verificadas midiendo F0) y, cuando un idioma solo tiene voz masculina, se incluye marcada
como tal. Todo lo anterior (VAD, Whisper, LLM, OCR, descargas, alineación 16 KB, targetSdk 35,
solo arm64-v8a, fallbacks) queda intacto.

Fecha: 2026-10-06 · Directorio: `/Users/zota/.openclaw/workspace/traductor/`
**No se ha publicado nada** (ni push, ni GitHub).

---

# PARTE v0.10 — Variante FULL: modelos y voces empaquetados en la APK

**Estado: ambos builds VERDES.** Se añade una variante **FULL** sin tocar el build
normal (LITE) mediante una **propiedad Gradle** (`-Pbundled=true`), **sin product
flavors**. La APK FULL lleva dentro (assets) todos los modelos que antes se
descargaban en el primer arranque, y en runtime los **copia de assets a `filesDir`**
(con progreso) en vez de descargarlos.

Fecha: 2026-10-06 · Directorio (nuevo): `/Users/zota/Desktop/CODE/traductor/`
**No se ha publicado nada** (ni push, ni releases).

## 1. Resultado de los builds (verificado)

| Dato | LITE (normal) | FULL (bundleada) |
|---|---|---|
| Comando | `./gradlew :app:assembleDebug :app:testDebugUnitTest` | `./gradlew :app:assembleDebug :app:testDebugUnitTest -Pbundled=true` |
| Resultado | BUILD SUCCESSFUL | BUILD SUCCESSFUL |
| Ruta EXACTA del APK | `app/build/outputs/apk/debug/app-debug.apk` | `app/build/outputs/apk/full/traductor-full-arm64-debug.apk` |
| Tamaño | **66 319 222 B** (≈63,2 MiB) | **946 780 860 B** (≈902,9 MiB) |
| sha256 | `562586f6e7961dce9bf4c4e6cf6ac8b89413fe3c6c299d20e064401ecac11898` | `d95cf1fd1cc667878b97d3639bc1eabd7d74bd99d99ebb67831c2477faa83549` |
| Tests host | `CoreTest: 35 · OcrTest: 20` = **55, failures=0** | **55, failures=0** |

El APK FULL se copia además a un nombre distinto (tarea `copyFullApk`, encadenada
con `finalizedBy`) para no pisar el artefacto normal.

## 2. Contenido del APK FULL (`unzip -l`)

| Entrada (assets) | Bytes |
|---|---|
| `bundled/files/Qwen3.5-0.8B-Q4_K_M.gguf` | 527 502 816 |
| `bundled/files/ggml-base.bin` | 147 951 465 |
| `bundled/files/silero_vad.onnx` | 2 327 524 |
| `bundled/files/ppocr_v6_det.onnx` | 1 780 590 |
| `bundled/files/ppocr_v6_rec.onnx` | 4 462 639 |
| `bundled/files/ppocr_v6_rec.yml` | 55 571 |
| `bundled/piper/es_AR-daniela-high/model.onnx` | 113 851 893 |
| `bundled/piper/en_US-hfc_female-medium/model.onnx` | 63 149 198 |
| `bundled/manifest.json` | 63 004 |

- Cada voz incluye `tokens.txt`, `voice.onnx.json` y `espeak-ng-data/` (**355
  ficheros por voz**). Total de entradas del APK: **1 787**.
- Los modelos ya comprimidos van **Stored** (0 % deflate), gracias a
  `androidResources { noCompress += gguf,bin,onnx,txt,json }`.
- El build FULL tarda **~36 s** (tras cachear lo nativo) y pide ~26 GB libres en disco.

## 3. Qué se implementó

- **`app/build.gradle.kts`**: `val bundled = findProperty("bundled")?.toBoolean() ?: false`;
  `buildConfigField("boolean", "BUNDLED_MODELS", ...)`; `buildFeatures.buildConfig = true`;
  `noCompress`; y, **solo si `bundled`**, `sourceSets.main.assets.srcDir("bundled-assets")`
  + tarea `copyFullApk`. El build LITE no añade srcDirs ni bundlea nada.
- **`BundledAssets.kt`** (nuevo): guiado por `bundled/manifest.json` (722 entradas con
  tamaño), copia los assets que falten a `filesDir` (modelos a la raíz; voces a
  `piper_voices/<id>/`) con progreso, escribe los `info.txt`/`sid` de cada voz y aplica
  los modelos por defecto (Whisper base + Qwen + voz ES) sin pisar selecciones del usuario.
  `destRelative()` es puro (testeable en host).
- **`MainActivity.ensureModels()`**: en FULL copia primero los bundleados; después sigue la
  lógica de descarga normal para lo que falte (no hay cambios en LITE).
- **`OcrModels.ensureReady()`** y **`PiperVoiceManager.download()`**: si el modelo/voz va
  bundleado, se copia; si no, se descarga como siempre.
- **`scripts/fetch_bundled_assets.sh`** + **`scripts/bundled_manifest.py`** (nuevos):
  descargan los modelos y generan el manifest. `app/bundled-assets/` está en `.gitignore`.

## 4. NO roto / comprobado

- LITE: mismo comando, mismos tests verdes, **0 entradas `assets/bundled/`** en la APK.
- FULL: los 6 modelos + 2 voces están *dentro* (verificado con `unzip -l`).
- El resto del pipeline (VAD, Whisper, LLM, OCR, TTS, historial) no se toca.

## 5. Pendiente / no verificado (honesto)

- **Sin test en dispositivo**: `adb devices` no lista ningún terminal, así que la copia
  assets→`filesDir` en runtime no se ha ejecutado en un móvil real (sí están cubiertas por
  test las rutas y la pureza del mapeo). Recomendado: instalar la FULL en el OnePlus PLB110
  y comprobar el primer arranque (progreso de copia + modelo/voces activos).
- El APK LITE crece ~148 KB frente al build previo por la clase nueva `BundledAssets` y el
  `BuildConfig`; el comportamiento es idéntico (no bundlea modelos).
- Sin bloques: el build FULL (≈900 MB) completó sin problemas de tamaño/tiempo.

---

# PARTE v0.7 — Voces Piper: pl/ar/tr/ko/bg/hu/ro/ja (género verificado por F0)

## 1. Resultado del build (verificado)

| Dato | Valor |
|---|---|
| Comando | `./gradlew :app:assembleDebug :app:testDebugUnitTest` |
| Resultado | `BUILD SUCCESSFUL` |
| Ruta EXACTA del APK | `/Users/zota/.openclaw/workspace/traductor/app/build/outputs/apk/debug/app-debug.apk` |
| Tamaño | **66 013 378 bytes** (≈62,96 MiB) |
| sha256 | `c89bdea650e172887874f4c5ba4d5d2446ce216bf9cf60bd212fd61e9ff96ff1` |
| Tests host | `CoreTest: tests=30 failures=0` · `OcrTest: tests=20 failures=0` (**50 en total**) |
| Versión | `versionCode = 7` · `versionName = "0.7.0"` |

## 2. Tabla idioma → voz → F0 → género (sherpa-onnx 1.13.8, `/tmp/piperprobe/venv`)

| Idioma | Voz (id) | Fuente | F0 | Género |
|---|---|---|---|---|
| Polaco (pl) | `pl_PL-gosia-medium` | tar sherpa | 206,1 Hz | ♀ femenina |
| Árabe (ar) | `ar_JO-kareem-medium` | tar sherpa | 105,5 Hz | ♂ masculina (única) |
| Turco (tr) | `tr_TR-dfki-medium` | tar sherpa | 109,2 Hz | ♂ masculina (única) |
| Coreano (ko) | `ko_KR-kss-medium` | crudo rhasspy | 306,2 Hz | ♀ femenina |
| Búlgaro (bg) | `bg_BG-dimitar-medium` | crudo rhasspy | 113,1 Hz | ♂ masculina (única) |
| Húngaro (hu) | `hu_HU-anna-medium` | tar sherpa | 183,8 Hz | ♀ femenina |
| Rumano (ro) | `ro_RO-mihai-medium` | tar sherpa | 129,7 Hz | ♂ masculina (única) |
| Japonés (ja) | `ja_JP-hi_fi_captain-medium` (sid 0) | crudo rhasspy | 268,9 Hz | ♀ femenina |

Método: `median_f0` (autocorrelación por tramas de 40 ms; femenino ≥ 165 Hz) sobre audio
sintetizado con el `espeak-ng-data` compartido. Candidatas descartadas: polaco bass 81,1 M /
darkman 112,5 M / mc_speech 111,9 M; húngaro imre 108,4 M; japonés sid 1 = 158,6 M.

## 3. Qué se implementó

### A) Catálogo — `PiperVoiceManager.kt`
- `spec()` gana el parámetro `gender` (por defecto `"F"`); nuevo `rawSpec()` para voces
  SIN paquete oficial de sherpa (`tarUrl = ""` → descarga directa `.onnx` + `.onnx.json`).
- 8 entradas nuevas en `CATALOG` con etiqueta `idioma · nombre · (calidad) ♀/♂`.
- `download()`: si `tarUrl` está vacío salta directo a `downloadRaw()` (evita el 404). Las
  voces con paquete siguen igual (tar principal + respaldo crudo).

### B) Tokens multi-codepoint (ko/ja) — `OnnxMeta.kt`
- sherpa-onnx exige que cada token de `tokens.txt` sea **un único codepoint Unicode**; los
  mapas de coreano y japonés incluyen bigramas IPA (`aɪ`, `aʊ`, `ɔɪ`, `eɪ`, `oʊ`) que hacían
  **abortar** el lector (`Error when reading tokens at Line aɪ 161`).
- `tokensFromPhonemeIdMap(entries, dropMultiCodepoint = true)` los omite; la fonemización de
  ko/ja no los emite, así que la síntesis queda correcta (host: ko 306,2 Hz · ja 268,9 Hz).
  El comportamiento por defecto no filtra (compatibilidad previa intacta).

### C) Idiomas — `Languages.kt`
- Añadidos **bg (Búlgaro 🇧🇬)** y **hu (Húngaro 🇭🇺)** (pl, ar, tr, ko, ro, ja ya estaban).

### D) Documentación
- README.md (EN+ES): 30+ idiomas, tabla de voces nuevas, nota de preferencia femenina.
- docs/index.html: idiomas, TTS y versión v0.7.0.
- strings.xml: descripción de Piper matizada (femeninas; masculinas solo si son únicas).

## 4. Archivos nuevos / modificados

```
app/build.gradle.kts                          versionCode 7 / versionName 0.7.0
app/src/main/java/.../PiperVoiceManager.kt    spec() con género, rawSpec(), +8 voces, download() crudo directo
app/src/main/java/.../OnnxMeta.kt             tokensFromPhonemeIdMap(..., dropMultiCodepoint)
app/src/main/java/.../Languages.kt            +bg, +hu
app/src/main/res/values/strings.xml           descripción Piper matizada
app/src/test/.../CoreTest.kt                  +3 tests, 2 ajustados
README.md · docs/index.html · SUMMARY.md      documentación
```

## 5. NO roto (reverificado)

- Build verde: 50 tests host (CoreTest 30 + OcrTest 20), 0 fallos.
- Catálogo es/en/fr/de/it/zh intacto (todas siguen marcadas `"F"`).
- VAD, Whisper, LLM, OCR, pipeline, descargas y alineación 16 KB sin tocar.

## 6. Pendiente / no verificado (honesto)

- **Sin dispositivo**: descarga y conversión cruda no probadas en el móvil (sí en host con
  el mismo algoritmo). El APK compila y los tests pasan.
- **Japonés**: la voz usa `phoneme_type = japanese`; sherpa avisa de codepoints combinatorios
  omitidos (U+031e, U+0308). Sintetiza y mide F0, pero la calidad fonética no se ha validado.
- **Calidad subjetiva** de las voces masculinas (ar/tr/bg/ro) no evaluada en el móvil.

## 7. Siguiente paso concreto

1. Instalar el APK en el OnePlus PLB110 y descargar/activar las voces nuevas.
2. Verificar en dispositivo la conversión cruda de ko/bg/ja (Ajustes → descargar → Probar voz).
3. Escuchar ko/ja por si el filtrado de bigramas afecta a la prosodia.

---

# Traductor — v0.6 (Android nativo, 100% offline)

**Estado: build VERDE.** Se evoluciona el proyecto v0.5 (no se reescribe). La novedad de
v0.6 es de **producto y publicación**: crédito del autor, icono nuevo estilo Apple, README
bilingüe, web lista para GitHub Pages y el repositorio preparado (git init + submódulos) para
un `git push` manual. Nada funcional del traductor (VAD, Whisper, Piper, OCR, descargas,
alineación 16 KB, targetSdk 35, solo arm64-v8a, fallbacks) se ha roto.

Fecha: 2026-10-06 · Directorio: `/Users/zota/.openclaw/workspace/traductor/`
**No se ha publicado nada** en ningún sitio (ni push, ni GitHub).

---

# PARTE v0.6 — Crédito, icono, README bilingüe, web y preparación del repo

## 1. Resultado del build (verificado)

| Dato | Valor |
|---|---|
| Comando | `./gradlew :app:assembleDebug :app:testDebugUnitTest` |
| Resultado | `BUILD SUCCESSFUL` |
| Ruta EXACTA del APK | `/Users/zota/.openclaw/workspace/traductor/app/build/outputs/apk/debug/app-debug.apk` |
| Tamaño | **66 013 322 bytes** (≈62,95 MiB) |
| sha256 | `89ba3eeab9feec5216c6ffbd34a834a534158ee17f63ef011ef8da748f4a8232` |
| ABI empaquetada | `arm64-v8a` (únicamente; minSdk 26, targetSdk 35) |
| Tests host | `CoreTest: tests=27 failures=0` · `OcrTest: tests=20 failures=0` (**47 en total**) |
| Alineación 16 KB | `zipalign -c -P 16 -v 4 app-debug.apk` → `Verification successful` ✅ |
| Versión | `versionCode = 6` · `versionName = "0.6.0"` |

## 2. Crédito y «Acerca de» en Ajustes

- **Strings localizados ES/EN**: `app/src/main/res/values/strings.xml` (ES) y **nuevo**
  `app/src/main/res/values-en/strings.xml` (EN, traducido completo).
- Nuevas cadenas: `settings_about_title`, `settings_about_version`, `settings_credit`,
  `settings_credit_author`, `settings_footer_note`.
- **Layout** `activity_settings.xml`: sección «Acerca de» al final (separador + nombre de la
  app + versión) y línea de crédito **«Creado por Andres Mag · Cuba»** con el nombre en
  negrita (`Spannable` en `SettingsActivity.bindAbout()`). La nota final, antes *hardcoded*,
  ahora usa `@string/settings_footer_note` (ES/EN).
- Versión leída en runtime con `packageManager.getPackageInfo(...)` (sin depender de BuildConfig).

## 3. Icono de app (PIL, estilo Apple)

Generado por `assets/icon/generate_icons.py` (Pillow, super-sampling 4×, determinista):

- Fondo **squircle** con degradado sutil de azul brillante (**#4FA8FF → #0A6FD8**, referencia
  **#0A84FF**) y glifo blanco minimalista: **dos bocadillos de chat superpuestos**, sin texto,
  legible a 48 px.
- PNGs en **todas las densidades** para `ic_launcher` y `ic_launcher_round`:
  `mipmap-mdpi/48`, `mipmap-hdpi/72`, `mipmap-xhdpi/96`, `mipmap-xxhdpi/144`,
  `mipmap-xxxhdpi/192`.
- **512×512** para tienda: `assets/icon/ic_launcher_512.png`; para web:
  `assets/icon/icon-web-512.png` y copia `docs/icon.png`; vista previa: `assets/icon/preview.png`.
- **Icono adaptativo (Android 8+)**: `mipmap-anydpi-v26/ic_launcher.xml` y
  `ic_launcher_round.xml` → `@drawable/ic_launcher_background` (vector con gradiente) +
  `@drawable/ic_launcher_foreground` (glifo, contenido en la zona segura) + `monochrome`
  (iconos temáticos Android 13+). `colors.xml#ic_background` actualizado a `#FF0A84FF`.

## 4. README.md profesional y bilingüe

`README.md` (16,5 KB): portada con el icono, secciones **EN** y **ES** completas — qué es,
características, arquitectura (diagrama ASCII del pipeline JNI), requisitos, **cómo compilar**,
estructura del repo, **privacidad** (todo local), **licencias de terceros** (llama.cpp,
whisper.cpp, sherpa-onnx, PaddleOCR, ONNX Runtime, Silero VAD, Piper/espeak-ng, modelo Qwen)
y **créditos** (Andres Mag · Cuba). Enlaza la web (`docs/index.html`).

## 5. Web (docs/index.html, GitHub Pages)

`docs/index.html` (25 KB): **bilingüe ES/EN con conmutador** (persistido en localStorage),
**tema claro/oscuro** (conmutador + `prefers-color-scheme`), CSS propio minimalista y
responsive, **sin dependencias externas ni CDNs** (carga offline), icono local.
Secciones: qué es, características, cómo funciona (pipeline), modelos/primer uso,
privacidad, compilar, créditos. **Capturas: no generadas** (no hay dispositivo/emulador en
el entorno); en su lugar incluye el icono y un diagrama del pipeline en texto.

## 6. Git y submódulos

- `.gitignore` completo: `build/`, `app/build/`, `.gradle/`, `.kotlin/`, `.nativebuild/`,
  `prebuilt/` (`app/src/main/cpp/prebuilt/`), `.cxx/`, `local.properties`, `*.apk`, `*.a`,
  `.DS_Store`, caches, keystores.
- **Vendor como submódulos git** (`git init` local, commit `6eadeb3`):

  | Submódulo | Commit fijado | Estado |
  |---|---|---|
  | `app/src/main/cpp/llama.cpp` | `4f5406761517648c23dbd60ea5ade37f77a316c9` | gitlink 160000 |
  | `app/src/main/cpp/whisper.cpp` | `4afec37b797ab531aaf363208d79d541fbc17ff4` | gitlink 160000 |

  Se eliminaron sus `.git` internos y se registraron como gitlinks en el índice con
  `.gitmodules` (`https://github.com/ggml-org/llama.cpp.git`, `.../whisper.cpp.git`). Un
  `git submodule update --init` los clona en esos commits exactos. `git submodule status`
  los muestra con el prefijo `-` (sin clonar en el checkout local: normal).
- **Fallback documentado**: `scripts/fetch-deps.sh` — usa los submódulos si existen y, si no
  (p. ej. ZIP sin `.git`), clona `llama.cpp`/`whisper.cpp` en los commits fijados. `--force`
  para clonado directo.
- **NO se ha hecho push ni creado nada en GitHub.**

## 7. Tamaño de lo que se subiría

- **~33 MB** en **98 ficheros** rastreados (sin los submódulos). Los mayores:
  `libsherpa-onnx-jni.so` 23 MB, `espeak-ng-data.zip` 8,6 MB, `sherpa-onnx-1.13.8.jar` 236 KB.
- Los vendor (llama.cpp 176 MB, whisper.cpp 43 MB) **no** van al repo: quedan como submódulos.
- `.git` local: ~19 MB.

## 8. Entregables / evidencia

| Entregable | Ruta |
|---|---|
| APK (build verde) | `app/build/outputs/apk/debug/app-debug.apk` · 66 013 322 B · sha256 `89ba3eea…f4a8232` |
| Script + fuentes del icono | `assets/icon/generate_icons.py` · `assets/icon/ic_launcher_512.png` · `icon-web-512.png` · `preview.png` |
| Icono web | `docs/icon.png` |
| README bilingüe | `README.md` |
| Web bilingüe | `docs/index.html` |
| Repo preparado | `.git` (commit `6eadeb3`), `.gitignore`, `.gitmodules`, `scripts/fetch-deps.sh` |

## 9. Pendiente / no verificado (honesto)

- **Sin dispositivo**: el icono y la UI de «Acerca de» compilan y empaquetan, pero no se han
  visto en pantalla real; el icono sí se inspeccionó renderizado (vista previa PIL).
- **Capturas de la web**: no generadas (requieren dispositivo/emulador).
- **Push**: intencionadamente no realizado (lo hace el autor).

---

# Historial — v0.5 (Android nativo, 100% offline)

**Estado: build VERDE.** Se evoluciona el proyecto v0.4 (no se reescribe). La novedad de
v0.5: **OCR de fotos** para traducir imágenes al estilo Google Translate. El usuario pulsa
📷, elige «Hacer foto» (cámara + FileProvider, permiso CAMERA) o «Elegir de galería»
(SAF/`ACTION_OPEN_DOCUMENT`, sin permisos), y la app extrae el texto **100% offline** con
**PaddleOCR PP-OCR (ONNX)** reutilizando el `onnxruntime-android` que ya traía el proyecto
(el mismo `libonnxruntime.so` del VAD Silero). El texto reconocido aparece **editable** en el
panel de entrada y se traduce con el pipeline existente (idiomas origen/destino respetados).
Nada de lo anterior (VAD, Whisper, Piper, descargas, alineación 16 KB, targetSdk 35, solo
arm64-v8a, fallbacks) se ha roto.

Fecha: 2026-10-06 · Directorio: `/Users/zota/.openclaw/workspace/traductor/`
**No se ha publicado nada** en ningún sitio.

---

# PARTE v0.5 — OCR de fotos (PP-OCR ONNX) → texto → traducción

## 1. Resultado del build (verificado)

| Dato | Valor |
|---|---|
| Comando | `./gradlew :app:assembleDebug :app:testDebugUnitTest` |
| Resultado | `BUILD SUCCESSFUL` |
| Ruta EXACTA del APK | `/Users/zota/.openclaw/workspace/traductor/app/build/outputs/apk/debug/app-debug.apk` |
| Tamaño | **71 326 630 bytes** (≈68,0 MiB) |
| sha256 | `b6f92e151e323d1d94c21368d503662c195c8012ea1b6ec4ce8c1ea6689153dc` |
| ABI empaquetada | `arm64-v8a` (únicamente; minSdk 26, targetSdk 35) |
| Tests host | `CoreTest: tests=26 failures=0 errors=0` · `OcrTest: tests=20 failures=0 errors=0` (**46 en total**) |

- **Alineación 16 KB**: `zipalign -c -P 16 -v 4 app-debug.apk` → `Verification successful`
  (los 6 `.so` siguen alineados; el OCR no añade nativos nuevos: usa el
  `libonnxruntime.so`/`libonnxruntime4j_jni.so` ya presentes). ✅
- **Manifest fusionado**: `android:minSdkVersion=26`, `android:targetSdkVersion=35`,
  `uses-permission CAMERA`, `uses-feature camera (required=false)`,
  `provider com.zota.traductor.fileprovider`. ✅

## 2. Motor OCR elegido y verificado

**Decisión: PP-OCRv6 tiny (oficial PaddlePaddle, Apache-2.0).** Es el más pequeño con
cobertura latino + chino + números y su diccionario viene embebido en el `inference.yml`
oficial (se parsea y se guarda como texto plano en el dispositivo).

| Fichero | URL (verificada HTTP 200) | bytes |
|---|---|---|
| `ppocr_v6_det.onnx` | `https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_det_onnx/resolve/main/inference.onnx` | 1 780 590 |
| `ppocr_v6_rec.onnx` | `https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_rec_onnx/resolve/main/inference.onnx` | 4 462 639 |
| `ppocr_v6_rec.yml` | `https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_rec_onnx/resolve/main/inference.yml` | 55 571 |
| `ppocr_v6_dict.txt` | generado en el dispositivo al parsear el `inference.yml` | 6 904 caracteres |

Parámetros tomados del `inference.yml` oficial (y usados tal cual en Kotlin):

- **det**: resize a lado mayor ≤ 960 redondeado a múltiplos de 32, canal BGR, `/255`,
  `mean=[0.485,0.456,0.406]`, `std=[0.229,0.224,0.225]`; postproceso DB con
  `thresh=0.2`, `box_thresh=0.4`, `unclip_ratio=1.4`.
- **rec**: alto fijo **48**, ancho proporcional, BGR, `/255`, `(x-0.5)/0.5`; salida
  `[1, T, 6906]` = 1 blank + 6 904 caracteres + 1 espacio (clase extra).

**Alternativa preparada (no activada por defecto)**: PP-OCRv4 de RapidOCR
(`ch_PP-OCRv4_det_infer.onnx` 4 745 517 B, `ch_PP-OCRv4_rec_infer.onnx` 10 857 958 B,
`ppocr_keys_v1.txt` 26 250 B; URLs verificadas HTTP 200) para añadir más scripts después.

### Verificación end-to-end del pipeline (host, Python + onnxruntime 1.24.3)

Antes de portar a Kotlin se reprodujo el pipeline completo (det → DB → recorte con
perspectiva → rec → CTC) con Python y los **mismos** modelos ONNX oficiales, sobre imágenes
sintéticas:

| Imagen | Salida OCR |
|---|---|
| «Hello World 12345» | `Hello World 12345` ✅ |
| «Traduce esto offline» | `Traduce esto offline` ✅ |
| «Small print line one» / «Second line 2026» (2400×1600) | ambas líneas, escala correcta ✅ |
| «你好世界 2026» / «Hello 中文混排» (fuente Songti) | `你好世界2026` / `Hello中文混排` ✅ |

También se comprobó que el detector devuelve el mapa a la **misma resolución** que la entrada
(y el código Kotlin lee además las dimensiones reales de la salida ONNX por seguridad).

## 3. Qué se implementó

### A) UI: botón 📷 + elección de origen

- Nuevo `ImageButton` `btnCamera` (icono `ic_camera`) en el panel de entrada, junto al 🎤.
- Al pulsarlo, un `MaterialAlertDialog` con **«Hacer foto»** y **«Elegir de galería»**.
- **Foto**: permiso `CAMERA` en runtime (`RequestPermission`); la captura usa
  `ActivityResultContracts.TakePicture` con un `Uri` de **FileProvider**
  (`authority = com.zota.traductor.fileprovider`, `@xml/file_paths` → `cache-path camera/`).
  Si no hay app de cámara (`ActivityNotFoundException`) se avisa sin romper nada.
- **Galería**: `ActivityResultContracts.OpenDocument` con `image/*` (SAF, **sin permisos**).

### B) Motor OCR offline (Kotlin puro + onnxruntime-android)

- `OcrImage` / `OcrPreprocess`: resize bilineal, plan de resize (det/rec), normalización
  CHW/BGR y **recorte con corrección de perspectiva** (homografía 3×3 resuelta por
  eliminación gaussiana). Todo sin `Bitmap` → **testeable en host**.
- `DbPostProcess`: binarizado, componentes 4-conectados, `minAreaRect` (cierre convexo +
  calipers rotatorios) y `unclip`. Sin OpenCV.
- `CtcDecoder`: decodificación CTC voraz + confianza.
- `OcrDict`: parser del bloque `character_dict:` del `inference.yml` (comillas simples/dobles,
  `''` escapado y líneas como `- 　` para el espacio ideográfico).
- `OcrEngine`: sesiones ONNX (det+rec) reutilizadas, inferencia y ensamblado
  (líneas ordenadas por orden de lectura).
- `ImageLoad`: decodificación con **downscale** (lado mayor ≤ 2560) y **orientación EXIF**
  (`androidx.exifinterface`).
- `OcrModels`: catálogo + descarga bajo demanda con `ModelManager.downloadTo` (progreso) y
  generación del diccionario en `filesDir`.

### C) Integración con el pipeline existente

- El texto OCR se escribe en `editInput` (**editable**, por si hay que corregirlo), se
  muestra el estado y se lanza la traducción con el flujo ya existente (`translateNowManual`),
  respetando idioma origen/destino, historial y TTS.
- Los modelos OCR **no** son obligatorios en el primer arranque: se descargan solo la primera
  vez que se usa el 📷 (≈6,4 MB) y **no alteran** la descarga inicial (Whisper + Qwen).

## 4. Archivos nuevos / modificados

```
app/build.gradle.kts                    + androidx.exifinterface:exifinterface:1.3.7
app/src/main/AndroidManifest.xml        + permiso CAMERA, uses-feature camera, FileProvider
app/src/main/res/xml/file_paths.xml     NUEVO  rutas del FileProvider (cache/camera)
app/src/main/res/drawable/ic_camera.xml NUEVO  icono 📷
app/src/main/res/layout/activity_main.xml + btnCamera
app/src/main/res/values/strings.xml     + textos de OCR/cámara
app/src/main/java/com/zota/traductor/
    OcrImage.kt        NUEVO  OcrImage + OcrBox + OcrPreprocess (resize/normalización/recorte)
    DbPostProcess.kt   NUEVO  detector DB: componentes + minAreaRect + unclip + orden lectura
    CtcDecoder.kt      NUEVO  decodificación CTC voraz
    OcrDict.kt         NUEVO  parser del diccionario del inference.yml
    OcrModels.kt       NUEVO  catálogo/descarga de modelos OCR (v6 por defecto, v4 preparado)
    OcrEngine.kt       NUEVO  sesiones ONNX e inferencia del pipeline OCR
    ImageLoad.kt       NUEVO  decodificación con downscale + orientación EXIF
    MainActivity.kt    + 📷, cámara/FileProvider, galería SAF, runOcr(), liberación del motor
app/src/test/java/com/zota/traductor/
    OcrTest.kt         NUEVO  20 tests de host (diccionario, preproceso, cajas DB, CTC)
```

## 5. Evidencia sin dispositivo (tests host nuevos)

`OcrTest` (20 tests, verde) cubre los tres puntos pedidos **con vectores sintéticos**:

- **Preproceso/letterbox**: `detPlan` (límite 960 + múltiplos de 32, redondeo bancario como
  PaddleOCR), `recPlan` (alto 48), resize bilineal, orden **BGR/CHW** y normalización
  (det `mean/std` y rec `-1..1`).
- **Extracción de cajas DB**: `extractBoxes` sobre mapas de probabilidad sintéticos (2 cajas
  con centro/score correctos, descarte por `box_thresh`, ruido puntual ignorado), `minAreaRect`
  y `unclip`, más el orden de lectura.
- **Decodificación CTC**: colapso de repeticiones, borrado de blanks, clase extra de espacio y
  diccionario mixto latino+chino.

> Un test detectó un bug real (orden de esquinas `tr`/`bl` invertido) que habría estropeado
> el recorte en perspectiva en el dispositivo; corregido antes de cerrar.

## 6. NO roto (reverificado)

- `CoreTest` sigue en verde (26 tests) → VAD, segmentador, prompts, idiomas, OnnxMeta y
  paquetes Piper intactos.
- Se conservan los 6 `.so` del APK y la alineación 16 KB; `abiFilters` solo `arm64-v8a`,
  `minSdk 26`, `targetSdk 35`.
- La descarga inicial (Whisper base + Qwen) y los flujos de importación no se tocan.

## 7. Pendiente / no verificado (honesto) — y bloqueos

- **Sin dispositivo**: el OCR no se ha ejecutado en el móvil (no hay emulador/terminal en el
  entorno de build). La lógica está verificada en host (Kotlin + Python) pero **falta la
  prueba real** de cámara/galería y del runtime ONNX en Android.
- **Calidad OCR en fotos reales**: medida solo con imágenes sintéticas; falta probar con
  fotos de móvil (perspectiva, iluminación, texto pequeño). Se puede subir el límite de
  detección (960 → 1280) si hiciera falta.
- **Idiomas no latinos más allá del chino** (japonés, coreano, cirílico, árabe…): el
  diccionario v6 cubre una parte, pero no se ha validado; el diseño (`OcrModels`) ya deja
  listo cambiar/añadir modelos y diccionarios.
- **Detección de orientación del texto** (90°/vertical) no implementada.

## 8. Siguiente paso concreto

1. `adb install -r app/build/outputs/apk/debug/app-debug.apk` (OnePlus PLB110, arm64-v8a).
2. Probar 📷 → «Hacer foto» y «Elegir de galería»: la primera vez descarga ≈6,4 MB de modelos
   OCR; comprobar que el texto aparece editable y se traduce.
3. Si algo falla: `adb logcat -s OcrEngine Pipeline MainActivity`.

---

# PARTE v0.4 — Paquetes Piper de sherpa-onnx (.tar.bz2): descargar + importar

## 1. Resultado del build (verificado)

| Dato | Valor |
|---|---|
| Comando | `./gradlew :app:assembleDebug :app:testDebugUnitTest` |
| Resultado | `BUILD SUCCESSFUL` |
| Ruta EXACTA del APK | `/Users/zota/.openclaw/workspace/traductor/app/build/outputs/apk/debug/app-debug.apk` |
| Tamaño | **71 389 265 bytes** (≈68,1 MiB) |
| sha256 | `549136e0bb11009c1e7052063a0b2317945f38aa303c3185c31649d59df163ab` |
| ABI empaquetada | `arm64-v8a` (únicamente; minSdk 26, targetSdk 35) |
| Tests host | `com.zota.traductor.CoreTest: tests=24 failures=0 errors=0 skipped=0` (antes 19) |

### Contenido nativo del APK (`unzip -l | grep .so`)

```
  1292896  01-01-1981 01:01   lib/arm64-v8a/libc++_shared.so
  4513768  01-01-1981 01:01   lib/arm64-v8a/libllamajni.so        <- llama.cpp + JNI
 17571152  01-01-1981 01:01   lib/arm64-v8a/libonnxruntime.so     <- ONNX Runtime (VAD)
    82056  01-01-1981 01:01   lib/arm64-v8a/libonnxruntime4j_jni.so
 24169352  01-01-1981 01:01   lib/arm64-v8a/libsherpa-onnx-jni.so <- Piper/VITS + espeak-ng + onnxruntime (static-link)
  1739496  01-01-1981 01:01   lib/arm64-v8a/libwhisperjni.so      <- whisper.cpp + JNI
```

Sin cambios respecto a v0.3 (los mismos 6 `.so`; el crecimiento del APK ~4,5 MB se debe a
las clases de `commons-compress` + sus dependencias, ver §3C).

### Verificaciones extra (sin dispositivo)

- **Alineación 16 KB** (Android 15/16): `zipalign -c -P 16 -v 4` → `Verification successful`
  para los 6 `.so` (incluido `libsherpa-onnx-jni.so`). ✅
- **ABI**: solo `arm64-v8a`; `minSdkVersion=26`, `targetSdkVersion=35` (del manifest fusionado). ✅
- **Extensión real probada**: el paquete `vits-piper-es_AR-daniela-high.tar.bz2` (115 562 134 B)
  se descomprimió con **el mismo algoritmo** (`PiperTar.classify` + streaming) en la JVM con
  solo **256 MB de heap**: `model=true json=true tokens=true espeakFiles=355 rejected=0`,
  131 851 732 B extraídos en ~7 s, `model.onnx=113 851 893 B`, `tokens.txt=940 B`,
  `espeak-ng-data/` con 120 entradas. **Sin OOM** → el streaming funciona. ✅

## 2. Datos verificados de los paquetes

URL base: `https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-<id>.tar.bz2`
(todas responden **HTTP 200**; tamaño real = `content-length`):

| id | bytes `.tar.bz2` |
|---|---|
| es_AR-daniela-high | 115 562 134 |
| es_MX-claude-high | 67 207 890 |
| es_ES-sharvard-medium | 80 318 184 |
| en_US-hfc_female-medium | 67 228 166 |
| en_US-lessac-medium | 67 230 653 |
| fr_FR-siwis-medium | 67 207 459 |
| de_DE-ramona-low | 67 084 795 |
| de_DE-eva_k-x_low | 26 521 242 |
| it_IT-paola-medium | 67 221 173 |

Estructura interna (verificada con `tar tjf`):
`vits-piper-<id>/<id>.onnx`, `<id>.onnx.json`, `tokens.txt`, `MODEL_CARD`, `espeak-ng-data/…`.
El `.onnx` **ya trae la metadata piper/sherpa incrustada**
(`comment=piper`, `language=Spanish`, `voice=es-419`, `has_espeak=1`, `sample_rate=22050`),
por eso **no** hay que inyectar metadata ni regenerar `tokens.txt`.

## 3. Qué se implementó

### A) DESCARGAR paquetes sherpa (fuente principal) — `PiperVoiceManager.kt`

- `Spec` gana `tarUrl` = `…/tts-models/vits-piper-<id>.tar.bz2`; las voces crudas de rhasspy
  (`onnxUrl`/`jsonUrl`) se conservan como **respaldo** si el paquete fallara.
- `download()` → 1) `downloadTar()` (paquete oficial), 2) si falla → `downloadRaw()` (rhasspy + conversión).
- Catálogo (solo femeninas, la 1ª de cada idioma por defecto):
  **es** = `es_AR-daniela-high` (def.) / `es_MX-claude-high` / `es_ES-sharvard-medium`(sid=1),
  **en** = `en_US-hfc_female-medium` (def.) / `en_US-lessac-medium`,
  **fr** = `fr_FR-siwis-medium`, **de** = `de_DE-ramona-low` (def.) / `de_DE-eva_k-x_low`,
  **it** = `it_IT-paola-medium`.

### B) IMPORTAR paquete (SAF) — `importFromUris()`

- Si el primer fichero es `.tar.bz2`/`.tbz2`/`.tbz`/`.tar.gz`/`.tgz`/`.tar` → `importArchive()`:
  se extrae en streaming **directamente del `InputStream` de la SAF** (sin copia intermedia).
- Si no, se mantiene la importación clásica multi-fichero `.onnx` + `.onnx.json` + `tokens.txt`.
- El id del paquete se normaliza (`vits-piper-es_AR-daniela-high.tar.bz2` → `es_AR-daniela-high`);
  si coincide con el catálogo, se reconoce y queda como voz oficial.

### C) EXTRACCIÓN tar.bz2 en Android — `PiperTar.kt` (NUEVO) + commons-compress

- Dependencia añadida: `org.apache.commons:commons-compress:1.27.1` (arrastra `commons-io`,
  `commons-codec`, `commons-lang3`; bzip2/gzip van dentro de commons-compress).
- `PiperTar.extract()` descomprime **streaming** por bloques de 128 KB con
  `BZip2CompressorInputStream` (o `GzipCompressorInputStream`) + `TarArchiveInputStream`;
  **nunca** carga el tar completo en memoria (probado con heap de 256 MB).
- Localiza dentro del árbol del tar el `*.onnx` (excluye `.onnx.json`), su `*.onnx.json`,
  `tokens.txt` y la carpeta `espeak-ng-data/`, y los copia con **nombres canónicos**
  (`model.onnx`, `voice.onnx.json`, `tokens.txt`) al directorio de la voz; `espeak-ng-data/`
  se conserva dentro del dir de la voz.
- **Anti path traversal**: se rechazan rutas absolutas, con `\` o con `..` (`classify()`),
  y hay una segunda comprobación `canonicalPath` antes de escribir; los enlaces simbólicos se
  cuentan como rechazados. `MODEL_CARD` y demás ruido se ignoran.
- Tras extraer: `convert()` **no regenera** `tokens.txt` si ya hay uno válido y **no inyecta**
  metadata si el `.onnx` ya la trae (`OnnxMeta.hasPiperMetadata()`, lee la cola del fichero).
  Si faltara `tokens.txt`, se usa la conversión on-device existente como fallback.

### D) DATOS PARA SHERPA: `espeak-ng-data` de la propia voz

- `PiperTts.ensureLoaded()` usa como `data_dir` el `espeak-ng-data` **extraído de la voz**
  (`voice.dir/espeak-ng-data`) si existe; si no, el asset global (`filesDir/espeak-ng-data`).
- **Verificado en host** (Python sherpa-onnx 1.13.8 del entorno `/tmp/piperprobe/venv`)
  pasando `data_dir = <voz>/espeak-ng-data`:

| Paquete | sample_rate | num_speakers | F0 mediana | veredicto |
|---|---|---|---|---|
| `vits-piper-es_AR-daniela-high` | 22 050 | 1 | **180,7 Hz** | femenina ✅ (sintetiza 3,76 s) |
| `vits-piper-es_MX-claude-high` | 22 050 | 1 | **190,1 Hz** | femenina ✅ (sintetiza 4,05 s) |

### E) PROGRESO / cancelar / espacio

- Descarga con progreso (`onProgress(bytes, total)`), que la UI muestra en la barra.
- **Cancelar**: nuevo botón «Cancelar» (visible solo durante la descarga) →
  `PiperVoiceManager.cancelDownload()`; la descarga aborta, borra el `.part` y no deja la voz a medias.
- **Espacio en disco**: antes de descargar se comprueba con `StatFs` que hay
  `≈2×tamaño + 32 MB` libres; si no, error claro con el espacio disponible.
- Etapas de la instalación mostradas en el estado: «Descargando…», «Extrayendo…», «Preparando voz…».
- Errores de red: la descarga lanza excepción con el motivo y cae al respaldo rhasspy.

## 4. Archivos nuevos / modificados

```
app/build.gradle.kts                    + org.apache.commons:commons-compress:1.27.1
app/src/main/java/com/zota/traductor/
    PiperTar.kt             NUEVO  extracción streaming tar.bz2/gz/tar + clasificación canónica + anti-traversal
    PiperVoiceManager.kt    AMPLIADO  catálogo con tarUrl sherpa (fuente principal) + respaldo rhasspy,
                                    downloadTar/importArchive, cancelar, StatFs, espeak propio de la voz
    OnnxMeta.kt             + hasPiperMetadata() (no duplicar metadata en paquetes ya convertidos)
    PiperTts.kt             data_dir = espeak-ng-data de la voz (fallback al asset global)
    ModelManager.kt         downloadTo(..., isCancelled) + limpieza del .part en fallo/cancelación
    SettingsActivity.kt     botón Cancelar, etapas de instalación, catch de Cancelled
app/src/main/res/layout/activity_settings.xml  + btnCancelPiper + texto informativo
app/src/main/res/values/strings.xml            + settings_cancel, piper_download_hint, import_piper (tar.bz2)
app/src/test/.../CoreTest.kt                   19 -> 24 tests (catálogo tar, classify, anti-traversal,
                                               extracción de paquete sintético tar.bz2 real)
```

## 5. Buenas prácticas / decisiones

- **Streaming de verdad**: la extracción copia por bloques a disco; se probó con heap de 256 MB
  y un paquete de 115 MB sin OOM (en Android el heap por app suele ser ~192-512 MB).
- **No duplicar metadata**: los paquetes sherpa ya vienen convertidos; duplicar `metadata_props`
  podría confundir al lector de sherpa. `hasPiperMetadata()` lo evita.
- **Fallback en dos niveles**: paquete sherpa → rhasspy crudo → (a nivel de reproducción) TTS del sistema.
- **Atomicidad**: se extrae a `.tmp-<id>` y se renombra al dir final; el `.tar.bz2` temporal se borra siempre.

## 6. NO roto (reverificado)

- VAD Silero ONNX + `EnergyVad` de reserva: intacta.
- Descarga de modelos (Qwen/Whisper/VAD) e importación Whisper/GGUF: intactas
  (`downloadTo` mantiene el comportamiento previo con `isCancelled = { false }`).
- UI de idiomas, traducción al escribir, historial, tema oscuro: intactos.
- Alineación 16 KB, `targetSdk 35`, `arm64-v8a` único, y fallback al TTS del sistema: intactos.

## 7. Pendiente / no verificado (honesto) — y bloqueos

- **Sin dispositivo**: compila, empaqueta, pasa 24 tests host y la extracción real + la síntesis
  sherpa se validaron en host; **nada se ha ejecutado aún en Android** (no hay terminal conectado).
- **Riesgos runtime a comprobar en el móvil**: extracción del paquete en el `filesDir` real
  (espacio), `SAF` abriendo el `InputStream` de un `.tar.bz2` grande, `System.loadLibrary`,
  y `AudioTrack` PCM float (el fallback al sistema sigue ahí).
- **Sin bloqueos**: commons-compress y la extracción funcionaron a la primera (tras corregir la
  versión de `commons-io` en el harness host; en Gradle la resolución de dependencias es correcta).
- **Calidad de voz**: no medida subjetivamente en el móvil (F0 coherente con femenina en host).

## 8. Siguiente paso concreto

1. `adb install -r app/build/outputs/apk/debug/app-debug.apk` en el OnePlus PLB110.
2. Ajustes → «Voz neuronal (Piper)» → descargar *es_AR-daniela-high* (115,5 MB, con progreso y
   Cancelar) o **Importar** un `vits-piper-*.tar.bz2` → **Probar voz**.
3. 🔊 en el panel de traducción/original: debe sonar con Piper; sin voz, cae al motor del sistema.
4. Logs: `adb logcat -s PiperTts PiperVoiceManager PiperTar TtsRouter SettingsActivity`.

---

# PARTE v0.3 (histórico) — Piper (TTS neuronal offline con voces rhasspy)

**Estado: build VERDE.** Se evoluciona el proyecto v0.2 (no se reescribe) añadiendo
**PIPER** para el texto-a-voz: TTS **neuronal offline** (Piper/VITS) que lee en voz alta
tanto el original como la traducción, con **voces descargables e importables** y
**fallback al motor TTS del sistema** cuando no hay voz instalada. Nada de lo anterior
(VAD, descarga de modelos, importar Whisper/GGUF, alineación 16 KB, targetSdk 35,
solo arm64-v8a) se ha roto.

Fecha: 2026-10-06 · Directorio: `/Users/zota/.openclaw/workspace/traductor/`
**No se ha publicado nada** en ningún sitio.

---

# PARTE v0.3 — Piper (TTS neuronal offline)

## 1. Resultado del build (verificado)

| Dato | Valor |
|---|---|
| Comando | `./gradlew :app:assembleDebug :app:testDebugUnitTest` |
| Resultado | `BUILD SUCCESSFUL` |
| Ruta EXACTA del APK | `/Users/zota/.openclaw/workspace/traductor/app/build/outputs/apk/debug/app-debug.apk` |
| Tamaño | **66 881 301 bytes** (≈63,8 MiB) |
| sha256 | `b326046a478b2aa72bae4cdf89a75a67c8aa18f59d52ada1c74772cb5c7ac057` |
| ABI empaquetada | `arm64-v8a` (únicamente; minSdk 26, targetSdk 35) |
| Tests host | `com.zota.traductor.CoreTest: tests=19 failures=0 errors=0 skipped=0` (antes 13) |

### Contenido nativo del APK (`unzip -l | grep .so`)

```
  1292896  01-01-1981 01:01   lib/arm64-v8a/libc++_shared.so
  4513768  01-01-1981 01:01   lib/arm64-v8a/libllamajni.so        <- llama.cpp + JNI (previo)
 17571152  01-01-1981 01:01   lib/arm64-v8a/libonnxruntime.so     <- ONNX Runtime (VAD, previo)
    82056  01-01-1981 01:01   lib/arm64-v8a/libonnxruntime4j_jni.so
 24169352  01-01-1981 01:01   lib/arm64-v8a/libsherpa-onnx-jni.so <- NUEVO: Piper/VITS + espeak-ng + onnxruntime (static-link)
  1739496  01-01-1981 01:01   lib/arm64-v8a/libwhisperjni.so      <- whisper.cpp + JNI (previo)
```

> **Nota sobre espeak-ng:** en el artefacto `static-link-onnxruntime` de sherpa-onnx,
> onnxruntime **y espeak-ng van enlazados estáticamente dentro de `libsherpa-onnx-jni.so`**;
> por eso no aparece una `libespeak-ng.so` aparte. Confirmado buscando cadenas del binario:
> `strings libsherpa-onnx-jni.so | grep espeak` → `%s/espeak-ng-data`, `Wrong version of espeak-ng-data`, …

### Verificaciones extra (sin dispositivo)

- **Alineación 16 KB** (Android 15/16): los 3 segmentos `LOAD` de
  `libsherpa-onnx-jni.so` quedan a `align=0x4000`; `zipalign -c -P 16` da `OK` para
  `libsherpa-onnx-jni.so` (y sigue OK para llama/whisper). ✅
- **Sin choque de onnxruntime**: al usar la variante *static-link-onnxruntime*, el `.so`
  de sherpa **no** exporta símbolos `onnxruntime_*` (0 símbolos dinámicos), así que no
  interfiere con `libonnxruntime.so` de la VAD. ✅
- **espeak-ng-data** empaquetado como `assets/espeak-ng-data.zip` (9 016 636 B) y
  extraído a `filesDir/espeak-ng-data/` la primera vez. ✅

## 2. Decisión de implementación (por qué sherpa-onnx y no libpiper)

El objetivo pedía, en orden: (1) `libpiper` + `espeak-ng` compilados con el NDK, o
(2) **sherpa-onnx** (k2-fsa) si lo anterior se atasca. Se eligió **la vía (2)** por ser
la **más fiable y verificable sin dispositivo**:

- `libpiper` (piper1-gpl) exige compilar **espeak-ng + piper + datos + cabeceras de
  onnxruntime C++** para arm64 desde cero (el AAR de `onnxruntime-android` solo trae
  `.so` y clases Java, **no cabeceras C++**), y no publica binarios Android. Riesgo alto
  y sin forma de probar el resultado en este entorno.
- `sherpa-onnx` publica **AAR/.so prebuilt para Android arm64-v8a** con Piper/VITS **y
  espeak-ng** ya integrados, y expone una **API Kotlin** (`OfflineTts`) que replica
  exactamente lo que se pedía (`init` → `synthesize` → `release`).
- Se eligió el artefacto **`static-link-onnxruntime`** para no duplicar `libonnxruntime.so`
  con el que ya usa la VAD.

La conversión de voces crudas de rhasspy a formato sherpa (**metadata en el `.onnx` +
`tokens.txt`**) se hace **en el propio móvil**, así que se respetan al 100% las URLs de
voces pedidas (no se depende de modelos pre-convertidos de terceros).

## 2.bis CORRECCIÓN — TODAS las voces FEMENINAS (género verificado por F0)

Requisito posterior: la lista descargable y la selección por defecto deben ser **femeninas**.
Se **midió el pitch (F0)** del audio real sintetizado (método de autocorrelación sobre
frames de 40 ms, calibrado con referencias conocidas) y se descartaron las masculinas.

| Voz | F0 mediana | Veredicto |
|---|---|---|
| `es_ES-davefx-medium` (referencia) | 117 Hz | **MASCULINA** → excluida |
| `en_US-lessac-medium` (referencia) | 190 Hz | femenina |
| `es_AR-daniela-high` | 182 Hz | **femenina** ✅ (español, por defecto) |
| `es_MX-claude-high` | 181 Hz | **femenina** ✅ |
| `es_ES-sharvard-medium` (sid=1) | 202 Hz | **femenina** ✅ (multi-speaker: speaker F = 1) |
| `en_US-hfc_female-medium` | 212 Hz | **femenina** ✅ (inglés, por defecto) |
| `en_US-lessac-medium` | 190 Hz | **femenina** ✅ |
| `fr_FR-siwis-medium` | 212 Hz | **femenina** ✅ |
| `de_DE-ramona-low` | 208 Hz | **femenina** ✅ (alemán, por defecto) |
| `de_DE-eva_k-x_low` | 174 Hz | **femenina** ✅ |
| `it_IT-paola-medium` | 193 Hz | **femenina** ✅ |
| `de_DE-pavoque-low` | 122 Hz | MASCULINA → excluida |

Decisiones:

- **Español**: NO se usa `es_ES-davefx-medium` ni `es_ES-carlfm` (masculinas). Por defecto
  `es_AR-daniela-high` (1ª opción pedida); alternativa `es_MX-claude-high`; y
  `es_ES-sharvard-medium` **confirmada femenina** con **speaker = 1** (su `speaker_id_map`
  es `M=0, F=1`). Se añadió soporte de **speakerId** a las voces para este caso multi-speaker.
- **Inglés**: `en_US-hfc_female-medium` (nombre explícito, F0 212 Hz) por defecto, más
  `en_US-lessac-medium` (F0 190 Hz). Se descartó `en_US-amy-medium`/`it_IT-serena-medium`
  por un fallo del lector de tokens de sherpa con su `tokens.txt` (no afecta a las elegidas).
- **Resto**: fr (`siwis`, F0 212), de (`ramona` 208 / `eva_k` 174), it (`paola` 193).
- El catálogo marca cada voz con `gender = "F"` y un test lo verifica; otro test comprueba
  que `davefx`/`carlfm` NO están.

## 3. Qué se añadió

### A) Motor Piper (`PiperTts.kt`)

- Carga la voz (`OfflineTts` de sherpa-onnx) apuntando a `model.onnx`, `tokens.txt` y al
  directorio `espeak-ng-data`; `numThreads=2`, `provider=cpu`.
- `speak(text, voice)` sintetiza y reproduce con `AudioTrack` (PCM float, mono, al sample
  rate del modelo — 22 050 Hz en las voces medium). Bloqueante → se ejecuta en IO.
- `espeak-ng-data` se extrae una sola vez del asset zip a `filesDir`.

### B) Enrutador de voz (`TtsRouter.kt`) — Piper por defecto, sistema como fallback

1. Si hay **voz Piper instalada para el idioma** → Piper (neuronal, offline).
2. Si no hay voz, o Piper falla → **motor TTS del sistema**, prefiriendo
   `com.google.android.tts` (misma lógica que ya existía en `TtsHelper`).

Los botones 🔊 del original y de la traducción y el auto-TTS usan este enrutador, así que
**nunca dejan de funcionar** aunque no haya ninguna voz Piper descargada.

### C) Gestión de voces (`PiperVoiceManager.kt`) + conversión (`OnnxMeta.kt`)

- **Catálogo descargable** (no se empaqueta en el APK) — **todas femeninas** (F0 verificado):
  `es_AR-daniela-high`, `es_MX-claude-high`, `es_ES-sharvard-medium` (speaker F=1),
  `en_US-hfc_female-medium`, `en_US-lessac-medium`, `fr_FR-siwis-medium`,
  `de_DE-ramona-low`, `de_DE-eva_k-x_low`, `it_IT-paola-medium` (basta añadir una línea
  para más idiomas).
- **Descarga** del `.onnx` + `.onnx.json` desde rhasspy/piper-voices con **progreso** y
  borrado de voces.
- **Conversión on-device** al formato sherpa:
  - `tokens.txt` a partir de `phoneme_id_map` del `.onnx.json`.
  - `metadata_props` inyectadas **al final** del `.onnx` (campo 14 de `ModelProto`;
    en protobuf el orden no importa) con `comment=piper`, `voice`, `sample_rate`,
    `n_speakers`, `has_espeak`… Idempotente (marcador `.meta`).
- **Importación (SAF)**: selección múltiple con el `.onnx` y su `.onnx.json` (y, opcional,
  `tokens.txt`); se copian a `filesDir/piper_voices/imp_<nombre>/` y se convierten.

### D) UI (Ajustes → «Voz neuronal (Piper · texto a voz offline)»)

- Interruptor **«Usar Piper cuando haya voz instalada»** (`MaterialSwitch`).
- Lista de voces del catálogo + importadas con **Descargar / Usar / Eliminar** (reusa
  `item_model.xml`) y **progreso** de descarga.
- Botón **Importar voz (.onnx + .onnx.json)** y botón **Probar voz** (diálogo con texto →
  🔊 con la voz activa).
- Voz activa persistida en `ModelPrefs` (`active_piper_voice`, `piper_enabled`).

### E) Nada de lo que funcionaba se rompió

- VAD (Silero ONNX + `EnergyVad` de reserva), descarga inicial de modelos, importar
  Whisper/GGUF, selector de idiomas, historial, alineación 16 KB y `targetSdk 35` siguen
  igual. `ModelManager.download()` era refactorizado a `downloadTo(url, dest)` reutilizable
  (mismo comportamiento para los modelos existentes).

## 4. Archivos nuevos / modificados

```
app/build.gradle.kts                    + implementation(files("libs/sherpa-onnx-1.13.8.jar"))
app/libs/sherpa-onnx-1.13.8.jar         NUEVO  clases Kotlin/Java de sherpa-onnx (del AAR)
app/src/main/jniLibs/arm64-v8a/
    libsherpa-onnx-jni.so               NUEVO  Piper/VITS + espeak-ng + onnxruntime (prebuilt)
app/src/main/assets/espeak-ng-data.zip  NUEVO  datos de espeak-ng compartidos (se extraen al 1er uso)
app/src/main/java/com/zota/traductor/
    OnnxMeta.kt              NUEVO  encoder protobuf (metadata .onnx) + tokens.txt (Kotlin puro)
    PiperVoiceManager.kt     NUEVO  catálogo, descarga, import SAF, conversión, espeak, resolución
    PiperTts.kt              NUEVO  OfflineTts (sherpa-onnx) + AudioTrack
    TtsRouter.kt             NUEVO  Piper por defecto, fallback al TTS del sistema
    ModelPrefs.kt            + piper_enabled / active_piper_voice
    ModelManager.kt          + downloadTo(url, dest) genérico (reutilizado por voces)
    TranslationPipeline.kt   tts: TtsHelper -> TtsRouter
    MainActivity.kt          usa TtsRouter en los botones 🔊
    SettingsActivity.kt      + sección Piper (listar/descargar/usar/borrar/importar/probar)
app/src/main/res/layout/activity_settings.xml  + sección Piper (switch, lista, botones)
app/src/main/res/values/strings.xml            + textos de la sección Piper
app/src/test/.../CoreTest.kt                   13 -> 18 tests (encoder varint/protobuf, tokens, catálogo)
```

## 5. Fuentes / versiones usadas

| Componente | Fuente / versión |
|---|---|
| sherpa-onnx (prebuilt Android, static-link-onnxruntime) | release **v1.13.8** · `https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-v1.13.8-android-static-link-onnxruntime.tar.bz2` |
| sherpa-onnx AAR (clases Kotlin) | `https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar` (solo `classes.jar`) |
| espeak-ng-data (compartido por todas las voces piper) | `https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/espeak-ng-data.tar.bz2` |
| Voces Piper | `https://huggingface.co/rhasspy/piper-voices/resolve/main/…` (es_ES/davefx, en_US/lessac, fr_FR/siwis, de_DE/thorsten, it_IT/riccardo) |
| Procedimiento de conversión | doc oficial sherpa-onnx «Piper» (metadata + tokens) |
| NDK / CMake / AGP / Kotlin / onnxruntime-android | sin cambios: 27.0.12077973 / 3.22.1 / 8.7.3 / 2.0.21 / 1.20.0 |
| llama.cpp / whisper.cpp | sin cambios (commits v0.1/v0.2) |

Hashes de los artefactos integrados:

```
b4a53d915c19cb6d09b1cdc9a8084be54419b647aab9097ffdfb46f30c3c4dd8  libsherpa-onnx-jni.so (24 169 352 B)
8af5aa647494ec9cb6596959d6d20864377cf4a9dedf1c1b178d936bfb7cea95  sherpa-onnx-1.13.8.jar (238 706 B)
5f16dcb0feadd293ac5d99cf1859a431eea56e183d8f07d541989a06f240086c  espeak-ng-data.zip (9 016 636 B)
```

## 6. Verificación del pipeline (sin dispositivo)

Como no hay móvil/emulador en el entorno, la conversión + inferencia Piper se reprodujo
**exactamente** en el host con Python (`onnx` + `sherpa-onnx` del mismo release):

1. Se descargaron voces crudas (p.ej. `es_AR-daniela-high`, `en_US-hfc_female-medium`) de rhasspy.
2. Se aplicó el **mismo** algoritmo del app: añadir `metadata_props` al final del `.onnx`
   + generar `tokens.txt` desde `phoneme_id_map`.
3. `onnx.load()` confirmó que la metadata es legible (`comment=piper`,
   `voice=en-us`/`es`, `sample_rate=22050`).
4. `sherpa-onnx` sintetizó **audio real** y se estimó el F0 (tabla de 2.bis) →
   **pipeline validado y todas las voces del catálogo confirmadas femeninas**. ✅

## 7. Pendiente / no verificado (honesto) — y bloqueos

- **Sin dispositivo**: todo compila, empaqueta, pasa 18 tests host y el pipeline Piper se
  validó en host, pero **nada se ha ejecutado aún en Android** (no hay terminal conectado).
- **Riesgos runtime a comprobar en el móvil** (por eso el fallback al sistema):
  - Extracción del asset `espeak-ng-data.zip` a `filesDir` en el primer uso de Piper.
  - Reproducción `AudioTrack` PCM float en el dispositivo.
  - `System.loadLibrary("sherpa-onnx-jni")` + coexistencia con `libonnxruntime.so` (símbolos
    ocultos, pero conviene verlo en logcat).
  - Rutas SAF con nombre `.onnx.json` al importar (el emparejamiento se hace por nombre).
- **No se incluyeron bloques “libpiper”/“libespeak-ng.so” separados**: espeak-ng va dentro
  de `libsherpa-onnx-jni.so` (static-link). Si se quiere un `.so` propio de espeak-ng habría
  que compilar `libpiper`+`espeak-ng` con el NDK (vía 1), no emprendida por riesgo/tiempo.
- **Calidad de voz**: no medida subjetivamente en el móvil.

## 8. Siguiente paso concreto

1. `adb install -r app/build/outputs/apk/debug/app-debug.apk` en el OnePlus PLB110.
2. Ajustes → «Voz neuronal (Piper)» → descargar *es_AR-daniela-high* (femenina) → **Probar voz**.
3. 🔊 en el panel de traducción/original: debe sonar con Piper; sin voz instalada, cae al
   motor del sistema (`com.google.android.tts`).
4. Logs: `adb logcat -s PiperTts PiperVoiceManager TtsRouter SettingsActivity`.

---

# ANEXO — Resumen de v0.2 (histórico)

## v0.2 · 1. Resultado del build (verificado)

| Dato | Valor |
|---|---|
| Comando | `./gradlew :app:assembleDebug :app:testDebugUnitTest` |
| Resultado | `BUILD SUCCESSFUL` |
| Ruta EXACTA del APK | `/Users/zota/.openclaw/workspace/traductor/app/build/outputs/apk/debug/app-debug.apk` |
| Tamaño | **33 611 443 bytes** (≈32,1 MiB) — bajó de 55 MB al empaquetar solo arm64-v8a |
| sha256 | `bb7f5cd3eb3870babb086b89db20c53827d05a903cc0b21e594ad85303df90b2` |
| ABI empaquetada | `arm64-v8a` (únicamente; `abiFilters += "arm64-v8a"`, minSdk 26, targetSdk 35) |
| Tests host | `com.zota.traductor.CoreTest: tests=13 failures=0 errors=0 skipped=0` (antes 6) |

### Contenido nativo del APK (`unzip -l`)

```
  1292896  01-01-1981 01:01   lib/arm64-v8a/libc++_shared.so
  4513768  01-01-1981 01:01   lib/arm64-v8a/libllamajni.so     <- llama.cpp + JNI
 17571152  01-01-1981 01:01   lib/arm64-v8a/libonnxruntime.so  <- ONNX Runtime (VAD)
    82056  01-01-1981 01:01   lib/arm64-v8a/libonnxruntime4j_jni.so
  1739496  01-01-1981 01:01   lib/arm64-v8a/libwhisperjni.so   <- whisper.cpp + JNI
```

### Verificaciones extra (sin dispositivo)

- **Alineación 16 KB**: los 3 segmentos `LOAD` de `libllamajni.so` y `libwhisperjni.so`
  quedan a `align=0x4000` (requisito Android 15/16 en dispositivos de 16 KB). ✅
- **Aislamiento de símbolos**: 0 símbolos `ggml_*`/`llama_*`/`whisper_*` exportados en cada
  `.so`; los 4 `Java_com_zota_traductor_*` sí exportados en cada uno. ✅

---

## 2. Qué cambió (v0.1 → v0.2)

### A) Interfaz estilo Google Translate (offline, tema oscuro Material 3)

- **Barra superior**: selector de idioma **origen** (incluye «🌐 Detectar idioma») ⇄ botón
  **swap** ⇄ selector de idioma **destino**, más ⚙ Ajustes.
- **Dos paneles grandes**: entrada editable arriba (con contador de caracteres y ✕ limpiar)
  y traducción abajo (scrollable, seleccionable). Fondo oscuro, tarjetas redondeadas.
- **Botones por panel**:
  - Entrada: 🎤 micrófono (VAD), 🔊 escuchar, 📋 copiar, 📥 pegar, ✕ limpiar.
  - Salida: 🔊 escuchar, 📋 copiar, switch «Leer traducción» (auto-TTS), 🗑 limpiar todo.
- **Traducción al escribir** con debounce de 700 ms + botón **Traducir**; spinner de progreso
  y línea de estado.
- **Selector de idiomas** con lista amplia (**30 idiomas** + detección), buscador y marca de
  selección; cada entrada muestra bandera (emoji) + nombre.
- **Tema oscuro por defecto** (`Theme.Material3.Dark.NoActionBar`) con paleta propia.
- **Historial** (pestaña vía icono 🕘): lista local con escuchar / copiar / reutilizar entrada.

Idiomas incluidos: Detectar idioma, Español, Inglés, Francés, Alemán, Italiano, Portugués,
Ruso, Chino, Japonés, Coreano, Árabe, Hindi, Turco, Neerlandés, Polaco, Ucraniano, Rumano,
Sueco, Danés, Finés, Noruego, Checo, Griego, Hebreo, Persa, Indonesio, Vietnamita, Tailandés,
Bengalí, Catalán.

### B) Selector de idiomas REAL (se propaga de verdad al pipeline)

- `Prompts.systemPrompt(target, source)` genera «Traduce del &lt;origen&gt; al &lt;destino&gt;…»
  y, si el origen es «Detectar idioma», «Detecta el idioma del texto y tradúcelo al &lt;destino&gt;».
- **Whisper**: si el origen no es «auto» se le pasa el código ISO como **idioma forzado**
  (`detect_language=false`); si es «auto», autodetecta y el idioma detectado
  (`whisper_full_lang_id`) se usa luego en el prompt de Qwen y para el swap. Se persiste el
  último idioma detectado.
- Al **intercambiar** (swap), si el origen era «auto» se usa el idioma detectado real.
- El idioma destino elegido determina también el locale del **TTS**.

### C) Importar modelos (Whisper y GGUF de Qwen)

Pantalla **Ajustes** con tres secciones:

1. **Qwen (traducción)**: `Qwen3.5-0.8B Q4_K_M` descargable + GGUF importados. Descargar /
   usar / eliminar; muestra estado, tamaño y **ruta**.
2. **Whisper (voz)**: `tiny` / `base` / `small` multilingües descargables + modelos importados.
   Mismo juego de acciones y **selector de modelo activo**.
3. **VAD**: Silero v5 ONNX (descarga/eliminación).

- **Importar desde el almacenamiento** (SAF `ACTION_OPEN_DOCUMENT`, `.bin`/`.gguf`): se copia a
  `filesDir/whisper_imports/` (Whisper) o `filesDir/mt_imports/` (Qwen) y queda seleccionable.
  Se separan por subcarpeta para desambiguar aunque compartan extensión.
- La elección de modelo activo **persiste** en `SharedPreferences` (`ModelPrefs`) y el pipeline
  **recarga automáticamente** si cambia el archivo activo (sin reiniciar la app).

### D) Nada de lo que funcionaba se rompió

- **VAD** intacta: Silero ONNX + `EnergyVad` de reserva si el modelo falla o no está.
- **Descarga inicial** en el primer arranque (Whisper base + Qwen + Silero) con barra de progreso.
- **Alineación a 16 KB** y `targetSdk 35` sin cambios (reverificado arriba).
- `libllamajni.so` / `libwhisperjni.so`, JNI y CMake sin tocar.

---

## 3. Archivos nuevos / modificados

```
app/build.gradle.kts                    + com.google.android.material:material:1.12.0
app/src/main/AndroidManifest.xml        + SettingsActivity, HistoryActivity (exported=false)
app/src/main/res/values/themes.xml      Material3 Dark (tema oscuro por defecto)
app/src/main/res/values/colors.xml      paleta oscura + pill
app/src/main/res/values/strings.xml     textos de la nueva UI
app/src/main/res/layout/activity_main.xml      UI estilo Google Translate
app/src/main/res/layout/activity_settings.xml  gestión de modelos
app/src/main/res/layout/activity_history.xml   historial
app/src/main/res/layout/item_model.xml         fila de modelo (descargar/usar/eliminar)
app/src/main/res/layout/item_history.xml       fila de historial
app/src/main/res/layout/dialog_language.xml    desplegable con buscador
app/src/main/res/layout/item_language.xml      fila de idioma (bandera + check)
app/src/main/res/drawable/ic_*.xml             8 iconos vectoriales + panel_bg/pill_bg
app/src/main/java/com/zota/traductor/
    Languages.kt          NUEVO  catálogo de idiomas (puro Kotlin, testeable)
    ModelPrefs.kt         NUEVO  preferencias (modelos activos, idiomas, TTS)
    HistoryStore.kt       NUEVO  historial local (SharedPreferences + JSON)
    LanguagePickerDialog.kt NUEVO desplegable de idiomas
    SettingsActivity.kt   NUEVO  descarga / importación / selección de modelos
    HistoryActivity.kt    NUEVO  historial (escuchar/copiar/reutilizar)
    MainActivity.kt       REESCRITO  UI estilo Google Translate
    ModelManager.kt       AMPLIADO   whisper tiny/base/small, import subdirs, resolver
    Prompts.kt            AMPLIADO   origen + destino en el prompt
    TranslationPipeline.kt AMPLIADO  idioma forzado Whisper, recarga por modelo activo,
                                     streaming parcial
app/src/test/.../CoreTest.kt            AMPLIADO   6 → 13 tests (idiomas, prompts)
```

---

## 4. Versiones fijadas (sin cambios respecto a v0.1)

| Componente | Versión / commit |
|---|---|
| llama.cpp | commit `4f5406761517648c23dbd60ea5ade37f77a316c9` |
| whisper.cpp | commit `4afec37b797ab531aaf363208d79d541fbc17ff4` |
| NDK | `27.0.12077973` |
| CMake (Gradle/AGP) | `3.22.1` |
| AGP / Gradle / Kotlin | 8.7.3 / wrapper 8.11.1 / 2.0.21 |
| onnxruntime-android | 1.20.0 |
| material | 1.12.0 (NUEVO) |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 |

---

## 5. Modelos (URLs verificadas, sin cambios)

| Modelo | Bytes reales | URL |
|---|---|---|
| Qwen3.5-0.8B Q4_K_M | 527 502 816 | `https://huggingface.co/lmstudio-community/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf` |
| ggml-tiny.bin | 77 691 713 | `https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin` |
| ggml-base.bin | 147 951 465 | `https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin` |
| ggml-small.bin | 487 601 967 | `https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin` |
| silero_vad.onnx | 2 327 524 | `https://raw.githubusercontent.com/snakers4/silero-vad/master/src/silero_vad/data/silero_vad.onnx` |

---

## 6. Capturas

**No generadas**: no hay dispositivo/emulador conectado en el entorno de build (solo
`adb`/SDK presentes sin target). La UI está descrita en el apartado 2A y el APK es
instalable para verificarla en el móvil (OnePlus PLB110, arm64-v8a).

---

## 7. Pendiente / no verificado (honesto)

- **Sin dispositivo**: nada de la nueva UI se ha ejecutado en runtime. Compila, empaqueta y
  pasa los tests host, pero no está probado en pantalla.
- **Modo conversación** (dos botones, uno por idioma, para hablar por turnos): **no
  implementado** (era opcional). El resto de opcionales (historial) sí está.
- **Importación SAF**: implementada copiando el contenido por `ContentResolver` al
  `filesDir`. No probada en dispositivo (no se puede sin terminal).
- **Traducción al escribir**: debounce + guarda de reentrada implementados; sin medir consumo
  real de CPU/batería con el modelo cargado.
- **Calidad de traducción**: no medida con el modelo real (sigue en `Prompts.systemPrompt`).
- Los flags emoji se renderizan dependiendo de la fuente del sistema; en algunos OEM pueden
  mostrarse como letras regionales (no es un fallo de la app).

## 8. Siguiente paso concreto

1. `adb install -r app/build/outputs/apk/debug/app-debug.apk` en el OnePlus PLB110
   (o copiar el APK y abrirlo). Primera vez: Wi-Fi y esperar la descarga (~650 MB).
2. Probar: escribir → traducción automática; 🎤 con VAD; 🔊 TTS (motor
   `com.google.android.tts`); ⚙ Ajustes → importar un `ggml-*.bin` desde el móvil y usarlo;
   🕘 Historial.
3. Si algo falla: `adb logcat -s llama_jni whisper_jni Pipeline TtsHelper SettingsActivity`.
