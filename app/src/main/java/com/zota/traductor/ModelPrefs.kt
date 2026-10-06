package com.zota.traductor

import android.content.Context
import java.io.File

/**
 * Preferencias persistentes: modelos activos, idiomas y opciones de la UI.
 * No guarda secretos.
 */
object ModelPrefs {

    private const val PREF = "traductor_prefs"

    private const val K_ASR = "active_asr_path"
    private const val K_MT = "active_mt_path"
    private const val K_SRC = "last_source"
    private const val K_DST = "last_target"
    private const val K_AUTO_TTS = "auto_tts"
    private const val K_PIPER_ON = "piper_enabled"
    private const val K_PIPER_VOICE = "active_piper_voice"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ---- modelos activos (ruta absoluta) ----

    fun activeAsrPath(ctx: Context): String? = p(ctx).getString(K_ASR, null)
    fun setActiveAsr(ctx: Context, file: File) {
        p(ctx).edit().putString(K_ASR, file.absolutePath).apply()
    }
    fun clearActiveAsr(ctx: Context) {
        p(ctx).edit().remove(K_ASR).apply()
    }

    fun activeMtPath(ctx: Context): String? = p(ctx).getString(K_MT, null)
    fun setActiveMt(ctx: Context, file: File) {
        p(ctx).edit().putString(K_MT, file.absolutePath).apply()
    }
    fun clearActiveMt(ctx: Context) {
        p(ctx).edit().remove(K_MT).apply()
    }

    // ---- idiomas ----

    fun lastSource(ctx: Context): String =
        p(ctx).getString(K_SRC, Languages.DEFAULT_SOURCE) ?: Languages.DEFAULT_SOURCE

    fun setLastSource(ctx: Context, code: String) {
        p(ctx).edit().putString(K_SRC, code).apply()
    }

    fun lastTarget(ctx: Context): String =
        p(ctx).getString(K_DST, Languages.DEFAULT_TARGET) ?: Languages.DEFAULT_TARGET

    fun setLastTarget(ctx: Context, code: String) {
        p(ctx).edit().putString(K_DST, code).apply()
    }

    // ---- opciones ----

    fun autoTts(ctx: Context): Boolean = p(ctx).getBoolean(K_AUTO_TTS, true)
    fun setAutoTts(ctx: Context, v: Boolean) {
        p(ctx).edit().putBoolean(K_AUTO_TTS, v).apply()
    }

    // ---- TTS neuronal (Piper) ----

    /** Preferir Piper (offline) sobre el motor TTS del sistema. */
    fun piperEnabled(ctx: Context): Boolean = p(ctx).getBoolean(K_PIPER_ON, true)
    fun setPiperEnabled(ctx: Context, v: Boolean) {
        p(ctx).edit().putBoolean(K_PIPER_ON, v).apply()
    }

    /** Voz Piper activa (id de la carpeta en piper_voices/). */
    fun activePiperVoiceId(ctx: Context): String? = p(ctx).getString(K_PIPER_VOICE, null)
    fun setActivePiperVoice(ctx: Context, id: String) {
        p(ctx).edit().putString(K_PIPER_VOICE, id).apply()
    }
    fun clearActivePiperVoice(ctx: Context) {
        p(ctx).edit().remove(K_PIPER_VOICE).apply()
    }

    // ---- deteccion de idioma (Whisper) ----

    private const val K_DETECTED = "last_detected_lang"

    fun lastDetected(ctx: Context): String? = p(ctx).getString(K_DETECTED, null)
    fun setLastDetected(ctx: Context, code: String?) {
        if (code.isNullOrBlank()) p(ctx).edit().remove(K_DETECTED).apply()
        else p(ctx).edit().putString(K_DETECTED, code).apply()
    }
}
