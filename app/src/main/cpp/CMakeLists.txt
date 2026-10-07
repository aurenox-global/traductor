cmake_minimum_required(VERSION 3.22)
project(traductor_jni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_DIR   "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")
set(WHISPER_DIR "${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp")

file(GLOB WHISPER_LIBS "${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/whisper/${ANDROID_ABI}/*.a")

if(NOT WHISPER_LIBS)
    message(FATAL_ERROR
        "Faltan librerias nativas prebuilt para ${ANDROID_ABI}.\n"
        "Ejecuta primero:  ABIS=\"${ANDROID_ABI}\" ./scripts/build_native.sh")
endif()

# Variante NLLB puro: NO se compila el motor LLM (llama.cpp). El traductor es
# NLLB-200 por ONNX Runtime (org.microsoft.onnxruntime / onnxruntime-android).

# ---------------- whisper.cpp (ASR) ----------------
add_library(whisperjni SHARED whisper_jni.cpp)
target_include_directories(whisperjni PRIVATE
    "${WHISPER_DIR}/include"
    "${WHISPER_DIR}/ggml/include")
target_link_options(whisperjni PRIVATE -Wl,-Bsymbolic -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
target_link_libraries(whisperjni PRIVATE
    -Wl,--start-group ${WHISPER_LIBS} -Wl,--end-group
    log)
