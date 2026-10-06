package com.zota.traductor

import android.util.Log

/**
 * Puente JNI con llama.cpp (libllamajni.so).
 * Backend CPU, sin Vulkan/OpenCL.
 */
object LlamaBridge {

    private const val TAG = "LlamaBridge"

    @Volatile private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("llamajni")
            loaded = true
            true
        } catch (t: Throwable) {
            Log.e(TAG, "no se pudo cargar libllamajni.so: ${t.message}")
            false
        }
    }

    /** Callback de streaming token a token. */
    fun interface TokenCallback {
        fun onToken(piece: String)
    }

    external fun nativeInit(path: String, nThreads: Int, nCtx: Int): Long
    external fun nativeFree(handle: Long)
    external fun nativeCancel(handle: Long)
    external fun nativeGenerate(
        handle: Long,
        system: String,
        user: String,
        maxTokens: Int,
        cb: TokenCallback?
    ): String
}
