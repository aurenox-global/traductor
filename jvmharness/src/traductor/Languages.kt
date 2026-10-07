package com.zota.traductor

import java.util.Locale

/**
 * Idioma soportado por la app. `code` es ISO-639-1 (o "auto" para detección).
 * Puro Kotlin (sin Android) para poder testearlo en el host.
 */
data class Lang(val code: String, val name: String, val flag: String) {

    /** Etiqueta visible: bandera/emoji + nombre (estilo Google Translate). */
    val label: String get() = if (flag.isEmpty()) name else "$flag $name"

    /** Nombre normalizado para el prompt del modelo. */
    val promptName: String get() = name.lowercase(Locale.ROOT)

    override fun toString(): String = label
}

object Languages {

    val AUTO = Lang("auto", "Detectar idioma", "\uD83C\uDF10") // 🌐

    /** Lista amplia de idiomas (origen y destino). */
    val ALL: List<Lang> = listOf(
        AUTO,
        Lang("es", "Español", "\uD83C\uDDEA\uD83C\uDDF8"),
        Lang("en", "Inglés", "\uD83C\uDDEC\uD83C\uDDE7"),
        Lang("fr", "Francés", "\uD83C\uDDEB\uD83C\uDDF7"),
        Lang("de", "Alemán", "\uD83C\uDDE9\uD83C\uDDEA"),
        Lang("it", "Italiano", "\uD83C\uDDEE\uD83C\uDDF9"),
        Lang("pt", "Portugués", "\uD83C\uDDF5\uD83C\uDDF9"),
        Lang("ru", "Ruso", "\uD83C\uDDF7\uD83C\uDDFA"),
        Lang("zh", "Chino", "\uD83C\uDDE8\uD83C\uDDF3"),
        Lang("ja", "Japonés", "\uD83C\uDDEF\uD83C\uDDF5"),
        Lang("ko", "Coreano", "\uD83C\uDDF0\uD83C\uDDF7"),
        Lang("ar", "Árabe", "\uD83C\uDDF8\uD83C\uDDE6"),
        Lang("hi", "Hindi", "\uD83C\uDDEE\uD83C\uDDF3"),
        Lang("tr", "Turco", "\uD83C\uDDF9\uD83C\uDDF7"),
        Lang("nl", "Neerlandés", "\uD83C\uDDF3\uD83C\uDDF1"),
        Lang("pl", "Polaco", "\uD83C\uDDF5\uD83C\uDDF1"),
        Lang("uk", "Ucraniano", "\uD83C\uDDFA\uD83C\uDDE6"),
        Lang("ro", "Rumano", "\uD83C\uDDF7\uD83C\uDDF4"),
        Lang("bg", "Búlgaro", "\uD83C\uDDE7\uD83C\uDDEC"),
        Lang("hu", "Húngaro", "\uD83C\uDDED\uD83C\uDDFA"),
        Lang("sv", "Sueco", "\uD83C\uDDF8\uD83C\uDDEA"),
        Lang("da", "Danés", "\uD83C\uDDE9\uD83C\uDDF0"),
        Lang("fi", "Finés", "\uD83C\uDDEB\uD83C\uDDEE"),
        Lang("no", "Noruego", "\uD83C\uDDF3\uD83C\uDDF4"),
        Lang("cs", "Checo", "\uD83C\uDDE8\uD83C\uDDFF"),
        Lang("el", "Griego", "\uD83C\uDDEC\uD83C\uDDF7"),
        Lang("he", "Hebreo", "\uD83C\uDDEE\uD83C\uDDF1"),
        Lang("fa", "Persa", "\uD83C\uDDEE\uD83C\uDDF7"),
        Lang("id", "Indonesio", "\uD83C\uDDEE\uD83C\uDDE9"),
        Lang("vi", "Vietnamita", "\uD83C\uDDFB\uD83C\uDDF3"),
        Lang("th", "Tailandés", "\uD83C\uDDF9\uD83C\uDDED"),
        Lang("bn", "Bengalí", "\uD83C\uDDE7\uD83C\uDDE9"),
        Lang("ca", "Catalán", "\uD83C\uDDEA\uD83C\uDDF8")
    )

    /** Idiomas destino (sin «detectar»). */
    val TARGETS: List<Lang> = ALL.filter { it.code != AUTO.code }

    fun byCode(code: String): Lang =
        ALL.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: Lang(code, code, "")

    /** Idioma destino por defecto. */
    const val DEFAULT_TARGET = "es"

    /** Idioma origen por defecto. */
    const val DEFAULT_SOURCE = "auto"

    /** Código ISO que entiende whisper.cpp ("auto" = autodetecta). */
    fun whisperCode(code: String): String = if (code == AUTO.code) "auto" else code

    /** Locale para el TTS. */
    fun localeTag(code: String): String = if (code == AUTO.code) "en" else code
}
