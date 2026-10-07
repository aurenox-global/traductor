package com.zota.traductor

import android.content.Context
import java.io.File

/**
 * Stubs de las dependencias Android del motor NLLB que NO toca la ruta de
 * inferencia. Permiten compilar y ejecutar [NllbEngine] tal cual en el JVM.
 */
object ModelPrefs {
    // El motor sólo consulta el último idioma detectado (fallback de "auto").
    fun lastDetected(ctx: Context): String? = null
}

object NllbModels {
    val ENCODER: Any = Any()
    val DECODER: Any = Any()
    fun fileFor(ctx: Context, spec: Any): File? = null
}
