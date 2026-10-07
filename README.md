<div align="center">

<img src="assets/icon/ic_launcher_512.png" alt="Traductor" width="128" height="128" />

# Traductor · NLLB-200

**A fully offline translator for Android — text, voice and photos.**
**Un traductor 100% offline para Android — texto, voz y fotos.**

[🇬🇧 English](#-english) · [🇪🇸 Español](#-español) · [🌐 Website / Web](https://aurenox-global.github.io/traductor-web/)

![Platform](https://img.shields.io/badge/platform-Android%208%2B%20(arm64--v8a)-0A84FF)
![Offline](https://img.shields.io/badge/offline-100%25-0A84FF)
![Engine](https://img.shields.io/badge/engine-NLLB--200%20%C2%B7%20ONNX-0A84FF)
![License](https://img.shields.io/badge/license-MIT-0A84FF)

**Latest release: [`v1.0-nllb`](https://github.com/aurenox-global/traductor/releases/latest)** · LITE ≈ 79 MB · FULL ≈ 1.32 GB

</div>

---

## 🇬🇧 English

### What is it?

**Traductor** is a native Android application that translates text, speech and
photos **entirely on the device**. There is no cloud, no account and no telemetry:
once the models are downloaded, the app works in airplane mode.

- **Translation:** **NLLB-200-distilled-600M** (Meta AI) quantised to int8 ONNX and
  run with **ONNX Runtime** — a dedicated, 200-language neural translation model.
  No LLM, no chat prompt: a real encoder/decoder translator.
- **Speech recognition:** **Whisper** (`whisper.cpp`) — record and transcribe offline.
- **Voice activity detection:** **Silero VAD** (ONNX Runtime) to cut clean audio segments.
- **Photo OCR:** **PaddleOCR PP-OCR** (ONNX) — point the camera at a sign and translate it.
- **Text-to-speech:** **Piper** neural voices (sherpa-onnx + espeak-ng), fully offline.
- **UI:** a clean, Google-Translate-style interface with a light/dark theme.

> The only moment a connection is needed is the **first model download** (Wi-Fi
> recommended). After that everything runs locally. You can also **import the models
> from the phone's storage**, so nothing is ever downloaded.

### Features

| | |
|---|---|
| 🌍 **NLLB-200 languages** | The NLLB-200 model family covers 200 languages; the app maps the main ones (ES, EN, FR, DE, IT, PT, RU, ZH, JA, KO, AR, HI, TR, NL, PL, UK, RO, BG, HU, SV, DA, FI, NO, CS, EL, HE, FA, ID, VI, TH, BN, CA…). |
| ⌨️ **Text translation** | Type or paste, with an ES/EN translated interface and language selector. Long texts are translated sentence by sentence. |
| 🎤 **Voice translation** | Whisper ASR + VAD: press the mic, speak naturally, get text. |
| 📷 **Photo translation (OCR)** | Camera or gallery → offline text extraction → editable input → translation. |
| 🔊 **Offline TTS** | Piper neural voices, **female by preference** (verified by measuring F0); male only when it is a language's only option. System engine as fallback. |
| 📥 **Import NLLB models** | Download the ONNX models, or **import them from a folder on the phone** (encoder + decoder + `tokenizer.bin`) — nothing but the APK is transferred. |
| 🕘 **History** | Your translations are stored locally and can be revisited. |
| ⚙️ **Model manager** | Download or import Whisper/Piper/OCR models from storage. |
| 🔒 **Private by design** | Nothing leaves the phone. No ads, no analytics, no servers. |

### Translation engine (NLLB-200 · ONNX)

The translation engine is **NLLB-200-distilled-600M**, quantised to int8 and exported
to ONNX (`Xenova/nllb-200-distilled-600M`):

| File | Role | Size |
|---|---|---|
| `nllb_encoder_model_quantized.onnx` | Encoder | ≈ 419 MB |
| `nllb_decoder_model_merged_quantized.onnx` | Decoder (merged, cache) | ≈ 475 MB |
| `tokenizer.bin` | SentencePiece BPE tokenizer (compact) | ≈ 9 MB |

- The **tokenizer** ships inside the APK (`assets/nllb/tokenizer.bin`).
- The two **ONNX** models (~900 MB) are either **downloaded on first run** or
  **imported from storage** (see below). They are never bundled in the normal APK.
- The tokenizer is reimplemented in **pure Kotlin** (SentencePiece BPE with Metaspace
  pre-tokenization + BPE merges), verified against HuggingFace in unit tests.

### Import the models from your phone (no download)

1. Download the three files (encoder ONNX, decoder ONNX, `tokenizer.bin`) into the
   **same folder** on the phone.
2. Open the app → **Settings** → *Translation model (NLLB-200 · ONNX)* →
   **Import NLLB models** → pick that folder.
3. The app copies the files to its private storage and **won't download anything**.

On the **FULL** build the models are already inside the APK, so import is optional;
on the **LITE** build you either import them or let the app download them on first run.

### How it works (architecture)

```
                 ┌──────────────────────────────────────────────┐
  text  ─────────▶│                                              │
  mic ──▶ VAD ───▶│            TranslationPipeline               │
                 │   (Kotlin coroutines, single engine)         │
  photo ─▶ OCR ──▶│                                              │
                 └───────┬───────────────────────┬──────────────┘
                         │                       │
                 ┌───────▼────────┐      ┌───────▼────────┐
                 │ ONNX Runtime   │      │  whisper.cpp   │
                 │  NLLB-200      │      │  (Whisper ASR) │
                 │  encoder+dec   │      │  whisper_jni   │
                 └────────────────┘      └────────────────┘
                         │
                 ONNX Runtime (Silero VAD · PaddleOCR PP-OCR)
                         │
                 sherpa-onnx (Piper TTS + espeak-ng)
```

- **JNI layer** (`app/src/main/cpp`): a single thin C++ bridge, `whisper_jni.cpp`,
  linked against a static build of `whisper.cpp` produced by
  `scripts/build_native.sh` (Android NDK, `arm64-v8a`, 16 KB-aligned).
- **Kotlin layer** (`app/src/main/java/com/zota/traductor`): `NllbEngine`
  (encoder + merged decoder via ONNX Runtime, sentence-level chunking) and
  `NllbTokenizer` drive translation; `TranslationPipeline` orchestrates
  ASR → NLLB → post-processing → TTS; `NllbModels`/`NllbImport` handle
  download/import; `HistoryStore` persists.
- **Models are downloaded at runtime** (never bundled in the normal APK) to the app's
  private storage, sizes shown in Settings. There is also a **FULL** build variant
  that ships the models and voices *inside* the APK (no download on first launch) —
  see [How to build](#how-to-build).

### Build variants (LITE / FULL)

The variants are **not** product flavors: they are gated by Gradle properties, so the
normal build is untouched.

| Variant | Command | Contents | versionName |
|---|---|---|---|
| **LITE** (normal) | `./gradlew :app:assembleDebug` | Downloads models on first run | `1.0.2-nllb` |
| **LITE-OCR** | `./gradlew :app:assembleDebug -Pocrbundle=true` | Ships the OCR + VAD models in assets | `1.0-nllb-lite` |
| **FULL** | `./gradlew :app:assembleDebug -Pbundled=true` | Ships **all** models (NLLB + Whisper + OCR + VAD + Piper) in assets | `1.0-nllb-full` |

### Requirements

- Android **8.0 (API 26)** or newer.
- **arm64-v8a** device (most modern phones). The APK ships a single ABI.
- **~1.1 GB free** for the first model download (NLLB ≈ 900 MB + Whisper + VAD).
- For building: **JDK 17**, **Android SDK 35**, **NDK 27.0.12077973**, **CMake 3.22.1**.

### How to build

```bash
# 1. Native library (whisper.cpp → static .a for arm64-v8a)
./scripts/fetch-deps.sh              # clone the pinned whisper.cpp (submodule)
LIBS=whisper ./scripts/build_native.sh   # compile it with the NDK

# 2. The app
./gradlew :app:assembleDebug         # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest     # host unit tests

# 3. Install
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> `scripts/build_native.sh` still knows how to build `llama.cpp` (`LIBS="llama whisper"`),
> but the NLLB variant no longer links it: only `whisperjni` is compiled by
> `app/src/main/cpp/CMakeLists.txt`.

#### FULL variant (all default models bundled)

```bash
./scripts/fetch_bundled_assets.sh              # download the models into app/bundled-assets/
./gradlew :app:assembleDebug -Pbundled=true    # → dist-final/traductor-nllb-full-arm64-debug.apk
```

The FULL APK bundles NLLB-200 (encoder + decoder + tokenizer), Whisper base, Silero
VAD, the PP-OCRv6 tiny OCR models and the default Piper voices (ES
`es_AR-daniela-high`, EN `en_US-hfc_female-medium`) with their `tokens.txt` and
`espeak-ng-data`. On first launch they are **copied from assets to private storage**
(with progress) instead of downloaded; any other model or voice is still downloaded
normally. The `app/bundled-assets/` folder is generated by the script and not
versioned (see `.gitignore`).

### Repository structure

```
traductor/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── java/com/zota/traductor/   # Kotlin: NllbEngine/Tokenizer, ASR, TTS, OCR, UI
│       │   ├── cpp/                       # whisper_jni.cpp + whisper.cpp (submodule)
│       │   ├── assets/nllb/tokenizer.bin  # NLLB tokenizer (ships in the APK)
│       │   ├── res/                       # layouts, strings (es/en), mipmaps, adaptive icon
│       │   └── AndroidManifest.xml
│       └── test/                          # host unit tests (JUnit)
├── assets/icon/                           # icon sources + generate_icons.py
├── jvmharness/                            # JVM harness: runs NllbEngine on the desktop
├── scripts/
│   ├── fetch-deps.sh                      # clone vendor deps at pinned commits
│   ├── build_native.sh                    # NDK build of the native libraries
│   ├── fetch_bundled_assets.sh            # FULL variant: download models to bundle
│   ├── fetch_ocr_bundle.sh                # LITE-OCR variant: download OCR to bundle
│   ├── build_nllb_tokenizer.py            # tokenizer.json → assets/nllb/tokenizer.bin
│   ├── nllb_onnx.py                       # reference Python NLLB pipeline (validation)
│   └── bundled_manifest.py                # FULL variant: generate the assets manifest
├── gradle/ · gradlew · settings.gradle.kts
└── README.md
```

### Privacy

- **Everything is local.** Translation, speech recognition, OCR and TTS run on the
  device; no text, audio or image is ever uploaded.
- **No accounts, no analytics, no ads, no trackers.**
- **Network is used only to download models**, from the official repositories
  (HuggingFace / GitHub). After the first download you can use the app offline.
- Permissions: `INTERNET` (model download), `RECORD_AUDIO` (speech),
  `CAMERA` (photo OCR, optional), `ACCESS_NETWORK_STATE`.

### Third-party licenses

| Component | License | Use |
|---|---|---|
| [NLLB-200](https://huggingface.co/facebook/nllb-200-distilled-600M) | CC-BY-NC-4.0 | Translation model (encoder/decoder) |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | MIT | NLLB + VAD + OCR inference |
| [whisper.cpp](https://github.com/ggml-org/whisper.cpp) | MIT | Speech recognition |
| [Whisper](https://github.com/openai/whisper) | MIT | ASR model |
| [Silero VAD](https://github.com/snakers4/silero-vad) | MIT | Voice activity detection |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | Apache-2.0 | Piper TTS runtime |
| [Piper](https://github.com/rhasspy/piper) | MIT | Neural TTS voices |
| [espeak-ng](https://github.com/espeak-ng/espeak-ng) | GPL-3.0 | Piper phonemization |
| [PaddleOCR (PP-OCR)](https://github.com/PaddlePaddle/PaddleOCR) | Apache-2.0 | Photo OCR |

Model files are downloaded from their official repositories and remain subject to
their own licenses — note that the **NLLB-200 weights are licensed CC-BY-NC-4.0
(non-commercial)**.

### Credits

**Created by [Andres Mag](https://github.com/) · Cuba.** 🇨🇺

Built on the shoulders of the open-source community above.

### License

The application code is released under the **MIT License**. Third-party components
keep their own licenses (see above).

---

## 🇪🇸 Español

### ¿Qué es?

**Traductor** es una aplicación nativa de Android que traduce texto, voz y fotos
**íntegramente en el dispositivo**. Sin nube, sin cuentas y sin telemetría: una vez
descargados los modelos, funciona incluso en modo avión.

- **Traducción:** **NLLB-200-distilled-600M** (Meta AI) cuantizado a int8 en ONNX y
  ejecutado con **ONNX Runtime** — un modelo de traducción neuronal dedicado de 200
  idiomas. Sin LLM y sin prompt de chat: un traductor real encoder/decoder.
- **Reconocimiento de voz:** **Whisper** (`whisper.cpp`) — graba y transcribe offline.
- **Detección de voz:** **Silero VAD** (ONNX Runtime) para cortar segmentos limpios.
- **OCR de fotos:** **PaddleOCR PP-OCR** (ONNX) — apunta a un cartel y tradúcelo.
- **Texto a voz:** voces neuronales **Piper** (sherpa-onnx + espeak-ng), 100% offline.
- **Interfaz:** limpia, estilo Google Translate, con tema claro/oscuro.

> El único momento en que hace falta conexión es la **primera descarga de modelos**
> (se recomienda Wi-Fi). Después, todo se ejecuta en local. También puedes **importar
> los modelos desde el almacenamiento del móvil**, sin descargar nada.

### Características

| | |
|---|---|
| 🌍 **Idiomas NLLB-200** | La familia NLLB-200 cubre 200 idiomas; la app mapea los principales (ES, EN, FR, DE, IT, PT, RU, ZH, JA, KO, AR, HI, TR, NL, PL, UK, RO, BG, HU, SV, DA, FI, NO, CS, EL, HE, FA, ID, VI, TH, BN, CA…). |
| ⌨️ **Traducción de texto** | Escribe o pega, con interfaz traducida ES/EN y selector de idiomas. Los textos largos se traducen frase a frase. |
| 🎤 **Traducción de voz** | ASR Whisper + VAD: pulsa el micro, habla con naturalidad y obtén el texto. |
| 📷 **Traducción de fotos (OCR)** | Cámara o galería → extracción de texto offline → entrada editable → traducción. |
| 🔊 **TTS offline** | Voces neuronales Piper, **femeninas por preferencia** (verificadas midiendo F0); masculinas solo cuando son la única opción del idioma. Motor del sistema como reserva. |
| 📥 **Importar modelos NLLB** | Descarga los ONNX, o **impórtalos desde una carpeta del móvil** (encoder + decoder + `tokenizer.bin`): no se transfiere nada más que la APK. |
| 🕘 **Historial** | Tus traducciones se guardan en local y puedes consultarlas. |
| ⚙️ **Gestor de modelos** | Descarga o importa modelos Whisper/Piper/OCR desde el almacenamiento. |
| 🔒 **Privacidad por diseño** | Nada sale del teléfono. Sin anuncios, sin analíticas, sin servidores. |

### Motor de traducción (NLLB-200 · ONNX)

El motor de traducción es **NLLB-200-distilled-600M**, cuantizado a int8 y exportado a
ONNX (`Xenova/nllb-200-distilled-600M`):

| Fichero | Rol | Tamaño |
|---|---|---|
| `nllb_encoder_model_quantized.onnx` | Encoder | ≈ 419 MB |
| `nllb_decoder_model_merged_quantized.onnx` | Decoder (merged, caché) | ≈ 475 MB |
| `tokenizer.bin` | Tokenizador SentencePiece BPE (compacto) | ≈ 9 MB |

- El **tokenizador** viaja dentro de la APK (`assets/nllb/tokenizer.bin`).
- Los dos **ONNX** (~900 MB) se **descargan en el primer uso** o se **importan desde el
  almacenamiento** (ver abajo). Nunca van dentro de la APK normal.
- El tokenizador está reimplementado en **Kotlin puro** (SentencePiece BPE con
  pre-tokenización Metaspace + fusiones BPE), verificado contra HuggingFace en los tests.

### Importar los modelos desde el móvil (sin descargar)

1. Descarga los tres ficheros (encoder ONNX, decoder ONNX y `tokenizer.bin`) a una
   **misma carpeta** del móvil.
2. Abre la app → **Ajustes** → *Modelo de traducción (NLLB-200 · ONNX)* →
   **Importar modelos NLLB** → elige esa carpeta.
3. La app los copia a su memoria interna y **ya no descarga nada**.

En la variante **FULL** los modelos ya vienen dentro de la APK, así que la importación
es opcional; en la variante **LITE** hay que importarlos o dejarlos descargar en el
primer arranque.

### Cómo funciona (arquitectura)

```
                 ┌──────────────────────────────────────────────┐
  texto ─────────▶│                                              │
  micro ─▶ VAD ──▶│            TranslationPipeline               │
                 │   (corrutinas de Kotlin, un solo motor)      │
  foto ──▶ OCR ──▶│                                              │
                 └───────┬───────────────────────┬──────────────┘
                         │                       │
                 ┌───────▼────────┐      ┌───────▼────────┐
                 │ ONNX Runtime   │      │  whisper.cpp   │
                 │  NLLB-200      │      │  (Whisper ASR) │
                 │  encoder+dec   │      │  whisper_jni   │
                 └────────────────┘      └────────────────┘
                         │
                 ONNX Runtime (Silero VAD · PaddleOCR PP-OCR)
                         │
                 sherpa-onnx (Piper TTS + espeak-ng)
```

- **Capa JNI** (`app/src/main/cpp`): un único puente C++ fino, `whisper_jni.cpp`,
  enlazado contra un build estático de `whisper.cpp` generado por
  `scripts/build_native.sh` (NDK de Android, `arm64-v8a`, alineado a 16 KB).
- **Capa Kotlin** (`app/src/main/java/com/zota/traductor`): `NllbEngine`
  (encoder + decoder merged por ONNX Runtime, troceado por frases) y `NllbTokenizer`
  realizan la traducción; `TranslationPipeline` orquesta ASR → NLLB → post-proceso →
  TTS; `NllbModels`/`NllbImport` gestionan descarga/importación; `HistoryStore` persiste.
- **Modelos descargados en tiempo de ejecución** (nunca incluidos en la APK normal)
  al almacenamiento privado de la app, con tamaños visibles en Ajustes. Existe además
  una variante **FULL** que lleva los modelos y voces *dentro* del APK (sin descarga
  en el primer arranque) — ver [Cómo compilar](#cómo-compilar).

### Variantes de build (LITE / FULL)

Las variantes **no** son product flavors: se gobiernan por propiedades Gradle, así que
el build normal queda intacto.

| Variante | Comando | Contenido | versionName |
|---|---|---|---|
| **LITE** (normal) | `./gradlew :app:assembleDebug` | Descarga los modelos en el primer uso | `1.0.2-nllb` |
| **LITE-OCR** | `./gradlew :app:assembleDebug -Pocrbundle=true` | Lleva los modelos OCR + VAD en assets | `1.0-nllb-lite` |
| **FULL** | `./gradlew :app:assembleDebug -Pbundled=true` | Lleva **todos** los modelos (NLLB + Whisper + OCR + VAD + Piper) en assets | `1.0-nllb-full` |

### Requisitos

- Android **8.0 (API 26)** o superior.
- Dispositivo **arm64-v8a** (la mayoría de móviles modernos). El APK trae una sola ABI.
- **~1,1 GB libres** para la primera descarga de modelos (NLLB ≈ 900 MB + Whisper + VAD).
- Para compilar: **JDK 17**, **Android SDK 35**, **NDK 27.0.12077973**, **CMake 3.22.1**.

### Cómo compilar

```bash
# 1. Librería nativa (whisper.cpp → .a estática para arm64-v8a)
./scripts/fetch-deps.sh                  # clona whisper.cpp fijado (submódulo)
LIBS=whisper ./scripts/build_native.sh   # lo compila con el NDK

# 2. La app
./gradlew :app:assembleDebug         # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest     # tests unitarios de host

# 3. Instalar
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> `scripts/build_native.sh` todavía sabe compilar `llama.cpp` (`LIBS="llama whisper"`),
> pero la variante NLLB ya no lo enlaza: `app/src/main/cpp/CMakeLists.txt` solo compila
> `whisperjni`.

#### Variante FULL (todos los modelos por defecto incluidos)

```bash
./scripts/fetch_bundled_assets.sh              # descarga los modelos a app/bundled-assets/
./gradlew :app:assembleDebug -Pbundled=true    # → dist-final/traductor-nllb-full-arm64-debug.apk
```

La APK FULL incluye NLLB-200 (encoder + decoder + tokenizador), Whisper base, Silero
VAD, los modelos OCR PP-OCRv6 tiny y las voces Piper por defecto (ES
`es_AR-daniela-high`, EN `en_US-hfc_female-medium`) con su `tokens.txt` y
`espeak-ng-data`. En el primer arranque se **copian de assets a almacenamiento privado**
(con progreso) en vez de descargarse; cualquier otro modelo o voz se sigue descargando
normal. La carpeta `app/bundled-assets/` la genera el script y no se versiona
(ver `.gitignore`).

### Estructura del repositorio

```
traductor/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── java/com/zota/traductor/   # Kotlin: NllbEngine/Tokenizer, ASR, TTS, OCR, UI
│       │   ├── cpp/                       # whisper_jni.cpp + whisper.cpp (submódulo)
│       │   ├── assets/nllb/tokenizer.bin  # tokenizador NLLB (viaja en la APK)
│       │   ├── res/                       # layouts, strings (es/en), mipmaps, icono adaptativo
│       │   └── AndroidManifest.xml
│       └── test/                          # tests unitarios de host (JUnit)
├── assets/icon/                           # fuentes del icono + generate_icons.py
├── jvmharness/                            # arnés JVM: ejecuta NllbEngine en el escritorio
├── scripts/
│   ├── fetch-deps.sh                      # clona las dependencias fijadas
│   ├── build_native.sh                    # build NDK de las librerías nativas
│   ├── fetch_bundled_assets.sh            # variante FULL: descarga los modelos a empaquetar
│   ├── fetch_ocr_bundle.sh                # variante LITE-OCR: descarga el OCR a empaquetar
│   ├── build_nllb_tokenizer.py            # tokenizer.json → assets/nllb/tokenizer.bin
│   ├── nllb_onnx.py                       # pipeline NLLB de referencia en Python (validación)
│   └── bundled_manifest.py                # variante FULL: genera el manifest de assets
├── gradle/ · gradlew · settings.gradle.kts
└── README.md
```

### Privacidad

- **Todo es local.** La traducción, el reconocimiento de voz, el OCR y el TTS se
  ejecutan en el dispositivo; nunca se sube texto, audio ni imágenes.
- **Sin cuentas, sin analíticas, sin anuncios, sin rastreadores.**
- **La red se usa solo para descargar modelos**, desde los repositorios oficiales
  (HuggingFace / GitHub). Tras la primera descarga puedes usar la app sin conexión.
- Permisos: `INTERNET` (descarga de modelos), `RECORD_AUDIO` (voz),
  `CAMERA` (OCR de fotos, opcional), `ACCESS_NETWORK_STATE`.

### Licencias de terceros

| Componente | Licencia | Uso |
|---|---|---|
| [NLLB-200](https://huggingface.co/facebook/nllb-200-distilled-600M) | CC-BY-NC-4.0 | Modelo de traducción (encoder/decoder) |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | MIT | Inferencia NLLB + VAD + OCR |
| [whisper.cpp](https://github.com/ggml-org/whisper.cpp) | MIT | Reconocimiento de voz |
| [Whisper](https://github.com/openai/whisper) | MIT | Modelo ASR |
| [Silero VAD](https://github.com/snakers4/silero-vad) | MIT | Detección de actividad de voz |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | Apache-2.0 | Runtime de Piper TTS |
| [Piper](https://github.com/rhasspy/piper) | MIT | Voces neuronales TTS |
| [espeak-ng](https://github.com/espeak-ng/espeak-ng) | GPL-3.0 | Fonemización de Piper |
| [PaddleOCR (PP-OCR)](https://github.com/PaddlePaddle/PaddleOCR) | Apache-2.0 | OCR de fotos |

Los ficheros de modelos se descargan de sus repositorios oficiales y quedan sujetos a
sus propias licencias: los pesos de **NLLB-200 están bajo CC-BY-NC-4.0
(no comercial)**.

### Créditos

**Creado por [Andres Mag](https://github.com/) · Cuba.** 🇨🇺

Construido sobre el trabajo de la comunidad de código abierto citada arriba.

### Licencia

El código de la aplicación se publica bajo **Licencia MIT**. Los componentes de
terceros conservan sus propias licencias (ver arriba).

---

<div align="center">

**Traductor v1.0-nllb** · Made with ❤️ in Cuba 🇨🇺 · 100% offline

</div>
