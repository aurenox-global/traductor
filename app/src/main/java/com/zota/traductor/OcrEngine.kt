package com.zota.traductor

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.nio.FloatBuffer

/**
 * Motor OCR offline basado en PP-OCR (ONNX) y el `onnxruntime-android` que ya
 * trae el proyecto (el mismo runtime del VAD Silero).
 *
 * Pipeline completo, todo en local:
 *   Bitmap → (EXIF/downscale ya aplicados al cargarla) → entrada det
 *   → inferencia DB → cajas (umbral + unclip) → recorte con perspectiva
 *   → entrada rec (alto 48) → inferencia CRNN → decodificación CTC con diccionario.
 *
 * Es reentrante-único: se instancia una vez y se reutiliza (las sesiones ONNX son
 * costosas de crear). Usar siempre desde un hilo de IO.
 */
class OcrEngine(private val ctx: android.content.Context) {

    companion object {
        private const val TAG = "OcrEngine"
        /** Máximo de cajas a reconocer por imagen (protege ante una detección ruidosa). */
        private const val MAX_BOXES = 200
    }

    data class Line(val text: String, val score: Float, val box: OcrBox)

    data class Result(
        val text: String,
        val lines: List<Line>,
        val detMillis: Long,
        val recMillis: Long
    )

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var det: OrtSession? = null
    private var rec: OrtSession? = null
    private var detInput = "x"
    private var recInput = "x"
    private var dict: List<String> = emptyList()

    /** Carga las sesiones ONNX y el diccionario. Devuelve false si falta algo. */
    fun load(): Boolean {
        if (det != null && rec != null && dict.isNotEmpty()) return true
        if (!OcrModels.isReady(ctx)) {
            Log.w(TAG, "faltan modelos OCR")
            return false
        }
        return try {
            val opts = OrtSession.SessionOptions()
            det = env.createSession(OcrModels.fileFor(ctx, OcrModels.DET_V6).absolutePath, opts)
            rec = env.createSession(OcrModels.fileFor(ctx, OcrModels.REC_V6).absolutePath, opts)
            detInput = det!!.inputNames.first()
            recInput = rec!!.inputNames.first()
            dict = OcrDict.load(OcrModels.dictFile(ctx))
            dict.size > 100
        } catch (t: Throwable) {
            Log.e(TAG, "no se pudo cargar el motor OCR: ${t.message}")
            close()
            false
        }
    }

    fun isLoaded(): Boolean = det != null && rec != null && dict.isNotEmpty()

    /**
     * Reconoce el texto de una imagen ya orientada y con tamaño razonable.
     * @param onStatus aviso de progreso (se llama desde el hilo de trabajo)
     */
    fun recognize(bitmap: Bitmap, onStatus: (String) -> Unit = {}): Result {
        if (!isLoaded() && !load()) throw IllegalStateException("Modelos OCR no disponibles")

        val img = toOcrImage(bitmap)

        // ---- detección ----
        val plan = OcrPreprocess.detPlan(img.width, img.height)
        onStatus("Analizando imagen (${plan.dstW}×${plan.dstH})…")
        val detImg = OcrPreprocess.resizeBilinear(img, plan.dstW, plan.dstH)
        val detIn = OcrPreprocess.toDetInput(detImg)

        val t0 = System.currentTimeMillis()
        val out = runDet(detIn, plan.dstW, plan.dstH)
        val boxes = DbPostProcess.extractBoxes(out.prob, out.w, out.h)
        val detMs = System.currentTimeMillis() - t0
        onStatus("Detectadas ${boxes.size} regiones de texto")
        if (boxes.isEmpty()) return Result("", emptyList(), detMs, 0)

        // Escala del mapa del detector a la imagen real (el detector reescala).
        val sx = img.width.toFloat() / out.w
        val sy = img.height.toFloat() / out.h

        // ---- reconocimiento ----
        val t1 = System.currentTimeMillis()
        val lines = ArrayList<Line>()
        var done = 0
        for (box in DbPostProcess.sortReadingOrder(boxes).take(MAX_BOXES)) {
            val scaled = OcrBox(
                cx = box.cx * sx,
                cy = box.cy * sy,
                w = box.w * sx,
                h = box.h * sy,
                angle = box.angle,
                score = box.score
            )
            val pts = clampBox(scaled.orderedPoints(), img.width, img.height)
            val crop = OcrPreprocess.cropPerspective(img, pts)
            if (crop.width < 4 || crop.height < 4) continue

            val recPlan = OcrPreprocess.recPlan(crop.width, crop.height)
            val recImg = OcrPreprocess.resizeBilinear(crop, recPlan.dstW, recPlan.dstH)
            val recIn = OcrPreprocess.toRecInput(recImg)
            val (text, score) = runRec(recIn, recPlan.dstW, recPlan.dstH)
            val clean = text.trim()
            if (clean.isNotEmpty()) lines.add(Line(clean, score, scaled))
            done++
            if (done % 8 == 0) onStatus("Reconociendo texto… $done/${boxes.size}")
        }
        val recMs = System.currentTimeMillis() - t1

        val text = lines.joinToString("\n") { it.text }
        return Result(text, lines, detMs, recMs)
    }

