package com.zota.traductor

/**
 * Lógica de **importación de los modelos NLLB** desde el almacenamiento (SAF).
 *
 * La parte pura (sin Android) —clasificar nombres y validar que no falte ninguno—
 * vive aquí para poder probarla en host ([NllbImportTest]). El copiado real a
 * `filesDir/nllb_models/` lo hace [NllbModels.importAll] (ContentResolver).
 *
 * Casos de uso:
 *  - El usuario elige una **carpeta** (`ACTION_OPEN_DOCUMENT_TREE`) que contiene
 *    los ficheros ONNX (encoder + decoder) y `tokenizer.bin`; se clasifican por
 *    nombre y se copian con los **nombres que espera el motor**.
 *  - Si falta alguno, [plan] devuelve la lista de roles ausentes para avisar antes
 *    de copiar nada.
 *
 * Se aceptan tanto los nombres originales de HuggingFace
 * (`encoder_model_quantized.onnx`, `decoder_model_merged_quantized.onnx`,
 * `tokenizer.bin`) como los nombres destino de la app.
 */
object NllbImport {

    /** Rol de un fichero dentro del paquete NLLB. */
    enum class Role(val id: String, val label: String, val targetName: String) {
        ENCODER("encoder", "encoder ONNX", NllbModels.ENCODER.fileName),
        DECODER("decoder", "decoder ONNX", NllbModels.DECODER.fileName),
        TOKENIZER("tokenizer", "tokenizer.bin", NllbModels.TOKENIZER_NAME)
    }

    /** Un fichero origen asignado a un rol. */
    data class Match(val source: String, val role: Role)

    /** Resultado del análisis de un conjunto de nombres de fichero. */
    data class Plan(val matches: List<Match>, val missing: List<Role>) {
        val complete: Boolean get() = missing.isEmpty()
        fun matchFor(role: Role): Match? = matches.firstOrNull { it.role == role }
    }

    /**
     * Clasifica un nombre de fichero al rol NLLB correspondiente, o `null` si no
     * es un fichero del paquete NLLB. Insensible a mayúsculas y a la ruta.
     */
    fun classify(fileName: String): Role? {
        val n = fileName.substringAfterLast('/').substringAfterLast('\\').trim().lowercase()
        if (n.isEmpty()) return null
        val onnx = n.endsWith(".onnx")
        return when {
            onnx && n.contains("encoder") -> Role.ENCODER
            onnx && n.contains("decoder") -> Role.DECODER
            n == NllbModels.TOKENIZER_NAME.lowercase() -> Role.TOKENIZER
            n.endsWith(".bin") && n.contains("tokenizer") -> Role.TOKENIZER
            else -> null
        }
    }

    /**
     * Analiza una lista de nombres de fichero (p.ej. el contenido de la carpeta
     * elegida) y devuelve qué roles se han encontrado (con su nombre de origen) y
     * cuáles faltan. El primer nombre que casa con cada rol es el que se usa.
     */
    fun plan(fileNames: Collection<String>): Plan {
        val found = LinkedHashMap<Role, String>()
        for (name in fileNames) {
            val role = classify(name) ?: continue
            if (!found.containsKey(role)) found[role] = name
        }
        val matches = Role.values().filter { found.containsKey(it) }
            .map { Match(found[it]!!, it) }
        val missing = Role.values().filter { !found.containsKey(it) }
        return Plan(matches, missing)
    }

    /** Texto legible de los roles que faltan (para el aviso de la UI). */
    fun missingLabels(plan: Plan): String = plan.missing.joinToString(", ") { it.label }
}
