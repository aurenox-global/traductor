<div align="center">

<img src="assets/icon/ic_launcher_512.png" alt="Traductor" width="128" height="128" />

# Traductor

**A fully offline translator for Android — text, voice and photos.**
**Un traductor 100% offline para Android — texto, voz y fotos.**

[🇬🇧 English](#-english) · [🇪🇸 Español](#-español) · [🌐 Website / Web](docs/index.html)

![Platform](https://img.shields.io/badge/platform-Android%208%2B%20(arm64--v8a)-0A84FF)
![Offline](https://img.shields.io/badge/offline-100%25-0A84FF)
![Kotlin](https://img.shields.io/badge/Kotlin-JNI%20%2B%20C%2B%2B-0A84FF)
![License](https://img.shields.io/badge/license-MIT-0A84FF)

</div>

---

## 🇬🇧 English

### What is it?

**Traductor** is a native Android application that translates text, speech and
photos **entirely on the device**. There is no cloud, no account and no telemetry:
once the models are downloaded, the app works in airplane mode.

- **Translation:** local LLM (Qwen3.5-0.8B GGUF) running on **llama.cpp** via JNI.
- **Speech recognition:** **Whisper** (`whisper.cpp`) — record and transcribe offline.
- **Voice activity detection:** **Silero VAD** (ONNX Runtime) to cut clean audio segments.
- **Photo OCR:** **PaddleOCR PP-OCR** (ONNX) — point the camera at a sign and translate it.
- **Text-to-speech:** **Piper** neural voices (sherpa-onnx + espeak-ng), fully offline.
- **UI:** a clean, Google-Translate-style dark interface.

> The only moment a connection is needed is the **first model download** (Wi-Fi
> recommended). After that everything runs locally.

### Features

| | |
|---|---|
| 🌍 **17+ languages** | Auto-detect plus Spanish, English, French, German, Italian, Portuguese, Russian, Chinese, Japanese, Korean, Arabic, Hindi, Turkish, Dutch, Polish, Ukrainian… |
| ⌨️ **Text translation** | Type or paste, with an ES/EN translated interface and language selector. |
| 🎤 **Voice translation** | Whisper ASR + VAD: press the mic, speak naturally, get text. |
| 📷 **Photo translation (OCR)** | Camera or gallery → offline text extraction → editable input → translation. |
| 🔊 **Offline TTS** | Piper female voices (ES, EN, FR, DE, …) or the system engine as fallback. |
| 🕘 **History** | Your translations are stored locally and can be revisited. |
| ⚙️ **Model manager** | Download or import GGUF/Whisper/Piper models from storage. |
| 🔒 **Private by design** | Nothing leaves the phone. No ads, no analytics, no servers. |

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
                 │  llama.cpp     │      │  whisper.cpp   │
                 │ (Qwen3.5 GGUF) │      │  (Whisper)     │
                 │  llama_jni     │      │  whisper_jni   │
                 └────────────────┘      └────────────────┘
                         │                       │
                 ONNX Runtime (Silero VAD · PaddleOCR PP-OCR)
                         │
                 sherpa-onnx (Piper TTS + espeak-ng)
```

- **JNI layer** (`app/src/main/cpp`): thin C++ bridges `llama_jni.cpp` and
  `whisper_jni.cpp`, linked against static builds of `llama.cpp` and `whisper.cpp`
  produced by `scripts/build_native.sh` (Android NDK, `arm64-v8a`, 16 KB-aligned).
- **Kotlin layer** (`app/src/main/java/com/zota/traductor`): `TranslationPipeline`
  orchestrates ASR → prompt → LLM → post-processing → TTS; `ModelManager` handles
  downloads/import; `Prompts` builds the Qwen chat prompt; `HistoryStore` persists.
- **Models downloaded at runtime** (never bundled in the APK) to the app's private
  storage, sizes shown in Settings.

### Requirements

- Android **8.0 (API 26)** or newer.
- **arm64-v8a** device (most modern phones). The APK ships a single ABI.
- **~700 MB free** for the first model download (translation + speech + VAD).
- For building: **JDK 17**, **Android SDK 35**, **NDK 27.0.12077973**, **CMake 3.22.1**.

### How to build

```bash
# 1. Native libraries (llama.cpp + whisper.cpp → static .a for arm64-v8a)
./scripts/fetch-deps.sh          # clone the pinned llama.cpp / whisper.cpp (submodules)
./scripts/build_native.sh        # compile them with the NDK

# 2. The app
./gradlew :app:assembleDebug     # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest # host unit tests

# 3. Install
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If the submodules are not initialised, `fetch-deps.sh` clones them at the pinned
commits:

- `llama.cpp` → `4f5406761517648c23dbd60ea5ade37f77a316c9`
- `whisper.cpp` → `4afec37b797ab531aaf363208d79d541fbc17ff4`

### Repository structure

```
traductor/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── java/com/zota/traductor/   # Kotlin: pipeline, ASR, TTS, OCR, UI
│       │   ├── cpp/                       # JNI bridges + llama.cpp/whisper.cpp (submodules)
│       │   ├── res/                       # layouts, strings (es/en), mipmaps, adaptive icon
│       │   └── AndroidManifest.xml
│       └── test/                          # host unit tests (JUnit)
├── assets/icon/                           # icon sources + generate_icons.py
├── docs/                                  # bilingual website (GitHub Pages)
├── scripts/
│   ├── fetch-deps.sh                      # clone vendor deps at pinned commits
│   └── build_native.sh                    # NDK build of the native libraries
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
| [llama.cpp](https://github.com/ggml-org/llama.cpp) | MIT | Local LLM inference |
| [whisper.cpp](https://github.com/ggml-org/whisper.cpp) | MIT | Speech recognition |
| [Whisper](https://github.com/openai/whisper) | MIT | ASR model |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | Apache-2.0 | Piper TTS runtime |
| [Piper](https://github.com/rhasspy/piper) | MIT | Neural TTS voices |
| [espeak-ng](https://github.com/espeak-ng/espeak-ng) | GPL-3.0 | Piper phonemization |
| [PaddleOCR (PP-OCR)](https://github.com/PaddlePaddle/PaddleOCR) | Apache-2.0 | Photo OCR |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | MIT | VAD + OCR inference |
| [Silero VAD](https://github.com/snakers4/silero-vad) | MIT | Voice activity detection |
| [Qwen3.5-0.8B GGUF](https://huggingface.co/lmstudio-community/Qwen3.5-0.8B-GGUF) | Apache-2.0 | Translation model |

Model files are downloaded from their official repositories and remain subject to
their own licenses.

### Credits

**Created by [Andres Mag](https://github.com/) · Cuba.** 🇨🇺

Built on the shoulders of the open-source community above. See
[`docs/index.html`](docs/index.html) for the full story.

### License

The application code is released under the **MIT License**. Third-party components
keep their own licenses (see above).

---

## 🇪🇸 Español

### ¿Qué es?

**Traductor** es una aplicación nativa de Android que traduce texto, voz y fotos
**íntegramente en el dispositivo**. Sin nube, sin cuentas y sin telemetría: una vez
descargados los modelos, funciona incluso en modo avión.

- **Traducción:** LLM local (Qwen3.5-0.8B GGUF) sobre **llama.cpp** vía JNI.
- **Reconocimiento de voz:** **Whisper** (`whisper.cpp`) — graba y transcribe offline.
- **Detección de voz:** **Silero VAD** (ONNX Runtime) para cortar segmentos limpios.
- **OCR de fotos:** **PaddleOCR PP-OCR** (ONNX) — apunta a un cartel y tradúcelo.
- **Texto a voz:** voces neuronales **Piper** (sherpa-onnx + espeak-ng), 100% offline.
- **Interfaz:** limpia, estilo Google Translate, tema oscuro.

> El único momento en que hace falta conexión es la **primera descarga de modelos**
> (se recomienda Wi-Fi). Después, todo se ejecuta en local.

### Características

| | |
|---|---|
| 🌍 **17+ idiomas** | Detección automática y español, inglés, francés, alemán, italiano, portugués, ruso, chino, japonés, coreano, árabe, hindi, turco, neerlandés, polaco, ucraniano… |
| ⌨️ **Traducción de texto** | Escribe o pega, con interfaz traducida ES/EN y selector de idiomas. |
| 🎤 **Traducción de voz** | ASR Whisper + VAD: pulsa el micro, habla con naturalidad y obtén el texto. |
| 📷 **Traducción de fotos (OCR)** | Cámara o galería → extracción de texto offline → entrada editable → traducción. |
| 🔊 **TTS offline** | Voces femeninas Piper (ES, EN, FR, DE, …) o el motor del sistema como reserva. |
| 🕘 **Historial** | Tus traducciones se guardan en local y puedes consultarlas. |
| ⚙️ **Gestor de modelos** | Descarga o importa modelos GGUF/Whisper/Piper desde el almacenamiento. |
| 🔒 **Privacidad por diseño** | Nada sale del teléfono. Sin anuncios, sin analíticas, sin servidores. |

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
                 │  llama.cpp     │      │  whisper.cpp   │
                 │ (Qwen3.5 GGUF) │      │  (Whisper)     │
                 │  llama_jni     │      │  whisper_jni   │
                 └────────────────┘      └────────────────┘
                         │                       │
                 ONNX Runtime (Silero VAD · PaddleOCR PP-OCR)
                         │
                 sherpa-onnx (Piper TTS + espeak-ng)
```

- **Capa JNI** (`app/src/main/cpp`): puentes C++ finos `llama_jni.cpp` y
  `whisper_jni.cpp`, enlazados contra builds estáticos de `llama.cpp` y
  `whisper.cpp` generados por `scripts/build_native.sh` (NDK de Android,
  `arm64-v8a`, alineado a 16 KB).
- **Capa Kotlin** (`app/src/main/java/com/zota/traductor`): `TranslationPipeline`
  orquesta ASR → prompt → LLM → post-proceso → TTS; `ModelManager` gestiona
  descargas/importaciones; `Prompts` construye el chat de Qwen; `HistoryStore` persiste.
- **Modelos descargados en tiempo de ejecución** (nunca incluidos en el APK) al
  almacenamiento privado de la app, con tamaños visibles en Ajustes.

### Requisitos

- Android **8.0 (API 26)** o superior.
- Dispositivo **arm64-v8a** (la mayoría de móviles modernos). El APK trae una sola ABI.
- **~700 MB libres** para la primera descarga de modelos (traducción + voz + VAD).
- Para compilar: **JDK 17**, **Android SDK 35**, **NDK 27.0.12077973**, **CMake 3.22.1**.

### Cómo compilar

```bash
# 1. Librerías nativas (llama.cpp + whisper.cpp → .a estáticas para arm64-v8a)
./scripts/fetch-deps.sh          # clona llama.cpp / whisper.cpp fijados (submódulos)
./scripts/build_native.sh        # los compila con el NDK

# 2. La app
./gradlew :app:assembleDebug     # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest # tests unitarios de host

# 3. Instalar
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Si los submódulos no están inicializados, `fetch-deps.sh` los clona en los commits
fijados:

- `llama.cpp` → `4f5406761517648c23dbd60ea5ade37f77a316c9`
- `whisper.cpp` → `4afec37b797ab531aaf363208d79d541fbc17ff4`

### Estructura del repositorio

```
traductor/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── java/com/zota/traductor/   # Kotlin: pipeline, ASR, TTS, OCR, UI
│       │   ├── cpp/                       # puentes JNI + llama.cpp/whisper.cpp (submódulos)
│       │   ├── res/                       # layouts, strings (es/en), mipmaps, icono adaptativo
│       │   └── AndroidManifest.xml
│       └── test/                          # tests unitarios de host (JUnit)
├── assets/icon/                           # fuentes del icono + generate_icons.py
├── docs/                                  # web bilingüe (GitHub Pages)
├── scripts/
│   ├── fetch-deps.sh                      # clona las dependencias fijadas
│   └── build_native.sh                    # build NDK de las librerías nativas
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
| [llama.cpp](https://github.com/ggml-org/llama.cpp) | MIT | Inferencia LLM local |
| [whisper.cpp](https://github.com/ggml-org/whisper.cpp) | MIT | Reconocimiento de voz |
| [Whisper](https://github.com/openai/whisper) | MIT | Modelo ASR |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | Apache-2.0 | Runtime de Piper TTS |
| [Piper](https://github.com/rhasspy/piper) | MIT | Voces neuronales TTS |
| [espeak-ng](https://github.com/espeak-ng/espeak-ng) | GPL-3.0 | Fonemización de Piper |
| [PaddleOCR (PP-OCR)](https://github.com/PaddlePaddle/PaddleOCR) | Apache-2.0 | OCR de fotos |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | MIT | Inferencia VAD + OCR |
| [Silero VAD](https://github.com/snakers4/silero-vad) | MIT | Detección de actividad de voz |
| [Qwen3.5-0.8B GGUF](https://huggingface.co/lmstudio-community/Qwen3.5-0.8B-GGUF) | Apache-2.0 | Modelo de traducción |

Los ficheros de modelos se descargan de sus repositorios oficiales y quedan sujetos
a sus propias licencias.

### Créditos

**Creado por [Andres Mag](https://github.com/) · Cuba.** 🇨🇺

Construido sobre el trabajo de la comunidad de código abierto citada arriba. La
historia completa está en [`docs/index.html`](docs/index.html).

### Licencia

El código de la aplicación se publica bajo **Licencia MIT**. Los componentes de
terceros conservan sus propias licencias (ver arriba).

---

<div align="center">

**Traductor v0.6.0** · Made with ❤️ in Cuba 🇨🇺 · 100% offline

</div>
