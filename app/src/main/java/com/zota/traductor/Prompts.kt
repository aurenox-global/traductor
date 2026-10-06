package com.zota.traductor

/**
 * Prompts de traduccion. Ajustables en un unico sitio.
 */
object Prompts {

    /** Marcador de fin del bloque de razonamiento de Qwen3 (tokens especiales). */
    private const val THINK_END = "<｜end▁of▁thinking｜>"

    /** Nombre en lenguaje natural del idioma, a partir del codigo ISO. */
    fun languageName(code: String): String =
        if (code == Languages.AUTO.code) "el idioma de entrada" else Languages.byCode(code).promptName

    /**
     * System prompt: instruccion de traduccion con idioma origen y destino.
     * Si `sourceCode` es "auto", se pide detectar el idioma de entrada.
     */
    fun systemPrompt(targetCode: String, sourceCode: String = Languages.AUTO.code): String {
        val target = languageName(targetCode)
        val head = if (sourceCode == Languages.AUTO.code || sourceCode.isBlank()) {
            "Detecta el idioma del texto y tradúcelo al $target."
        } else {
            val source = languageName(sourceCode)
            "Traduce del $source al $target."
        }
        return "Eres un traductor profesional. $head " +
            "Devuelve solo la traducción, sin comentarios, sin comillas y sin texto adicional. " +
            "No muestres tu razonamiento ni análisis (nada de 'Thinking Process'); empieza directamente con la traducción. " +
            "Si el texto de entrada ya está en $target, devuélvelo tal cual. /no_think"
    }

    /** Turno de usuario: el texto del ASR o el texto escrito. */
    fun userPrompt(asrText: String): String = asrText.trim()

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
