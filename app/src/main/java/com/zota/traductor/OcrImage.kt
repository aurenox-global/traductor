package com.zota.traductor

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Imagen ARGB de memoria, sin dependencias de Android.
 *
 * Existe para poder ejecutar y testear TODO el preproceso OCR (resize/letterbox,
 * normalización y recorte en perspectiva) en la JVM del host, sin dispositivo.
 * `pixels` usa el mismo formato que `Bitmap.getPixels` (ARGB_8888, fila por fila).
 */
class OcrImage(val width: Int, val height: Int, val pixels: IntArray) {

    init {
        require(width > 0 && height > 0) { "dimensiones inválidas ${width}x$height" }
        require(pixels.size >= width * height) { "buffer demasiado pequeño" }
    }

    fun argb(x: Int, y: Int): Int {
        val cx = x.coerceIn(0, width - 1)
        val cy = y.coerceIn(0, height - 1)
        return pixels[cy * width + cx]
    }

    /** Muestreo bilineal en coordenadas continuas (para el recorte en perspectiva). */
    fun sampleBilinear(fx: Float, fy: Float): Int {
        val x = fx.coerceIn(0f, (width - 1).toFloat())
        val y = fy.coerceIn(0f, (height - 1).toFloat())
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val tx = x - x0
        val ty = y - y0

        val c00 = argb(x0, y0); val c10 = argb(x1, y0)
        val c01 = argb(x0, y1); val c11 = argb(x1, y1)

        val a = bilinear((c00 ushr 24) and 0xFF, (c10 ushr 24) and 0xFF, (c01 ushr 24) and 0xFF, (c11 ushr 24) and 0xFF, tx, ty)
        val r = bilinear((c00 ushr 16) and 0xFF, (c10 ushr 16) and 0xFF, (c01 ushr 16) and 0xFF, (c11 ushr 16) and 0xFF, tx, ty)
        val g = bilinear((c00 ushr 8) and 0xFF, (c10 ushr 8) and 0xFF, (c01 ushr 8) and 0xFF, (c11 ushr 8) and 0xFF, tx, ty)
        val b = bilinear(c00 and 0xFF, c10 and 0xFF, c01 and 0xFF, c11 and 0xFF, tx, ty)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    companion object {
        private fun bilinear(v00: Int, v10: Int, v01: Int, v11: Int, tx: Float, ty: Float): Int {
            val top = v00 + (v10 - v00) * tx
            val bot = v01 + (v11 - v01) * tx
            return (top + (bot - top) * ty).roundToInt().coerceIn(0, 255)
        }
    }
}

/**
 * Caja de texto devuelta por el detector DB.
 * `angle` en radianes; `score` = probabilidad media de la región.
 */
data class OcrBox(
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val angle: Float,
    val score: Float
) {

    /** Las 4 esquinas (x0,y0,x1,y1,…) girando la caja alrededor de su centro. */
    fun points(): FloatArray {
        val c = cos(angle)
        val s = sin(angle)
        val hw = w / 2f
        val hh = h / 2f
        val dx = floatArrayOf(-hw, hw, hw, -hw)
        val dy = floatArrayOf(-hh, -hh, hh, hh)
        val out = FloatArray(8)
        for (i in 0 until 4) {
            out[i * 2] = cx + c * dx[i] - s * dy[i]
            out[i * 2 + 1] = cy + s * dx[i] + c * dy[i]
        }
        return out
    }

    /** Esquinas ordenadas [tl,tr,br,bl] (para el recorte en perspectiva). */
    fun orderedPoints(): FloatArray {
        val p = points()
        val pts = Array(4) { floatArrayOf(p[it * 2], p[it * 2 + 1]) }
        val tl = pts.minBy { it[0] + it[1] }
        val br = pts.maxBy { it[0] + it[1] }
        val tr = pts.maxBy { it[0] - it[1] }
        val bl = pts.minBy { it[0] - it[1] }
        return floatArrayOf(tl[0], tl[1], tr[0], tr[1], br[0], br[1], bl[0], bl[1])
    }

    /** Expansión del contorno (estilo `unclip` de DB): agranda w/h en 2·d. */
    fun unclip(ratio: Float): OcrBox {
        val area = w * h
        val per = 2f * (w + h)
        if (per <= 0f) return this
        val d = area * ratio / per
        return copy(w = w + 2f * d, h = h + 2f * d)
    }

    fun top(): Float = cy - h / 2f
}

/**
 * Preproceso del pipeline PP-OCR (detector DB + reconocedor CRNN).
 *
 * Replica EXACTAMENTE los parámetros verificados del `inference.yml` oficial:
 *  - det : resize (lado mayor ≤ 960, redondeado a múltiplos de 32), BGR, /255,
 *          normalización mean=[0.485,0.456,0.406] std=[0.229,0.224,0.225].
 *  - rec : alto fijo 48 (ancho proporcional), BGR, /255, (x-0.5)/0.5.
 */
object OcrPreprocess {

