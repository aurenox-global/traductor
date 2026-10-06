package com.zota.traductor

import android.util.Log

/**
 * Puente JNI con whisper.cpp (libwhisperjni.so).
 * Entrada: PCM float mono 16 kHz en [-1, 1].
 */
object WhisperBridge {

    private const val TAG = "WhisperBridge"

    @Volatile private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("whisperjni")
            loaded = true
            true
        } catch (t: Throwable) {
            Log.e(TAG, "no se pudo cargar libwhisperjni.so: ${t.message}")
            false
        }
    }

    external fun nativeInit(path: String, nThreads: Int, useGpu: Boolean): Long
    external fun nativeFree(handle: Long)
    external fun nativeTranscribe(handle: Long, pcm: FloatArray, lang: String): String
    external fun nativeLastLanguage(handle: Long): String
}