    // ---------------- inferencia ----------------

    private class DetOut(val prob: FloatArray, val w: Int, val h: Int)

    private fun runDet(input: FloatArray, w: Int, h: Int): DetOut {
        val session = det ?: error("det sin cargar")
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, h.toLong(), w.toLong()))
        try {
            session.run(mapOf(detInput to tensor)).use { res ->
                val shape = (res[0] as OnnxTensor).info.shape
                // salida DB: [1,1,H,W]; las dimensiones reales mandan (no asumir el tamaño de entrada)
                val oh = shape[shape.size - 2].toInt()
                val ow = shape[shape.size - 1].toInt()
                return DetOut(flatten(res[0].value, ow * oh), ow, oh)
            }
        } finally {
            tensor.close()
        }
    }

    private fun runRec(input: FloatArray, w: Int, h: Int): Pair<String, Float> {
        val session = rec ?: error("rec sin cargar")
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, h.toLong(), w.toLong()))
        try {
            session.run(mapOf(recInput to tensor)).use { res ->
                val flat = flatten(res[0].value, 0)
                val shape = (res[0] as OnnxTensor).info.shape
                val classes = shape[shape.size - 1].toInt()
                val steps = (flat.size / classes).toInt()
                return CtcDecoder.decodeWithScore(flat, steps, classes, dict)
            }
        } finally {
            tensor.close()
        }
    }

    /** Aplana recursivamente la salida de ONNX (Array<*> anidados + FloatArray). */
    private fun flatten(value: Any?, expected: Int): FloatArray {
        val sink = FloatSink(if (expected > 0) expected else 8192)
        sink.add(value)
        return sink.toArray()
    }

    private class FloatSink(initial: Int) {
        private var buf = FloatArray(initial.coerceAtLeast(16))
        private var size = 0
        fun add(v: Any?) {
            when (v) {
                is FloatArray -> for (f in v) append(f)
                is Array<*> -> for (e in v) add(e)
                is Float -> append(v)
                is Double -> append(v.toFloat())
                else -> {}
            }
        }
        private fun append(f: Float) {
            if (size == buf.size) buf = buf.copyOf(buf.size * 2)
            buf[size++] = f
        }
        fun toArray(): FloatArray = if (size == buf.size) buf else buf.copyOf(size)
    }

    // ---------------- utilidades ----------------

    private fun toOcrImage(bitmap: Bitmap): OcrImage {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        return OcrImage(w, h, px)
    }

    private fun clampBox(pts: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(8)
        for (i in 0 until 8) {
            out[i] = if (i % 2 == 0) pts[i].coerceIn(0f, (w - 1).toFloat())
            else pts[i].coerceIn(0f, (h - 1).toFloat())
        }
        return out
    }

    fun close() {
        runCatching { det?.close() }
        runCatching { rec?.close() }
        det = null
        rec = null
        dict = emptyList()
    }
}