    val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    const val DET_LIMIT = 960
    const val DET_MULTIPLE = 32
    const val REC_HEIGHT = 48

    data class Plan(val dstW: Int, val dstH: Int)

    /**
     * Tamaño de entrada del detector: si el lado mayor supera `limit` se escala para
     * que mida `limit`; en cualquier caso se redondea a múltiplos de `multiple`
     * (mínimo `multiple`), igual que `DetResizeForTest` de PaddleOCR.
     */
    fun detPlan(srcW: Int, srcH: Int, limit: Int = DET_LIMIT, multiple: Int = DET_MULTIPLE): Plan {
        val longest = max(srcW, srcH)
        val ratio = if (longest > limit) limit.toFloat() / longest else 1f
        // Math.rint (redondeo "bancario") replica el round() de PaddleOCR.
        val dw = max(multiple, (multiple * Math.rint((srcW * ratio / multiple).toDouble())).toInt())
        val dh = max(multiple, (multiple * Math.rint((srcH * ratio / multiple).toDouble())).toInt())
        return Plan(dw, dh)
    }

    /** Tamaño de entrada del reconocedor: alto fijo, ancho proporcional (mínimo 1). */
    fun recPlan(srcW: Int, srcH: Int, height: Int = REC_HEIGHT): Plan {
        val dw = max(1, (srcW.toFloat() * height / srcH).roundToInt())
        return Plan(dw, height)
    }

    /** Redimensionado bilineal (letterbox no hace falta: PP-OCR estira la imagen). */
    fun resizeBilinear(src: OcrImage, dstW: Int, dstH: Int): OcrImage {
        if (dstW == src.width && dstH == src.height) return src
        val out = IntArray(dstW * dstH)
        val sx = src.width.toFloat() / dstW
        val sy = src.height.toFloat() / dstH
        for (y in 0 until dstH) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceAtLeast(0f)
            val y0 = fy.toInt().coerceIn(0, src.height - 1)
            val y1 = min(y0 + 1, src.height - 1)
            val ty = fy - y0
            for (x in 0 until dstW) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceAtLeast(0f)
                val x0 = fx.toInt().coerceIn(0, src.width - 1)
                val x1 = min(x0 + 1, src.width - 1)
                val tx = fx - x0
                val c00 = src.pixels[y0 * src.width + x0]
                val c10 = src.pixels[y0 * src.width + x1]
                val c01 = src.pixels[y1 * src.width + x0]
                val c11 = src.pixels[y1 * src.width + x1]
                val r = blend((c00 ushr 16) and 0xFF, (c10 ushr 16) and 0xFF, (c01 ushr 16) and 0xFF, (c11 ushr 16) and 0xFF, tx, ty)
                val g = blend((c00 ushr 8) and 0xFF, (c10 ushr 8) and 0xFF, (c01 ushr 8) and 0xFF, (c11 ushr 8) and 0xFF, tx, ty)
                val b = blend(c00 and 0xFF, c10 and 0xFF, c01 and 0xFF, c11 and 0xFF, tx, ty)
                out[y * dstW + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return OcrImage(dstW, dstH, out)
    }

    private fun blend(v00: Int, v10: Int, v01: Int, v11: Int, tx: Float, ty: Float): Int {
        val top = v00 + (v10 - v00) * tx
        val bot = v01 + (v11 - v01) * tx
        return (top + (bot - top) * ty).roundToInt().coerceIn(0, 255)
    }

    /** Convierte a tensor CHW en orden BGR normalizado (det). */
    fun toDetInput(img: OcrImage, mean: FloatArray = DET_MEAN, std: FloatArray = DET_STD, scale: Float = 1f / 255f): FloatArray =
        toChw(img, mean, std, scale)

    /** Convierte a tensor CHW en orden BGR normalizado (rec: mean=std=0.5). */
    fun toRecInput(img: OcrImage): FloatArray =
        toChw(img, floatArrayOf(0.5f, 0.5f, 0.5f), floatArrayOf(0.5f, 0.5f, 0.5f), 1f / 255f)

    private fun toChw(img: OcrImage, mean: FloatArray, std: FloatArray, scale: Float): FloatArray {
        val n = img.width * img.height
        val out = FloatArray(3 * n)
        val px = img.pixels
        var i = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val c = px[i]
                val b = (c and 0xFF) * scale
                val g = ((c ushr 8) and 0xFF) * scale
                val r = ((c ushr 16) and 0xFF) * scale
                out[i] = (b - mean[0]) / std[0]
                out[n + i] = (g - mean[1]) / std[1]
                out[2 * n + i] = (r - mean[2]) / std[2]
                i++
            }
        }
        return out
    }

