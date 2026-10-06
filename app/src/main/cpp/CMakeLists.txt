cmake_minimum_required(VERSION 3.22)
project(traductor_jni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_DIR   "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")
set(WHISPER_DIR "${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp")

file(GLOB LLAMA_LIBS   "${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/llama/${ANDROID_ABI}/*.a")
file(GLOB WHISPER_LIBS "${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/whisper/${ANDROID_ABI}/*.a")

if(NOT LLAMA_LIBS OR NOT WHISPER_LIBS)
    message(FATAL_ERROR
        "Faltan librerias nativas prebuilt para ${ANDROID_ABI}.\n"
        "Ejecuta primero:  ABIS=\"${ANDROID_ABI}\" ./scripts/build_native.sh")
endif()

# ---------------- llama.cpp (Qwen3.5-0.8B) ----------------
add_library(llamajni SHARED llama_jni.cpp)
target_include_directories(llamajni PRIVATE
    "${LLAMA_DIR}/include"
    "${LLAMA_DIR}/ggml/include")
target_link_options(llamajni PRIVATE -Wl,-Bsymbolic -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
target_link_libraries(llamajni PRIVATE
    -Wl,--start-group ${LLAMA_LIBS} -Wl,--end-group
    log)

# ---------------- whisper.cpp (ASR) ----------------
add_library(whisperjni SHARED whisper_jni.cpp)
target_include_directories(whisperjni PRIVATE
    "${WHISPER_DIR}/include"
    "${WHISPER_DIR}/ggml/include")
target_link_options(whisperjni PRIVATE -Wl,-Bsymbolic -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
target_link_libraries(whisperjni PRIVATE
    -Wl,--start-group ${WHISPER_LIBS} -Wl,--end-group
    log)
