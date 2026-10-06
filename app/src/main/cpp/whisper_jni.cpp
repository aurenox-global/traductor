// JNI wrapper para whisper.cpp (ASR multilingue). Backend CPU.
#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "whisper.h"

#define TAG "whisper_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct WEngine {
    whisper_context * ctx = nullptr;
    int n_threads = 4;
    int last_lang_id = -1;
    std::mutex mtx;
};

std::string jstr(JNIEnv * env, jstring s) {
    if (s == nullptr) return std::string();
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c == nullptr ? "" : c);
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_zota_traductor_WhisperBridge_nativeInit(JNIEnv * env, jclass, jstring jpath, jint nThreads, jboolean useGpu) {
    const std::string path = jstr(env, jpath);

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = useGpu == JNI_TRUE;

    whisper_context * ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    if (ctx == nullptr) {
        LOGE("no se pudo cargar el modelo whisper: %s", path.c_str());
        return 0;
    }
    auto * e = new WEngine();
    e->ctx = ctx;
    e->n_threads = nThreads > 0 ? nThreads : 4;
    LOGI("whisper cargado OK (%s)", path.c_str());
    return reinterpret_cast<jlong>(e);
}

extern "C" JNIEXPORT void JNICALL
Java_com_zota_traductor_WhisperBridge_nativeFree(JNIEnv *, jclass, jlong h) {
    auto * e = reinterpret_cast<WEngine *>(h);
    if (e == nullptr) return;
    if (e->ctx) whisper_free(e->ctx);
    delete e;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_zota_traductor_WhisperBridge_nativeTranscribe(
        JNIEnv * env, jclass, jlong h, jfloatArray jpcm, jstring jlang) {

    auto * e = reinterpret_cast<WEngine *>(h);
    if (e == nullptr || jpcm == nullptr) return env->NewStringUTF("");

    const std::string lang = jstr(env, jlang); // "auto" o codigo ISO

    jsize n = env->GetArrayLength(jpcm);
    std::vector<float> pcm((size_t) n);
    env->GetFloatArrayRegion(jpcm, 0, n, pcm.data());

    std::lock_guard<std::mutex> lock(e->mtx);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress   = false;
    params.print_realtime   = false;
    params.print_timestamps = false;
    params.print_special    = false;
    params.translate        = false;
    params.no_context       = true;
    params.single_segment   = false;
    params.suppress_blank   = true;
    params.n_threads        = e->n_threads;
    params.audio_ctx        = 0;

    static thread_local std::string langbuf;
    if (lang.empty() || lang == "auto") {
        langbuf = "auto";
        params.detect_language = true;
    } else {
        langbuf = lang;
        params.detect_language = false;
    }
    params.language = langbuf.c_str();

    if (whisper_full(e->ctx, params, pcm.data(), (int) pcm.size()) != 0) {
        LOGE("whisper_full fallo");
        return env->NewStringUTF("");
    }

    e->last_lang_id = whisper_full_lang_id(e->ctx);

    std::string out;
    const int n_seg = whisper_full_n_segments(e->ctx);
    for (int i = 0; i < n_seg; ++i) {
        const char * t = whisper_full_get_segment_text(e->ctx, i);
        if (t) out += t;
    }
    // limpiar espacios iniciales
    size_t s = out.find_first_not_of(" \t\n");
    if (s == std::string::npos) out.clear(); else out = out.substr(s);
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_zota_traductor_WhisperBridge_nativeLastLanguage(JNIEnv * env, jclass, jlong h) {
    auto * e = reinterpret_cast<WEngine *>(h);
    if (e == nullptr || e->last_lang_id < 0) return env->NewStringUTF("");
    const char * s = whisper_lang_str(e->last_lang_id);
    return env->NewStringUTF(s == nullptr ? "" : s);
}