    /**
     * Recorte de un cuadrilátero con corrección de perspectiva (texto inclinado en fotos).
     * Devuelve una imagen recta de tamaño `ancho × alto` estimado desde el cuadrilátero.
     */
    fun cropPerspective(src: OcrImage, ordered: FloatArray): OcrImage {
        val tl = floatArrayOf(ordered[0], ordered[1])
        val tr = floatArrayOf(ordered[2], ordered[3])
        val br = floatArrayOf(ordered[4], ordered[5])
        val bl = floatArrayOf(ordered[6], ordered[7])

        val wTop = dist(tl, tr)
        val wBot = dist(bl, br)
        val hLeft = dist(tl, bl)
        val hRight = dist(tr, br)
        val dstW = max(1, max(wTop, wBot).roundToInt())
        val dstH = max(1, max(hLeft, hRight).roundToInt())

        // H mapea coordenadas del rectángulo destino -> imagen origen (sin invertir matrices).
        val from = floatArrayOf(0f, 0f, (dstW - 1).toFloat(), 0f, (dstW - 1).toFloat(), (dstH - 1).toFloat(), 0f, (dstH - 1).toFloat())
        val to = floatArrayOf(tl[0], tl[1], tr[0], tr[1], br[0], br[1], bl[0], bl[1])
        val h = solveHomography(from, to)

        val out = IntArray(dstW * dstH)
        for (y in 0 until dstH) {
            for (x in 0 until dstW) {
                val fx = (h[0] * x + h[1] * y + h[2]).toFloat()
                val fy = (h[3] * x + h[4] * y + h[5]).toFloat()
                val fz = (h[6] * x + h[7] * y + h[8]).toFloat()
                val u = if (abs(fz) > 1e-9f) fx / fz else fx
                val v = if (abs(fz) > 1e-9f) fy / fz else fy
                out[y * dstW + x] = src.sampleBilinear(u, v)
            }
        }
        return OcrImage(dstW, dstH, out)
    }

    private fun dist(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /**
     * Homografía (3×3, h22=1) que lleva los 4 puntos `from` a los 4 puntos `to`.
     * Resuelve el sistema de 8 ecuaciones por eliminación gaussiana.
     */
    fun solveHomography(from: FloatArray, to: FloatArray): DoubleArray {
        // 8x9: [x y 1 0 0 0 -x'x -x'y | x']  y  [0 0 0 x y 1 -y'x -y'y | y']
        val m = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val x = from[i * 2].toDouble()
            val y = from[i * 2 + 1].toDouble()
            val xp = to[i * 2].toDouble()
            val yp = to[i * 2 + 1].toDouble()

            m[i * 2] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -x * xp, -y * xp, xp)
            m[i * 2 + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -x * yp, -y * yp, yp)
        }
        // eliminación gaussiana con pivote parcial
        for (col in 0 until 8) {
            var piv = col
            for (r in col + 1 until 8) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            val tmp = m[col]; m[col] = m[piv]; m[piv] = tmp
            val d = m[col][col]
            if (abs(d) < 1e-12) continue
            for (c in col until 9) m[col][c] /= d
            for (r in 0 until 8) {
                if (r == col) continue
                val f = m[r][col]
                if (f == 0.0) continue
                for (c in col until 9) m[r][c] -= f * m[col][c]
            }
        }
        val h = DoubleArray(9)
        for (i in 0 until 8) h[i] = m[i][8]
        h[8] = 1.0
        return h
    }
}
