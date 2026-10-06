package com.zota.traductor

import java.io.File

/**
 * Diccionario de caracteres del modelo de reconocimiento PP-OCR (CRNN + CTC).
 *
 * El modelo de reconocimiento devuelve un índice de clase por cada paso de tiempo;
 * el índice 0 es el "blank" de CTC y a partir de 1 hay que mapear contra esta lista.
 *
 * Kotlin puro (sin Android) para poder verificar el parser en tests de host.
 * El diccionario oficial de PP-OCRv6 viene embebido en `inference.yml` dentro del
 * bloque `PostProcess.character_dict:` (una entrada YAML por carácter).
 */
object OcrDict {

    /** Cabecera del bloque YAML que contiene la lista de caracteres. */
    private const val KEY = "character_dict:"

    /** Línea de item de lista YAML: `- <valor>` (el valor puede estar entre comillas). */
    private val ITEM = Regex("^\\s*- ?(.*)$")

    /**
     * Extrae la lista de caracteres de un `inference.yml` de PP-OCR.
     * Acepta comillas simples (con escape `''`) y dobles. Se detiene al terminar la lista.
     */
    fun parseCharacterDict(yaml: String): List<String> {
        val out = ArrayList<String>(7000)
        var inList = false
        for (raw in yaml.lineSequence()) {
            if (!inList) {
                if (raw.trim().startsWith(KEY)) inList = true
                continue
            }
            val m = ITEM.find(raw)
            if (m == null) {
                if (raw.isBlank()) continue   // tolera líneas vacías dentro del bloque
                break                          // fin de la lista
            }
            out.add(unquote(m.groupValues[1]))
        }
        return out
    }

    private fun unquote(v: String): String {
        if (v.length >= 2 && v.first() == '\'' && v.last() == '\'') {
            return v.substring(1, v.length - 1).replace("''", "'")
        }
        if (v.length >= 2 && v.first() == '"' && v.last() == '"') {
            return v.substring(1, v.length - 1)
        }
        return v
    }

    /** Escribe el diccionario como texto plano: un carácter por línea. */
    fun write(file: File, chars: List<String>) {
        file.parentFile?.mkdirs()
        val sb = StringBuilder(chars.size * 3 + 16)
        for (c in chars) sb.append(c).append('\n')
        file.writeText(sb.toString(), Charsets.UTF_8)
    }

    /** Carga el diccionario (un carácter por línea). Devuelve lista vacía si no es válido. */
    fun load(file: File): List<String> {
        if (!file.isFile || file.length() < 4) return emptyList()
        return try {
            file.readLines(Charsets.UTF_8)
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
