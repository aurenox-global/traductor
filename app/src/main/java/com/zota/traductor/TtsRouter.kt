package com.zota.traductor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Selector de motor de voz. Regla:
 *   1) Si hay una voz Piper instalada para el idioma -> Piper (neuronal, offline).
 *   2) Si no hay voz, o Piper falla -> motor TTS del sistema
 *      (prefiriendo com.google.android.tts, como ya hacíamos).
 *
 * Así los botones 🔊 nunca dejan de funcionar, pero usan Piper por defecto.
 */
class TtsRouter(private val ctx: Context) {

    companion object { private const val TAG = "TtsRouter" }

    private val system = TtsHelper(ctx)
    private val piper = PiperTts(ctx)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    fun init(onReady: (Boolean) -> Unit) = system.init(onReady)

    fun speak(text: String, langCode: String) {
        if (text.isBlank()) return
        val voice = runCatching { PiperVoiceManager.resolveForLang(ctx, langCode) }.getOrNull()
        if (voice == null) {
            speakSystem(text, langCode)
            return
        }
        scope.launch {
            val ok = runCatching { piper.speak(text, voice) }.getOrElse {
                Log.e(TAG, "Piper falló, uso el sistema: ${it.message}")
                false
            }
            if (!ok) speakSystem(text, langCode)
        }
    }

    private fun speakSystem(text: String, langCode: String) {
        main.post { runCatching { system.speak(text, langCode) } }
    }

    fun stop() {
        runCatching { piper.stop() }
        runCatching { system.stop() }
    }

    fun shutdown() {
        runCatching { piper.release() }
        system.shutdown()
    }

    /** true si hay voz Piper instalada y habilitada para ese idioma. */
    fun isPiperAvailable(langCode: String): Boolean =
        runCatching { PiperVoiceManager.resolveForLang(ctx, langCode) != null }.getOrDefault(false)
}
