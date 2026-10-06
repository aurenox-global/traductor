plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zota.traductor"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.zota.traductor"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.6.0"

        ndk {
            // arm64-v8a principal (OnePlus PLB110).
            abiFilters += listOf("arm64-v8a")
        }

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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
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
