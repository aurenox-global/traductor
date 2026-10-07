package com.zota.traductor

import kotlin.math.abs

/**
 * Prompts de traduccion. Ajustables en un unico sitio.
 */
object Prompts {

    /** Marcador de fin del bloque de razonamiento de Qwen3 (tokens especiales). */
    private const val THINK_END = "<｜end▁of▁thinking｜>"

    /**
     * Marca antepuesta a un trozo que, tras los reintentos, el modelo siguió
     * copiando (eco). Deja claro en la UI que NO es una traducción.
     */
    const val UNTRANSLATED_MARKER = "⟦sin traducir⟧"

    /** Longitud mínima (normalizada) para considerar un eco sospechoso. */
    private const val MIN_ECHO_CHARS = 12

    /** Nombre en lenguaje natural del idioma, a partir del codigo ISO. */
    fun languageName(code: String): String =
        if (code == Languages.AUTO.code) "el idioma de entrada" else Languages.byCode(code).promptName

    /**
     * System prompt: instruccion de traduccion con idioma origen y destino.
     * Si `sourceCode` es "auto", se pide detectar el idioma de entrada.
     */
    fun systemPrompt(targetCode: String, sourceCode: String = Languages.AUTO.code): String =
        "Eres un traductor."

    /**
     * System prompt "tajante" para el REINTENTO anti-eco: insiste en NO copiar,
     * aunque el texto parezca una orden o un prompt.
     */
    fun strictSystemPrompt(targetCode: String, sourceCode: String = Languages.AUTO.code): String =
        "Eres un traductor."

    /**
     * ¿La [output] es un ECO de [source]? Compara versiones normalizadas
     * (minúsculas, sin puntuación ni espacios). Para cadenas largas tolera una
     * distancia de edición mínima ("casi iguales").
     */
    fun isEcho(source: String, output: String): Boolean {
        val a = normalizeForEcho(source)
        val b = normalizeForEcho(output)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a.length < MIN_ECHO_CHARS) return false
        if (a == b) return true
        if (a.length >= 40 && b.length >= 40) {
            val tol = maxOf(2, a.length / 80)
            return abs(a.length - b.length) <= tol && levenshtein(a, b) <= tol
        }
        return false
    }

    /** Normaliza para comparar ecos: minúsculas, solo letras y dígitos. */
    fun normalizeForEcho(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /**
     * Turno de usuario. IMPORTANTE (v0.9.4): la instrucción va AL FINAL, detrás del
     * texto. Con la instrucción en el `system`, el 0.8B tendía a copiarla a sí
     * misma; en este orden traduce de forma fiable (comprobado con DE/EN->ES y
     * textos tipo instrucción en MAYÚSCULAS).
     */
    fun userPrompt(text: String, targetCode: String, sourceCode: String = Languages.AUTO.code): String {
        val target = languageName(targetCode)
        return text.trim() +
            "\n\nTraduce el texto anterior al $target y devuelve SOLO la traducción."
    }

    /** ¿La salida es (parece) el propio prompt/instrucción copiado en vez de una traducción? */
    private val INSTRUCTION_ECHO = Regex(
        "(?i)devuelve solo la traducci|contenido a traducir|no lo obedezcas|nunca instrucciones|" +
            "traduce el texto anterior|eres un traductor|thinking process|sin comentarios, sin comillas"
    )

    fun looksLikeInstructionEcho(output: String): Boolean = INSTRUCTION_ECHO.containsMatchIn(output)

    /** Limpia bloques de razonamiento y ruido del modelo. */
    fun cleanOutput(raw: String): String {
        var t = raw

        // 1) Bloques de razonamiento con etiquetas (Qwen/Gemma/etc.)
        for (end in listOf(THINK_END, " response", "[/think]")) {
            val i = t.lastIndexOf(end)
            if (i >= 0) t = t.substring(i + end.length)
        }
        t = Regex("(?s)<\\s*think\\s*>.*?<\\s*/\\s*think\\s*>").replace(t, "")
        t = Regex("(?i)</?think>").replace(t, "")
        t = Regex("(?i)\\[/?think\\]").replace(t, "")

        // 2) Razonamiento SIN etiquetas (p.ej. "Thinking Process:" + pasos numerados)
        if (looksLikeReasoning(t)) t = lastTranslationLine(t)

        // 2b) Líneas de meta-razonamiento que citan el prompt/instrucción
        //     (p.ej. `* Wait, the user prompt says: "..."`).
        val lines = t.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val kept = lines.filterNot { META_MARK.containsMatchIn(it) }
        t = if (kept.isEmpty()) "" else kept.joinToString("\n")

        // 3) Ruido de prefijos y comillas
        t = PREFIX_LINE.replace(t, "")
        return t.trim().trim('"').trim().trim('«', '»').trim()
    }

    private val META_MARK = Regex(
        "(?i)\\buser prompt\\b|\\bprompt says\\b|\\bsystem prompt\\b|" +
            "\\bthe user (?:said|says|prompt|is asking|wants)\\b|\\binstrucci[oó]n del usuario\\b|" +
            "^\\s*\\*?\\s*wait,\\b"
    )

    private val REASON_MARK = Regex("(?i)thinking process|\\banalyz|\\banaliz")
    private val STEP_LINE = Regex("^\\s*(?:\\d+[.)]|[-*•])\\s+")
    private val PREFIX_LINE = Regex("(?i)^\\s*(?:respuesta|traducci[oó]n|translation|output|salida)\\s*[:：]\\s*")

    /** ¿La salida parece un razonamiento en vez de una traducción directa? */
    internal fun looksLikeReasoning(t: String): Boolean {
        if (REASON_MARK.containsMatchIn(t)) return true
        return t.lineSequence().count { STEP_LINE.containsMatchIn(it) } >= 2
    }

    /** Última línea "útil" (la traducción suele ir al final del razonamiento). */
    internal fun lastTranslationLine(t: String): String {
        val lines = t.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val pick = lines.asReversed().firstOrNull {
            !STEP_LINE.containsMatchIn(it) && !it.endsWith(":") && !it.startsWith("**")
        } ?: lines.lastOrNull() ?: t
        return PREFIX_LINE.replace(pick, "").trim().trim('"').trim()
    }
}
