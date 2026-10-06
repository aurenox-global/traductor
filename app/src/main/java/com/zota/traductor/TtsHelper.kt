package com.zota.traductor

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/**
 * TTS con seleccion explicita de motor. En algunos dispositivos (p.ej. OPPO/OnePlus)
 * el motor por defecto falla: preferimos com.google.android.tts si esta instalado.
 */
class TtsHelper(private val ctx: Context) {

    companion object {
        private const val TAG = "TtsHelper"
        private const val PREFERRED_ENGINE = "com.google.android.tts"
    }

    private var tts: TextToSpeech? = null
    private var ready = false

    fun installedEngines(): List<String> {
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        return try {
            ctx.packageManager.queryIntentServices(intent, 0).mapNotNull { it.serviceInfo?.packageName }.distinct()
        } catch (t: Throwable) {
            Log.w(TAG, "no se pudieron listar motores TTS: ${t.message}")
            emptyList()
        }
    }

    fun init(onReady: (Boolean) -> Unit) {
        val engines = installedEngines()
        Log.i(TAG, "motores TTS disponibles: $engines")
        val engine = if (engines.contains(PREFERRED_ENGINE)) PREFERRED_ENGINE else null
        Log.i(TAG, "usando motor TTS: ${engine ?: "(por defecto)"}")

        val listener = TextToSpeech.OnInitListener { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) Log.e(TAG, "TTS init fallo: $status")
            onReady(ready)
        }
        tts = if (engine != null) TextToSpeech(ctx, listener, engine) else TextToSpeech(ctx, listener)
    }

    fun speak(text: String, langCode: String) {
        val t = tts ?: return
        if (!ready) return
        if (text.isBlank()) return
        try {
            val locale = Locale.forLanguageTag(langCode)
            val res = t.setLanguage(locale)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "idioma no soportado por TTS: $langCode; uso el por defecto")
                t.setLanguage(Locale.getDefault())
            }
            t.setSpeechRate(1.0f)
            t.speak(text, TextToSpeech.QUEUE_ADD, null, "traductor-${System.nanoTime()}")
        } catch (e: Throwable) {
            Log.e(TAG, "speak fallo: ${e.message}")
        }
    }

    fun stop() {
        try { tts?.stop() } catch (_: Throwable) {}
    }

    fun shutdown() {
        try { tts?.shutdown() } catch (_: Throwable) {}
        tts = null
        ready = false
    }

    @Suppress("unused")
    fun isReady(): Boolean = ready

    @Suppress("unused")
    fun currentVoice(): Voice? = try { tts?.voice } catch (_: Throwable) { null }
}
