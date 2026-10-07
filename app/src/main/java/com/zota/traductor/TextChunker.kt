package com.zota.traductor

/**
 * Divide un texto en trozos ("chunks") que quepan holgados en el contexto del
 * modelo.
 *
 * v0.9.3: el troceo ahora es **por LÍNEAS primero**. Cada línea no vacía es una
 * unidad; solo se AGRUPAN líneas cortas entre sí mientras el conjunto no supere
 * [LINE_GROUP_LIMIT] (~220 chars). Una línea más larga que el presupuesto se
 * parte por FRASES y, si una frase no cabe, por palabras. Esto es clave para
 * bloques "tipo prompt/instrucción" en MAYÚSCULAS y varias líneas: el 0.8B copia
 * el bloque entero, pero traduce bien línea a línea.
 *
 * Nunca corta a mitad de palabra (salvo el caso patológico de una única palabra
 * más larga que el presupuesto, que se parte por necesidad). La recomposición
 * ([recompose], que une con "\n") conserva el orden y los saltos de línea.
 *
 * Es Kotlin puro (sin Android) para poder testearse en el host.
 */
object TextChunker {

    /** Presupuesto por defecto por trozo, en caracteres. */
    const val DEFAULT_MAX_CHARS: Int = 1200

    /**
     * Tope para AGRUPAR líneas cortas distintas en un mismo trozo. Por encima de
     * esto, cada línea arranca un trozo nuevo (que es el modo que el modelo sí
     * traduce bien en bloques tipo instrucción).
     */
    const val LINE_GROUP_LIMIT: Int = 220

    /** Fin de frase: punto, exclamación, interrogación, puntos suspensivos (ASCII y CJK). */
    private val SENTENCE_SPLIT = Regex("(?<=[.!?…。！？])\\s+")

    private val WHITESPACE = Regex("\\s+")

    /** Un bloque indivisible junto al separador que debe precederle al recomponer. */
    private data class Block(val text: String, val sepBefore: String, val lineId: Int)

    /**
     * Parte [text] en trozos de longitud <= [maxChars].
     *
     * - Texto de UNA sola línea que ya cabe -> lista con el texto idéntico
     *   (comportamiento previo intacto).
     * - Texto multilínea -> una unidad por línea; solo se agrupan líneas cortas
     *   hasta [LINE_GROUP_LIMIT] chars; las líneas largas se parten por frases.
     * - Los trozos se recortan (sin espacios sobrantes en los bordes).
     * - El texto vacío devuelve lista vacía.
     */
    fun chunk(
        text: String,
        maxChars: Int = DEFAULT_MAX_CHARS,
        lineGroupLimit: Int = LINE_GROUP_LIMIT
    ): List<String> {
        require(maxChars >= 1) { "maxChars debe ser >= 1" }
        if (text.isEmpty()) return emptyList()
        // Atajo: una sola línea que cabe -> sin cambios (no rompe el caso corto).
        if (!text.contains('\n') && text.length <= maxChars) return listOf(text)

        val blocks = ArrayList<Block>()
        var lineId = 0
        for (rawLine in text.split("\n")) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val sep = if (blocks.isEmpty()) "" else "\n"
            appendUnits(blocks, line, sep, maxChars, lineId)
            lineId++
        }

        // Empaquetado: dentro de una misma línea se llega hasta maxChars (las
        // frases de una línea larga son del mismo bloque semántico). Entre
        // líneas distintas solo se agrupa mientras quepa en lineGroupLimit.
        val groupLimit = minOf(lineGroupLimit, maxChars)
        val chunks = ArrayList<String>()
        val sb = StringBuilder()
        var currentLine = -1
        for (block in blocks) {
            val piece = block.text.trim()
            if (piece.isEmpty()) continue
            if (sb.isEmpty()) {
                sb.append(piece)
                currentLine = block.lineId
                continue
            }
            val sep = block.sepBefore.ifEmpty { " " }
            val sameLine = block.lineId == currentLine
            val limit = if (sameLine) maxChars else groupLimit
            if (sb.length + sep.length + piece.length <= limit) {
                sb.append(sep).append(piece)
            } else {
                chunks.add(sb.toString())
                sb.setLength(0)
                sb.append(piece)
            }
            currentLine = block.lineId
        }
        if (sb.isNotEmpty()) chunks.add(sb.toString())
        return chunks
    }

    /** Une los trozos con "\n" (misma convención que el pipeline al recomponer). */
    fun recompose(chunks: List<String>): String = chunks.joinToString("\n")

    /** Parte [text] por frases (signos de fin de frase). Kotlin puro, testeable. */
    fun splitSentences(text: String): List<String> =
        text.split(SENTENCE_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Unidades de granularidad MENOR para el reintento anti-eco: por líneas y,
     * dentro de una línea larga, por frases. Así un bloque tipo instrucción se
     * reintenta línea a línea (que es la granularidad que sí traduce el modelo).
     */
    fun retryUnits(text: String, maxChars: Int = DEFAULT_MAX_CHARS): List<String> {
        val out = ArrayList<String>()
        for (raw in text.split("\n")) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.length <= maxChars) out.add(line) else out.addAll(splitSentences(line))
        }
        return if (out.isEmpty()) listOf(text) else out
    }

    /**
     * Añade a [out] la línea [line] troceada por frases y, si una frase no cabe,
     * por palabras. [firstSep] es el separador del primer bloque.
     */
    private fun appendUnits(
        out: ArrayList<Block>,
        line: String,
        firstSep: String,
        maxChars: Int,
        lineId: Int
    ) {
        if (line.length <= maxChars) {
            out.add(Block(line, firstSep, lineId))
            return
        }
        val sentences = splitSentences(line)
        var first = true
        for (sentence in sentences) {
            val sep = if (first) firstSep else " "
            if (sentence.length <= maxChars) {
                out.add(Block(sentence, sep, lineId))
            } else {
                var firstWord = true
                for (wordPiece in splitByWords(sentence, maxChars)) {
                    out.add(Block(wordPiece, if (firstWord) sep else " ", lineId))
                    firstWord = false
                }
            }
            first = false
        }
    }

    /**
     * Empaqueta palabras en piezas <= [maxChars]. Las palabras individuales más
     * largas que el presupuesto se parten por necesidad (caso patológico).
     */
    private fun splitByWords(sentence: String, maxChars: Int): List<String> {
        val words = sentence.split(WHITESPACE).filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (w in words) {
            if (w.length > maxChars) {
                if (sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) }
                var i = 0
                while (i < w.length) {
                    val end = minOf(i + maxChars, w.length)
                    out.add(w.substring(i, end))
                    i = end
                }
                continue
            }
            if (sb.isEmpty()) sb.append(w)
            else if (sb.length + 1 + w.length <= maxChars) sb.append(' ').append(w)
            else { out.add(sb.toString()); sb.setLength(0); sb.append(w) }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }
}
