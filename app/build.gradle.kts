plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// Variante FULL (todo empaquetado) / LITE-OCR (OCR dentro) / build normal LITE.
//
//   ./gradlew :app:assembleDebug                   -> LITE (descarga en 1er arranque)
//   ./gradlew :app:assembleDebug -Pocrbundle=true  -> LITE-OCR (OCR en los assets)
//   ./gradlew :app:assembleDebug -Pbundled=true    -> FULL (todo en assets)
//
// Las propiedades NO usan flavors: el build normal queda igual (mismos srcDirs,
// sin assets extra). Solo cuando una está activa se añade su carpeta como srcDir
// de assets y se define el flag BUNDLED_MODELS en BuildConfig.
//
//   -Pocrbundle=true -> assets desde `bundled-ocr/`   (versión 1.0-nllb-lite)
//   -Pbundled=true   -> assets desde `bundled-assets/` (versión 1.0-nllb-full)
//
// Los assets de la FULL se generan con `scripts/fetch_bundled_assets.sh`; los del
// LITE-OCR con `scripts/fetch_ocr_bundle.sh` (no se versionan, ver .gitignore).
// ---------------------------------------------------------------------------
val bundled: Boolean = (project.findProperty("bundled") as String?)?.toBoolean() ?: false
val ocrbundle: Boolean = (project.findProperty("ocrbundle") as String?)?.toBoolean() ?: false
if (bundled && ocrbundle) {
    throw GradleException("Usa -Pbundled=true (FULL) O -Pocrbundle=true (LITE-OCR), no las dos a la vez.")
}

android {
    namespace = "com.zota.traductor"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        // Variante B: NLLB-200 puro (SIN LLM). applicationId propio para que sea
        // una app SEPARADA y no choque con las otras variantes al instalarla.
        applicationId = "com.zota.traductor.nllb"
        minSdk = 26
        targetSdk = 35
        // Variante NLLB puro: versión propia por variante (LITE-OCR / FULL / normal).
        versionCode = when {
            bundled -> 211
            ocrbundle -> 210
            else -> 202
        }
        versionName = when {
            bundled -> "1.0-nllb-full"
            ocrbundle -> "1.0-nllb-lite"
            else -> "1.0.2-nllb"
        }

        ndk {
            // arm64-v8a principal (OnePlus PLB110).
            abiFilters += listOf("arm64-v8a")
        }

        // true en las APK con modelos en assets (FULL y LITE-OCR); false en la normal.
        buildConfigField("boolean", "BUNDLED_MODELS", (bundled || ocrbundle).toString())

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            packaging {
                jniLibs {
                    // mantiene los .so sin comprimir -> instalacion mas rapida
                    useLegacyPackaging = false
                }
            }
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        // Necesario para exponer BUNDLED_MODELS. En LITE el valor es `false`.
        buildConfig = true
    }

    // Modelos ya comprimidos: no volver a comprimirlos (build y copia más rápidos;
    // además permite progreso/`openFd` correctos). Solo hay tales assets en FULL.
    androidResources {
        noCompress.addAll(listOf("gguf", "bin", "onnx", "txt", "json"))
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

if (bundled || ocrbundle) {
    // Los modelos bundleados se añaden SOLO a la variante correspondiente.
    //   -Pbundled=true   -> app/bundled-assets/ (todo: NLLB + Whisper + VAD + OCR + Piper)
    //   -Pocrbundle=true -> app/bundled-ocr/    (solo OCR + VAD)
    android.sourceSets.getByName("main").assets.srcDir(
        if (bundled) "bundled-assets" else "bundled-ocr"
    )
}

// ---------------------------------------------------------------------------
// Variante B (NLLB puro): copia la APK a dist-final/ (LITE-OCR y FULL) o a
// dist-nllb/ (build normal), con el nombre propio de cada variante.
// Se registra en afterEvaluate porque AGP registra assembleDebug tarde.
// ---------------------------------------------------------------------------
val apkOutName = when {
    bundled -> "traductor-nllb-full-arm64-debug.apk"
    ocrbundle -> "traductor-nllb-lite-arm64-debug.apk"
    else -> "traductor-nllb-arm64-debug.apk"
}
val apkOutDir = if (bundled || ocrbundle) "dist-final" else "dist-nllb"

val copyNllbApk = tasks.register<Copy>("copyNllbApk") {
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(rootProject.layout.projectDirectory.dir(apkOutDir))
    rename { apkOutName }
}
afterEvaluate {
    tasks.named("assembleDebug") { finalizedBy(copyNllbApk) }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // Orientación EXIF de las fotos usadas para OCR.
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // TTS neuronal offline (Piper/VITS + espeak-ng + onnxruntime) prebuilt para arm64-v8a.
    // El .so vive en app/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so.
    implementation(files("libs/sherpa-onnx-1.13.8.jar"))

    // Descompresión streaming de los paquetes Piper de sherpa-onnx (.tar.bz2).
    // commons-compress incluye bzip2 y gzip (no hace falta otra librería).
    implementation("org.apache.commons:commons-compress:1.27.1")

    testImplementation("junit:junit:4.13.2")
}
