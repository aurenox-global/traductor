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

    // --- 1. chat template (Qwen) + sellado del bloque de razonamiento ---
    // Se factoriza en una lambda para poder reconstruir el prompt con el USER
    // recortado (fallback de contexto) sin perder el SYSTEM.
    auto build_prompt = [&](const std::string & sys, const std::string & usr) -> std::string {
        std::string p;
        {
            llama_chat_message msgs[2];
            msgs[0].role = "system";
            msgs[0].content = sys.c_str();
            msgs[1].role = "user";
            msgs[1].content = usr.c_str();

            std::vector<char> buf(usr.size() + sys.size() + 1024);
            int32_t n = llama_chat_apply_template(nullptr, msgs, 2, true, buf.data(), (int32_t) buf.size());
            if (n > (int32_t) buf.size()) {
                buf.resize(n + 1);
                n = llama_chat_apply_template(nullptr, msgs, 2, true, buf.data(), (int32_t) buf.size());
            }
            if (n > 0) {
                p.assign(buf.data(), n);
            } else {
                // Fallback ChatML (Qwen)
                p = "<|im_start|>system\n" + sys +
                    "<|im_end|>\n<|im_start|>user\n" + usr +
                    "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n";
            }
        }

        // --- 1b. Desactivar el razonamiento de Qwen3.5 ---------------------
        // El template deja el bloque de "thinking" ABIERTO ("<think>\n") cuando
        // esta activado; entonces el modelo divaga y filtra ese texto
        // ("* Wait, the user prompt says...") en la traduccion. Lo cerramos VACIO
        // ("<think>\n\n</think>\n\n") para que responda directo con la traduccion.
        auto ends_with = [](const std::string &s, const std::string &suf) {
            return s.size() >= suf.size() &&
                   s.compare(s.size() - suf.size(), suf.size(), suf) == 0;
        };
        const std::string open_nl = "<think>\n";
        const std::string open = "<think>";
        const std::string closed = "<think>\n\n</think>\n\n";
        const std::string assistant_hdr = "<|im_start|>assistant\n";
        const std::string assistant_hdr2 = "<|im_start|>assistant";
        if (ends_with(p, open_nl)) {
            // bloque abierto -> cerrarlo vacío
            p.erase(p.size() - open_nl.size());
            p += closed;
        } else if (ends_with(p, open)) {
            p.erase(p.size() - open.size());
            p += closed;
        } else if (ends_with(p, assistant_hdr)) {
            // CHATML sin bloque think (caso Qwen3.5 en este llama.cpp): añadirlo CERRADO
            p += closed;
        } else if (ends_with(p, assistant_hdr2)) {
            p += "\n" + closed;
        }
        return p;
    };

    auto count_tokens = [&](const std::string & p) -> int {
        std::vector<llama_token> t(p.size() + 16);
        int32_t n = llama_tokenize(e->vocab, p.c_str(), (int32_t) p.size(),
                                   t.data(), (int32_t) t.size(), true, true);
        if (n < 0) {
            t.resize(-n);
            n = llama_tokenize(e->vocab, p.c_str(), (int32_t) p.size(),
                               t.data(), (int32_t) t.size(), true, true);
        }
        return n;
    };

    std::string prompt = build_prompt(system, user);

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
    const int budget = n_ctx - 8;
    if ((int) tokens.size() > budget) {
        // Fallback (el chunking en Kotlin ya lo evita): CONSERVAR el mensaje
        // SYSTEM y recortar el USER por el final, nunca quedarse con la cola
        // (eso borraba la instruccion de sistema y rompia el prompt).
        const int sys_toks = count_tokens(build_prompt(system, ""));
        const int user_budget = budget - sys_toks;
        std::string u = user;
        if (user_budget > 8) {
            // Encoge el USER por caracteres hasta que el prompt quepa holgado.
            while (!u.empty() && count_tokens(build_prompt(system, u)) > budget) {
                size_t next = u.size() * 3 / 4;
                if (next >= u.size()) next = u.size() - 1;
                u.resize(next);
            }
        } else {
            u.clear(); // el SYSTEM solo ya llena el contexto
        }
        prompt = build_prompt(system, u);
        std::vector<llama_token> t2(prompt.size() + 16);
        int32_t n2 = llama_tokenize(e->vocab, prompt.c_str(), (int32_t) prompt.size(),
                                    t2.data(), (int32_t) t2.size(), true, true);
        if (n2 < 0) {
            t2.resize(-n2);
            n2 = llama_tokenize(e->vocab, prompt.c_str(), (int32_t) prompt.size(),
                                t2.data(), (int32_t) t2.size(), true, true);
        }
        if (n2 <= 0) { LOGE("tokenize fallo (recorte)"); return env->NewStringUTF(""); }
        t2.resize(n2);
        // Ultimo recurso: conservar el INICIO (SYSTEM), jamas la cola.
        if ((int) t2.size() > budget) t2.resize(budget);
        tokens.swap(t2);
        LOGI("prompt recortado -> %d tokens (SYSTEM conservado, USER recortado)", (int) tokens.size());
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
