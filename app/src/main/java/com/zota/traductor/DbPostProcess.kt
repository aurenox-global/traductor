package com.zota.traductor

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Post-proceso del detector DB (Differentiable Binarization) de PP-OCR.
 *
 * Replica el `DBPostProcess` oficial sobre el mapa de probabilidad:
 *  1. binariza con `thresh` (0,2);
 *  2. agrupa píxeles conectados (componentes 4-conectados = candidatos de línea);
 *  3. calcula el rectángulo de área mínima de cada componente y su puntuación
 *     (probabilidad media), descartando los de score < `boxThresh` (0,4);
 *  4. expande la caja con `unclipRatio` (1,4) para recuperar los bordes del texto.
 *
 * Kotlin puro (sin Android) para poder verificar la extracción de cajas con
 * vectores sintéticos en tests de host.
 */
object DbPostProcess {

    const val DEFAULT_THRESH = 0.2f
    const val DEFAULT_BOX_THRESH = 0.4f
    const val DEFAULT_UNCLIP_RATIO = 1.4f
    const val DEFAULT_MIN_SIZE = 3f

    /** Máximo de píxeles por componente que se analizan con min-area-rect. */
    private const val MAX_POINTS = 30_000

    /**
     * Extrae las cajas de texto del mapa de probabilidad (row-major, `w`×`h`).
     * Las coordenadas quedan en el sistema del propio mapa.
     */
    fun extractBoxes(
        prob: FloatArray,
        w: Int,
        h: Int,
        thresh: Float = DEFAULT_THRESH,
        boxThresh: Float = DEFAULT_BOX_THRESH,
        unclipRatio: Float = DEFAULT_UNCLIP_RATIO,
        minSize: Float = DEFAULT_MIN_SIZE
    ): List<OcrBox> {
        require(prob.size >= w * h) { "mapa de probabilidad de tamaño incorrecto" }
        val visited = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var xs = IntArray(1024)
        var ys = IntArray(1024)
        val boxes = ArrayList<OcrBox>()

        for (start in 0 until w * h) {
            if (visited[start] || prob[start] < thresh) continue

            var sp = 0
            stack[sp++] = start
            visited[start] = true
            var count = 0
            var sum = 0f
            var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE
            var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE
            var truncated = false

            while (sp > 0) {
                val idx = stack[--sp]
                val x = idx % w
                val y = idx / w
                sum += prob[idx]
                count++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y

                if (!truncated) {
                    if (count > xs.size) {
                        if (xs.size >= MAX_POINTS) {
                            truncated = true
                        } else {
                            xs = xs.copyOf(minOf(MAX_POINTS, xs.size * 2))
                            ys = ys.copyOf(minOf(MAX_POINTS, ys.size * 2))
                        }
                    }
                    if (!truncated) { xs[count - 1] = x; ys[count - 1] = y }
                }

                if (x > 0 && !visited[idx - 1] && prob[idx - 1] >= thresh) { visited[idx - 1] = true; stack[sp++] = idx - 1 }
                if (x < w - 1 && !visited[idx + 1] && prob[idx + 1] >= thresh) { visited[idx + 1] = true; stack[sp++] = idx + 1 }
                if (y > 0 && !visited[idx - w] && prob[idx - w] >= thresh) { visited[idx - w] = true; stack[sp++] = idx - w }
                if (y < h - 1 && !visited[idx + w] && prob[idx + w] >= thresh) { visited[idx + w] = true; stack[sp++] = idx + w }
            }

            if (count < 3) continue
            val score = sum / count
            if (score < boxThresh) continue

            val rect = if (truncated) {
                // Componente enorme: el bounding box es una aproximación estable y barata.
                val cx = (minX + maxX + 1) / 2f
                val cy = (minY + maxY + 1) / 2f
                floatArrayOf(cx, cy, (maxX - minX + 1).toFloat(), (maxY - minY + 1).toFloat(), 0f)
            } else {
                minAreaRect(xs, ys, count)
            }

            val box = OcrBox(rect[0], rect[1], rect[2], rect[3], rect[4], score)
            if (min(box.w, box.h) < minSize) continue
            boxes.add(box.unclip(unclipRatio))
        }
        return boxes
    }

