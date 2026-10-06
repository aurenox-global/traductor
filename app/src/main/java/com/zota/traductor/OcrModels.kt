package com.zota.traductor

import android.content.Context
import java.io.File

/**
 * Catálogo y gestión de los modelos OCR (PP-OCR en ONNX).
 *
 * Se reutiliza la infraestructura ya existente: `onnxruntime-android` (el mismo
 * `libonnxruntime.so` que usa el VAD) y las descargas de `ModelManager` (reanudables
 * por redirección, con `.part` y progreso).
 *
 * Motor por defecto: **PP-OCRv6 tiny** (oficial PaddlePaddle, Apache-2.0), que cubre
 * latino + chino + números y pesa ~6 MB entre det+rec. Los modelos PP-OCRv4 de
 * RapidOCR quedan preparados como alternativa (URLs verificadas) para más scripts.
 *
 * Los ficheros se guardan en `filesDir`, igual que el resto de modelos.
 */
object OcrModels {

    data class Spec(
        val id: String,
        val fileName: String,
        val url: String,
        val approxBytes: Long,
        val label: String
    )

    // ---------------- PP-OCRv6 tiny (por defecto) ----------------

    val DET_V6 = Spec(
        id = "ocr_det_v6",
        fileName = "ppocr_v6_det.onnx",
        url = "https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_det_onnx/resolve/main/inference.onnx",
        approxBytes = 1_780_590L,
        label = "PP-OCRv6 tiny · detección (1,7 MB)"
    )

    val REC_V6 = Spec(
        id = "ocr_rec_v6",
        fileName = "ppocr_v6_rec.onnx",
        url = "https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_rec_onnx/resolve/main/inference.onnx",
        approxBytes = 4_462_639L,
        label = "PP-OCRv6 tiny · reconocimiento (4,3 MB)"
    )

    val REC_V6_YML = Spec(
        id = "ocr_rec_v6_yml",
        fileName = "ppocr_v6_rec.yml",
        url = "https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_rec_onnx/resolve/main/inference.yml",
        approxBytes = 55_571L,
        label = "PP-OCRv6 tiny · diccionario (55 KB)"
    )

    // ---------------- PP-OCRv4 / RapidOCR (alternativa preparada) ----------------
    // Verificadas HTTP 200; no se activan por defecto (requieren cambiar REC/DET y el dict).

    val DET_V4 = Spec(
        id = "ocr_det_v4",
        fileName = "ch_PP-OCRv4_det_infer.onnx",
        url = "https://huggingface.co/SWHL/RapidOCR/resolve/main/PP-OCRv4/ch_PP-OCRv4_det_infer.onnx",
        approxBytes = 4_745_517L,
        label = "PP-OCRv4 · detección (4,5 MB)"
    )

    val REC_V4 = Spec(
        id = "ocr_rec_v4",
        fileName = "ch_PP-OCRv4_rec_infer.onnx",
        url = "https://huggingface.co/SWHL/RapidOCR/resolve/main/PP-OCRv4/ch_PP-OCRv4_rec_infer.onnx",
        approxBytes = 10_857_958L,
        label = "PP-OCRv4 · reconocimiento (10,4 MB)"
    )

    val KEYS_V4 = Spec(
        id = "ocr_keys_v4",
        fileName = "ppocr_keys_v1.txt",
        url = "https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/main/ppocr/utils/ppocr_keys_v1.txt",
        approxBytes = 26_250L,
        label = "PP-OCRv4 · diccionario (26 KB)"
    )

    /** Descargas del motor por defecto (bajo demanda, la primera vez que se usa el OCR). */
    val DOWNLOADS: List<Spec> = listOf(DET_V6, REC_V6, REC_V6_YML)

    /** Nombre del diccionario ya parseado y listo para el decodificador. */
    private const val DICT_NAME = "ppocr_v6_dict.txt"

    // ---------------- Rutas ----------------

    fun fileFor(ctx: Context, spec: Spec): File = File(ctx.filesDir, spec.fileName)

    fun dictFile(ctx: Context): File = File(ctx.filesDir, DICT_NAME)

    fun isPresent(ctx: Context, spec: Spec): Boolean {
        val f = fileFor(ctx, spec)
        return f.isFile && f.length() > 512
    }

    /** ¿Están descargados los modelos y generado el diccionario? */
    fun isReady(ctx: Context): Boolean =
        isPresent(ctx, DET_V6) && isPresent(ctx, REC_V6) && OcrDict.load(dictFile(ctx)).size > 100

    fun human(bytes: Long): String = ModelManager.human(bytes)

    /**
     * Descarga (si hace falta) los modelos OCR y genera el diccionario a partir del
     * `inference.yml` oficial. Devuelve true si todo quedó listo.
     */
    fun ensureReady(
        ctx: Context,
        onStatus: (String) -> Unit = {},
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Boolean {
        for (spec in DOWNLOADS) {
            if (isPresent(ctx, spec)) continue
            onStatus("Descargando ${spec.label} …")
            ModelManager.downloadTo(spec.url, fileFor(ctx, spec), onProgress)
        }
        if (OcrDict.load(dictFile(ctx)).size <= 100) {
            onStatus("Preparando diccionario PP-OCR…")
            val yml = fileFor(ctx, REC_V6_YML)
            if (!yml.isFile) return false
            val chars = OcrDict.parseCharacterDict(yml.readText(Charsets.UTF_8))
            if (chars.size <= 100) return false
            OcrDict.write(dictFile(ctx), chars)
        }
        return isReady(ctx)
    }

    /** Borra los ficheros OCR (para liberar espacio). */
    fun deleteAll(ctx: Context) {
        for (spec in DOWNLOADS) runCatching { fileFor(ctx, spec).delete() }
        runCatching { dictFile(ctx).delete() }
    }
}
