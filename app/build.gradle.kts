plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// Variante FULL (todo empaquetado) vs. build normal LITE.
//
//   ./gradlew :app:assembleDebug                 -> LITE (descarga en 1er arranque)
//   ./gradlew :app:assembleDebug -Pbundled=true  -> FULL (modelos dentro de assets)
//
// La propiedad `bundled` NO usa flavors: el build normal queda igual (mismos
// srcDirs, sin assets extra). Solo cuando está activa se añade `bundled-assets/`
// como srcDir de assets y se define el flag BUNDLED_MODELS en BuildConfig.
//
// Los assets de la variante FULL se generan con `scripts/fetch_bundled_assets.sh`
// (no se versionan, ver .gitignore).
// ---------------------------------------------------------------------------
val bundled: Boolean = (project.findProperty("bundled") as String?)?.toBoolean() ?: false

android {
    namespace = "com.zota.traductor"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.zota.traductor"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "0.9.4"

        ndk {
            // arm64-v8a principal (OnePlus PLB110).
            abiFilters += listOf("arm64-v8a")
        }

        // true en la APK FULL (modelos en assets); false en el build normal LITE.
        buildConfigField("boolean", "BUNDLED_MODELS", bundled.toString())

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

if (bundled) {
    // Los modelos bundleados se añaden SOLO a la variante FULL.
    android.sourceSets.getByName("main").assets.srcDir("bundled-assets")

    // Copia el APK FULL a un nombre distinto para no pisar el LITE.
    // (assembleDebug lo registra AGP en su propio afterEvaluate: hay que esperar.)
    val copyFullApk = tasks.register<Copy>("copyFullApk") {
        from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
        into(layout.buildDirectory.dir("outputs/apk/full"))
        rename { "traductor-full-arm64-debug.apk" }
    }
    afterEvaluate {
        tasks.named("assembleDebug") { finalizedBy(copyFullApk) }
    }
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
