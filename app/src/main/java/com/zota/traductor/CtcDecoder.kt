package com.zota.traductor

/**
 * Decodificación CTC voraz para el reconocedor PP-OCR.
 *
 * Convención de índices de PaddleOCR:
 *  - clase 0 = "blank" (se ignora);
 *  - clases 1..dict.size = caracteres del diccionario;
 *  - clase dict.size+1 = espacio (si el modelo añade el espacio como clase extra).
 *
 * Regla voraz: se toma el índice más probable por paso de tiempo, se colapsan
 * repeticiones consecutivas y se eliminan los blanks.
 *
 * Kotlin puro (sin Android) para poder verificar la decodificación con vectores
 * de logits sintéticos en tests de host.
 */
object CtcDecoder {

    /**
     * @param logits  matriz aplanada `steps × classes` en row-major
     * @param steps   número de pasos de tiempo (T)
     * @param classes número de clases (C)
     * @param dict    diccionario de caracteres (sin el blank)
     */
    fun decode(logits: FloatArray, steps: Int, classes: Int, dict: List<String>): String {
        require(logits.size >= steps * classes) { "logits de tamaño incorrecto" }
        val sb = StringBuilder(steps)
        var prev = -1
        for (t in 0 until steps) {
            val base = t * classes
            var bestIdx = 0
            var bestVal = Float.NEGATIVE_INFINITY
            for (c in 0 until classes) {
                val v = logits[base + c]
                if (v > bestVal) {
                    bestVal = v
                    bestIdx = c
                }
            }
            if (bestIdx != 0 && bestIdx != prev) {
                val di = bestIdx - 1
                when {
                    di < dict.size -> sb.append(dict[di])
                    di == dict.size -> sb.append(' ')
                }
            }
            prev = bestIdx
        }
        return sb.toString()
    }

    /** Igual que [decode] pero además devuelve la confianza media de los caracteres emitidos. */
    fun decodeWithScore(logits: FloatArray, steps: Int, classes: Int, dict: List<String>): Pair<String, Float> {
        val sb = StringBuilder(steps)
        var prev = -1
        var sum = 0f
        var emitted = 0
        for (t in 0 until steps) {
            val base = t * classes
            var bestIdx = 0
            var bestVal = Float.NEGATIVE_INFINITY
            var second = Float.NEGATIVE_INFINITY
            for (c in 0 until classes) {
                val v = logits[base + c]
                if (v > bestVal) {
                    second = bestVal
                    bestVal = v
                    bestIdx = c
                } else if (v > second) {
                    second = v
                }
            }
            if (bestIdx != 0 && bestIdx != prev) {
                val di = bestIdx - 1
                when {
                    di < dict.size -> sb.append(dict[di])
                    di == dict.size -> sb.append(' ')
                }
                // confianza blanda: separación entre mejor y segundo (sigmoid-like)
                sum += (1f / (1f + kotlin.math.exp(-(bestVal - second))))
                emitted++
            }
            prev = bestIdx
        }
        return sb.toString() to if (emitted == 0) 0f else sum / emitted
    }
}
