package com.zota.traductor

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Modelos ONNX de la variante NLLB (Xenova/nllb-200-distilled-600M, int8).
 *
 * No se empaquetan en la APK (~900 MB): se descargan al primer uso, igual que el
 * resto de modelos de la app (Whisper/Piper), **o se importan** desde el
 * almacenamiento con la función "Importar modelos NLLB" de Ajustes (ver
 * [NllbImport]). El **tokenizador** viaja dentro de la APK como asset compacto
 * (`assets/nllb/tokenizer.bin`, ~9 MB) pero también puede importarse.
 *
 * Todos los ficheros viven en una **carpeta propia** (`filesDir/nllb_models/`)
 * para no confundirlos con el resto de modelos (que usan la raíz de `filesDir`) y
 * para que la variante FULL pueda empaquetarlos bajo `bundled/files/nllb_models/…`.
 */
object NllbModels {

    private const val BASE = "https://huggingface.co/Xenova/nllb-200-distilled-600M/resolve/main"

    /** Subcarpeta de los modelos NLLB dentro de `filesDir`. */
    const val DIR = "nllb_models"

    /** Nombre del tokenizador importado (mismo fichero que el asset del APK). */
    const val TOKENIZER_NAME = "tokenizer.bin"

    val ENCODER = ModelManager.ModelSpec(
        id = "nllb_encoder",
        fileName = "nllb_encoder_model_quantized.onnx",
        url = "$BASE/onnx/encoder_model_quantized.onnx",
        approxBytes = 419_120_483L,
        label = "NLLB-200 encoder · int8 (400 MB)"
    )

    val DECODER = ModelManager.ModelSpec(
        id = "nllb_decoder",
        fileName = "nllb_decoder_model_merged_quantized.onnx",
        url = "$BASE/onnx/decoder_model_merged_quantized.onnx",
        approxBytes = 475_505_771L,
        label = "NLLB-200 decoder · int8 (454 MB)"
    )

    val ALL: List<ModelManager.ModelSpec> = listOf(ENCODER, DECODER)

    // ---------------- Rutas ----------------

    /** Carpeta de modelos NLLB (`filesDir/nllb_models`), creada si no existe. */
    fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { if (!exists()) mkdirs() }

    fun fileFor(ctx: Context, spec: ModelManager.ModelSpec): File =
        File(ctx.filesDir, "$DIR/${spec.fileName}")

    /** Tokenizador importado por el usuario (`filesDir/nllb_models/tokenizer.bin`). */
    fun tokenizerFile(ctx: Context): File = File(ctx.filesDir, "$DIR/$TOKENIZER_NAME")

    /** ¿Hay un tokenizador importado utilizable? (si no, se usa el asset del APK). */
    fun tokenizerImported(ctx: Context): Boolean {
        val f = tokenizerFile(ctx)
        return f.isFile && f.length() > 1024
    }

    // ---------------- Estado ----------------

    fun isPresent(ctx: Context, spec: ModelManager.ModelSpec): Boolean {
        val f = fileFor(ctx, spec)
        return f.isFile && f.length() > 1024
    }

    /** ¿Están los dos ONNX descargados/importados y con tamaño plausible? */
    fun isReady(ctx: Context): Boolean = ALL.all { isPresent(ctx, it) }

    // ---------------- Descarga ----------------

    /** Descarga (si falta) un ONNX concreto a la carpeta NLLB. */
    fun downloadOne(
        ctx: Context,
        spec: ModelManager.ModelSpec,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): File {
        val dest = fileFor(ctx, spec)
        if (isPresent(ctx, spec)) return dest
        return ModelManager.downloadTo(spec.url, dest, onProgress)
    }

    /** Descarga (si hace falta) encoder + decoder, informando progreso global. */
    fun download(ctx: Context, onProgress: (Long, Long, String) -> Unit): Boolean {
        if (isReady(ctx)) return true
        val total = ALL.sumOf { it.approxBytes }
        var base = 0L
        for (spec in ALL) {
            if (isPresent(ctx, spec)) {
                base += spec.approxBytes
                continue
            }
            val b = base
            downloadOne(ctx, spec) { done, tot ->
                val t = if (tot > 0) tot else spec.approxBytes
                onProgress(b + done, total, spec.label)
            }
            base += spec.approxBytes
        }
        return isReady(ctx)
    }

    // ---------------- Importación ----------------

    /**
     * Copia los ficheros importados a la carpeta NLLB con los nombres que espera
     * el motor. `mappings` asocia cada origen (Uri elegida por el usuario) con su
     * rol (ver [NllbImport]).
     */
    fun importAll(
        ctx: Context,
        mappings: List<NllbImport.Match>,
        uriOf: (String) -> Uri,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): List<File> {
        val out = ArrayList<File>(mappings.size)
        for (m in mappings) {
            val dest = when (m.role) {
                NllbImport.Role.ENCODER -> ENCODER.fileName
                NllbImport.Role.DECODER -> DECODER.fileName
                NllbImport.Role.TOKENIZER -> TOKENIZER_NAME
            }
            out += ModelManager.importFromUri(ctx, uriOf(m.source), dest, DIR, onProgress)
        }
        return out
    }

    fun human(bytes: Long): String = ModelManager.human(bytes)
}
