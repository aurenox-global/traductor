// JNI wrapper para llama.cpp (Qwen3.5-0.8B Q4_K_M). Backend CPU.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "llama_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    int n_threads = 4;
    std::mutex mtx;
    std::atomic<bool> cancel{false};
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
Java_com_zota_traductor_LlamaBridge_nativeInit(JNIEnv * env, jclass, jstring jpath, jint nThreads, jint nCtx) {
    const std::string path = jstr(env, jpath);
    llama_backend_init();

    auto * e = new Engine();
    e->n_threads = nThreads > 0 ? nThreads : 4;

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;   // CPU puro

    e->model = llama_model_load_from_file(path.c_str(), mparams);
    if (e->model == nullptr) {
        LOGE("no se pudo cargar el modelo: %s", path.c_str());
        delete e;
        return 0;
    }
    e->vocab = llama_model_get_vocab(e->model);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = nCtx > 0 ? (uint32_t) nCtx : 2048;
    cparams.n_batch = 512;
    cparams.n_threads = e->n_threads;
    cparams.n_threads_batch = e->n_threads;

    e->ctx = llama_init_from_model(e->model, cparams);
    if (e->ctx == nullptr) {
        LOGE("no se pudo crear el contexto");
        llama_model_free(e->model);
        delete e;
        return 0;
    }
    LOGI("modelo cargado OK (%s)", path.c_str());
    return reinterpret_cast<jlong>(e);
}

extern "C" JNIEXPORT void JNICALL
Java_com_zota_traductor_LlamaBridge_nativeFree(JNIEnv *, jclass, jlong h) {
    auto * e = reinterpret_cast<Engine *>(h);
    if (e == nullptr) return;
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}

extern "C" JNIEXPORT void JNICALL
Java_com_zota_traductor_LlamaBridge_nativeCancel(JNIEnv *, jclass, jlong h) {
    auto * e = reinterpret_cast<Engine *>(h);
    if (e) e->cancel.store(true);
}

// Genera una traduccion. cb debe exponer: void onToken(String piece)
extern "C" JNIEXPORT jstring JNICALL
Java_com_zota_traductor_LlamaBridge_nativeGenerate(
        JNIEnv * env, jclass, jlong h, jstring jsystem, jstring juser,
        jint maxTokens, jobject cb) {

    auto * e = reinterpret_cast<Engine *>(h);
    if (e == nullptr) return env->NewStringUTF("");

    const std::string system = jstr(env, jsystem);
    const std::string user = jstr(env, juser);

    std::lock_guard<std::mutex> lock(e->mtx);
    e->cancel.store(false);

    // --- 1. chat template (Qwen) ---
    std::string prompt;
    {
        llama_chat_message msgs[2];
        msgs[0].role = "system";
        msgs[0].content = system.c_str();
        msgs[1].role = "user";
        msgs[1].content = user.c_str();

        std::vector<char> buf(user.size() + system.size() + 1024);
        int32_t n = llama_chat_apply_template(nullptr, msgs, 2, true, buf.data(), (int32_t) buf.size());
        if (n > (int32_t) buf.size()) {
            buf.resize(n + 1);
            n = llama_chat_apply_template(nullptr, msgs, 2, true, buf.data(), (int32_t) buf.size());
        }
        if (n > 0) {
            prompt.assign(buf.data(), n);
        } else {
            // Fallback ChatML (Qwen)
            prompt = "<|im_start|>system\n" + system +
                     "<|im_end|>\n<|im_start|>user\n" + user +
                     "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n";
        }
    }

    // --- 1b. Desactivar el razonamiento de Qwen3.5 -------------------------
    // El template deja el bloque de "thinking" ABIERTO ("<think>\n") cuando
    // esta activado; entonces el modelo divaga y filtra ese texto
    // ("* Wait, the user prompt says...") en la traduccion. Lo cerramos VACIO
    // ("<think>\n\n</think>\n\n") para que responda directo con la traduccion.
    {
        auto ends_with = [](const std::string &s, const std::string &suf) {
            return s.size() >= suf.size() &&
                   s.compare(s.size() - suf.size(), suf.size(), suf) == 0;
        };
        const std::string open_nl = "<think>\n";
        const std::string open = "<think>";
        const std::string closed = "<think>\n\n</think>\n\n";
        const std::string assistant_hdr = "<|im_start|>assistant\n";
        const std::string assistant_hdr2 = "<|im_start|>assistant";
        if (ends_with(prompt, open_nl)) {
            // bloque abierto -> cerrarlo vacío
            prompt.erase(prompt.size() - open_nl.size());
            prompt += closed;
        } else if (ends_with(prompt, open)) {
            prompt.erase(prompt.size() - open.size());
            prompt += closed;
        } else if (ends_with(prompt, assistant_hdr)) {
            // CHATML sin bloque think (caso Qwen3.5 en este llama.cpp): añadirlo CERRADO
            prompt += closed;
        } else if (ends_with(prompt, assistant_hdr2)) {
            prompt += "\n" + closed;
        }
    }

    // --- 2. tokenizar ---
    std::vector<llama_token> tokens(prompt.size() + 16);
    int32_t n_tok = llama_tokenize(e->vocab, prompt.c_str(), (int32_t) prompt.size(),
                                   tokens.data(), (int32_t) tokens.size(), true, true);
    if (n_tok < 0) {
        tokens.resize(-n_tok);
        n_tok = llama_tokenize(e->vocab, prompt.c_str(), (int32_t) prompt.size(),
                               tokens.data(), (int32_t) tokens.size(), true, true);
    }
    if (n_tok <= 0) { LOGE("tokenize fallo"); return env->NewStringUTF(""); }
    tokens.resize(n_tok);

    // --- 3. limpiar KV y evaluar prompt ---
    llama_memory_clear(llama_get_memory(e->ctx), true);

    const int n_ctx = (int) llama_n_ctx(e->ctx);
    if ((int) tokens.size() > n_ctx - 8) {
        tokens.erase(tokens.begin(), tokens.begin() + ((int) tokens.size() - (n_ctx - 8)));
    }

    for (size_t i = 0; i < tokens.size(); i += 512) {
        const int n = (int) std::min<size_t>(512, tokens.size() - i);
        llama_batch b = llama_batch_get_one(tokens.data() + i, n);
        if (llama_decode(e->ctx, b) != 0) { LOGE("decode(prompt) fallo"); return env->NewStringUTF(""); }
    }

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    // --- 4. bucle de generacion ---
    jclass cbClass = nullptr;
    jmethodID cbMethod = nullptr;
    if (cb != nullptr) {
        cbClass = env->GetObjectClass(cb);
        cbMethod = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    }

    std::string out;
    llama_token id = 0;
    for (int i = 0; i < maxTokens && !e->cancel.load(); ++i) {
        id = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, id)) break;

        char piece[256];
        int np = llama_token_to_piece(e->vocab, id, piece, sizeof(piece), 0, false);
        if (np > 0) {
            std::string p(piece, np);
            out += p;
            if (cbMethod != nullptr) {
                jstring js = env->NewStringUTF(p.c_str());
                env->CallVoidMethod(cb, cbMethod, js);
                if (env->ExceptionCheck()) env->ExceptionClear();
                env->DeleteLocalRef(js);
            }
        }
        llama_sampler_accept(smpl, id);

        llama_batch b = llama_batch_get_one(&id, 1);
        if (llama_decode(e->ctx, b) != 0) { LOGE("decode(token) fallo"); break; }
    }

    llama_sampler_free(smpl);
    if (cbClass) env->DeleteLocalRef(cbClass);
    return env->NewStringUTF(out.c_str());
}
