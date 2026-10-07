package com.zota.traductor

/**
 * Divide un texto en trozos ("chunks") que quepan holgados en el contexto del
 * modelo.
 *
 * v0.9.6: el troceo es **por PÁRRAFOS** (separados por línea en blanco). Un
 * párrafo que no cabe se parte por FRASES y, si una frase no cabe, por palabras.
 *
 * IMPORTANTE: NO se parte por líneas simples. La v0.9.3 lo hacía y **rompía las
 * listas**: las líneas cortadas ("control plate", "monitor") se perdían y el
 * modelo renumeraba los ítems. Como el prompt ya evita el eco, no hace falta
 * esa granularidad para trocear (sí se usa, en [retryUnits], para el reintento).
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
    /**
     * Parte [text] en trozos de longitud <= [maxChars].
     *
     * - Texto que ya cabe -> lista con el texto idéntico (caso corto intacto).
     * - Varios párrafos -> se agrupan hasta [maxChars]; un párrafo demasiado
     *   largo se parte por frases (y por palabras si una frase no cabe).
     * - Nunca se corta a mitad de palabra. [recompose] une con "\n".
     * - El texto vacío devuelve lista vacía.
     */
    fun chunk(text: String, maxChars: Int = DEFAULT_MAX_CHARS): List<String> {
        require(maxChars >= 1) { "maxChars debe ser >= 1" }
        if (text.isEmpty()) return emptyList()
        if (text.length <= maxChars) return listOf(text)

        val paraSplit = Regex("\\n[ \\t]*\\n")
        val chunks = ArrayList<String>()
        val sb = StringBuilder()
        for (rawPara in text.split(paraSplit)) {
            val para = rawPara.trim()
            if (para.isEmpty()) continue
            val units: List<String> = if (para.length <= maxChars) listOf(para)
            else splitSentences(para).flatMap { s ->
                if (s.length <= maxChars) listOf(s) else splitByWords(s, maxChars)
            }
            for (u in units) {
                if (sb.isEmpty()) {
                    sb.append(u)
                } else if (sb.length + 2 + u.length <= maxChars) {
                    sb.append("\n\n").append(u)
                } else {
                    chunks.add(sb.toString())
                    sb.setLength(0)
                    sb.append(u)
                }
            }
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