    /**
     * Rectángulo de área mínima que contiene los puntos, al estilo `cv2.minAreaRect`.
     * Devuelve `[cx, cy, ancho, alto, ángulo]`; el ángulo está en radianes.
     */
    fun minAreaRect(xs: IntArray, ys: IntArray, n: Int): FloatArray {
        if (n <= 0) return floatArrayOf(0f, 0f, 0f, 0f, 0f)

        val pts = ArrayList<DoubleArray>(n)
        for (i in 0 until n) pts.add(doubleArrayOf(xs[i].toDouble(), ys[i].toDouble()))
        pts.sortWith(compareBy({ it[0] }, { it[1] }))

        val hull = convexHull(pts)
        val size = hull.size
        if (size == 1) {
            return floatArrayOf(hull[0][0].toFloat(), hull[0][1].toFloat(), 0f, 0f, 0f)
        }
        if (size == 2) {
            val dx = hull[1][0] - hull[0][0]
            val dy = hull[1][1] - hull[0][1]
            return floatArrayOf(
                ((hull[0][0] + hull[1][0]) / 2).toFloat(),
                ((hull[0][1] + hull[1][1]) / 2).toFloat(),
                hypot(dx, dy).toFloat(), 0f, atan2(dy, dx).toFloat()
            )
        }

        var bestArea = Double.MAX_VALUE
        var bcx = 0.0; var bcy = 0.0; var bw = 0.0; var bh = 0.0; var bang = 0.0

        for (i in 0 until size) {
            val j = (i + 1) % size
            val ex = hull[j][0] - hull[i][0]
            val ey = hull[j][1] - hull[i][1]
            val len = hypot(ex, ey)
            if (len < 1e-9) continue
            val ux = ex / len
            val uy = ey / len

            var minU = Double.MAX_VALUE; var maxU = -Double.MAX_VALUE
            var minV = Double.MAX_VALUE; var maxV = -Double.MAX_VALUE
            for (k in 0 until size) {
                val dx = hull[k][0] - hull[i][0]
                val dy = hull[k][1] - hull[i][1]
                val u = dx * ux + dy * uy
                val v = -dx * uy + dy * ux
                if (u < minU) minU = u
                if (u > maxU) maxU = u
                if (v < minV) minV = v
                if (v > maxV) maxV = v
            }
            val rw = maxU - minU
            val rh = maxV - minV
            val area = rw * rh
            if (area < bestArea) {
                bestArea = area
                val cu = (minU + maxU) / 2
                val cv = (minV + maxV) / 2
                bcx = hull[i][0] + cu * ux - cv * uy
                bcy = hull[i][1] + cu * uy + cv * ux
                bw = rw
                bh = rh
                bang = atan2(uy, ux)
            }
        }
        return floatArrayOf(bcx.toFloat(), bcy.toFloat(), bw.toFloat(), bh.toFloat(), bang.toFloat())
    }

    /** Cierre convexo (monotone chain). */
    private fun convexHull(sorted: List<DoubleArray>): List<DoubleArray> {
        val n = sorted.size
        if (n <= 2) return sorted
        val lower = ArrayList<DoubleArray>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) lower.removeAt(lower.size - 1)
            lower.add(p)
        }
        val upper = ArrayList<DoubleArray>()
        for (i in n - 1 downTo 0) {
            val p = sorted[i]
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) upper.removeAt(upper.size - 1)
            upper.add(p)
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        lower.addAll(upper)
        return lower
    }

    private fun cross(o: DoubleArray, a: DoubleArray, b: DoubleArray): Double =
        (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    /** Ordena las cajas por línea (arriba→abajo) y, dentro de la línea, izquierda→derecha. */
    fun sortReadingOrder(boxes: List<OcrBox>): List<OcrBox> {
        val byTop = boxes.sortedBy { it.cy }
        val out = ArrayList<OcrBox>(byTop.size)
        var i = 0
        while (i < byTop.size) {
            val lineTop = byTop[i].cy
            val lineH = max(1f, byTop[i].h)
            val line = ArrayList<OcrBox>()
            var j = i
            while (j < byTop.size && abs(byTop[j].cy - lineTop) <= lineH * 0.6f) {
                line.add(byTop[j]); j++
            }
            line.sortBy { it.cx }
            out.addAll(line)
            i = j
        }
        return out
    }
}
