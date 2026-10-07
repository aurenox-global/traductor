package com.zota.traductor

/**
 * Divide un texto en trozos ("chunks") que quepan holgados en el contexto del
 * modelo, cortando por la jerarquía de mayor a menor:
 *
 *   párrafos -> líneas -> frases -> palabras
 *
 * Nunca corta a mitad de palabra (salvo el caso patológico de una única palabra
 * más larga que el presupuesto, que se parte por necesidad). La recomposición
 * ([recompose], que une con "\n") conserva el orden y los saltos de párrafo.
 *
 * Es Kotlin puro (sin Android) para poder testearse en el host.
 */
object TextChunker {

    /** Presupuesto por defecto por trozo, en caracteres. */
    const val DEFAULT_MAX_CHARS: Int = 1200

    /** Separador de párrafos (una o más líneas en blanco). */
    private val PARAGRAPH_SPLIT = Regex("\\n\\s*\\n")

    /** Fin de frase: punto, exclamación, interrogación, puntos suspensivos (ASCII y CJK). */
    private val SENTENCE_SPLIT = Regex("(?<=[.!?…。！？])\\s+")

    private val WHITESPACE = Regex("\\s+")

    /** Un bloque indivisible junto al separador que debe precederle al recomponer. */
    private data class Block(val text: String, val sepBefore: String)

    /**
     * Parte [text] en trozos de longitud <= [maxChars].
     *
     * - Si el texto ya cabe -> devuelve una lista con el texto idéntico.
     * - Si no -> trozos múltiples, cada uno <= [maxChars], sin cortar palabras.
     * - Los trozos se recortan (sin espacios sobrantes en los bordes).
     * - El texto vacío devuelve lista vacía.
     */
    fun chunk(text: String, maxChars: Int = DEFAULT_MAX_CHARS): List<String> {
        require(maxChars >= 1) { "maxChars debe ser >= 1" }
        if (text.isEmpty()) return emptyList()
        if (text.length <= maxChars) return listOf(text)

        val blocks = ArrayList<Block>()
        val paragraphs = text.split(PARAGRAPH_SPLIT).filter { it.isNotBlank() }
        for ((pi, paragraph) in paragraphs.withIndex()) {
            val lines = paragraph.split("\n")
            for ((li, rawLine) in lines.withIndex()) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                val firstSep = when {
                    li > 0 -> "\n"
                    pi > 0 -> "\n\n"
                    else -> ""
                }
                appendUnits(blocks, line, firstSep, maxChars)
            }
        }

        // Empaquetado goloso: junta bloques mientras quepan en el presupuesto.
        val chunks = ArrayList<String>()
        val sb = StringBuilder()
        for (block in blocks) {
            val piece = block.text.trim()
            if (piece.isEmpty()) continue
            if (sb.isEmpty()) {
                sb.append(piece)
            } else {
                val sep = block.sepBefore.ifEmpty { " " }
                if (sb.length + sep.length + piece.length <= maxChars) {
                    sb.append(sep).append(piece)
                } else {
                    chunks.add(sb.toString())
                    sb.setLength(0)
                    sb.append(piece)
                }
            }
        }
        if (sb.isNotEmpty()) chunks.add(sb.toString())
        return chunks
    }

    /** Une los trozos con "\n" (misma convención que el pipeline al recomponer). */
    fun recompose(chunks: List<String>): String = chunks.joinToString("\n")

    /**
     * Añade a [out] la línea [line] troceada por frases y, si una frase no cabe,
     * por palabras. [firstSep] es el separador del primer bloque ("" , "\n" o "\n\n").
     */
    private fun appendUnits(out: ArrayList<Block>, line: String, firstSep: String, maxChars: Int) {
        if (line.length <= maxChars) {
            out.add(Block(line, firstSep))
            return
        }
        val sentences = line.split(SENTENCE_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
        var first = true
        for (sentence in sentences) {
            val sep = if (first) firstSep else " "
            if (sentence.length <= maxChars) {
                out.add(Block(sentence, sep))
            } else {
                var firstWord = true
                for (wordPiece in splitByWords(sentence, maxChars)) {
                    out.add(Block(wordPiece, if (firstWord) sep else " "))
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
