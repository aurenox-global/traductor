package com.zota.traductor

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Extracción **streaming** de los paquetes Piper tal cual los publica sherpa-onnx:
 * `vits-piper-<id>.tar.bz2`, cuya estructura interna es
 *
 * ```
 * vits-piper-<id>/
 *   <id>.onnx            (excluye <id>.onnx.json)
 *   <id>.onnx.json
 *   tokens.txt
 *   MODEL_CARD
 *   espeak-ng-data/…     (carpeta completa)
 * ```
 *
 * Al extraer, los ficheros se copian con **nombres canónicos** dentro del directorio
 * de la voz (`model.onnx`, `voice.onnx.json`, `tokens.txt`) y la carpeta
 * `espeak-ng-data/` se conserva íntegra. Nada se carga entero en memoria (se
 * descomprime y copia por bloques), importante para paquetes de hasta ~115 MB.
 *
 * Toda la lógica de clasificación es Kotlin puro (sin Android) para poder
 * verificarla en tests host; la descompresión usa Apache Commons Compress.
 */
object PiperTar {

    const val MODEL = "model.onnx"
    const val JSON = "voice.onnx.json"
    const val TOKENS = "tokens.txt"
    const val ESPEAK = "espeak-ng-data"

    enum class Kind { ONNX, JSON, TOKENS, ESPEAK }

    /** Entrada del tar clasificada: tipo + ruta destino relativa al dir de la voz. */
    data class Entry(val kind: Kind, val destRel: String)

    /**
     * Clasifica la ruta interna de una entrada del tar al destino canónico.
     * Devuelve `null` si debe ignorarse (MODEL_CARD, directorios raíz, etc.).
     * **Rechaza path traversal** (`..`) y rutas absolutas o con `\`.
     */
    fun classify(rawPath: String): Entry? {
        if (rawPath.indexOf('\\') >= 0) return null
        var p = rawPath.trim()
        if (p.isEmpty() || p.startsWith("/")) return null
        while (p.startsWith("./")) p = p.substring(2)
        if (p.isEmpty()) return null
        val segs = p.split('/').filter { it.isNotEmpty() && it != "." }
        if (segs.isEmpty()) return null
        if (segs.any { it == ".." }) return null
        val base = segs.last()
        if (base.endsWith(".onnx.json", ignoreCase = true)) return Entry(Kind.JSON, JSON)
        if (base.endsWith(".onnx", ignoreCase = true)) return Entry(Kind.ONNX, MODEL)
        if (base.equals("tokens.txt", ignoreCase = true)) return Entry(Kind.TOKENS, TOKENS)
        // espeak-ng-data puede venir anidado; conservamos la subruta desde esa carpeta.
        val i = segs.indexOfFirst { it.equals(ESPEAK, ignoreCase = true) }
        if (i >= 0) return Entry(Kind.ESPEAK, segs.subList(i, segs.size).joinToString("/"))
        return null
    }

    /** Resultado de una extracción. */
    data class ExtractResult(
        val model: Boolean,
        val json: Boolean,
        val tokens: Boolean,
        val espeakFiles: Int,
        val rejected: Int
    ) {
        /** ¿Hay lo mínimo para usar la voz (modelo + tokens)? */
        val usable: Boolean get() = model && tokens
    }

    /**
     * Descomprime un tar (bzip2, gzip o plano; se autodetecta por magic bytes)
     * desde `input` a `destDir`, en streaming. `onBytes` informa bytes escritos.
     */
    fun extract(input: InputStream, destDir: File, onBytes: (Long) -> Unit = {}): ExtractResult {
        destDir.mkdirs()
        val root = destDir.canonicalFile
        var model = false
        var json = false
        var tokens = false
        var espeak = 0
        var rejected = 0
        val seen = HashSet<Kind>()
        var written = 0L
        var lastEmit = 0L

        openTar(input).use { tar ->
            var e = tar.nextEntry
            while (e != null) {
                val cls = classify(e.name)
                if (cls == null) {
                    if (e.name.contains("..") || e.name.startsWith("/")) rejected++
                } else if (e.isDirectory) {
                    if (cls.kind == Kind.ESPEAK) safeFile(root, cls.destRel)?.mkdirs()
                } else if (e.isSymbolicLink || e.isLink) {
                    rejected++
                } else {
                    val dup = (cls.kind == Kind.ONNX || cls.kind == Kind.JSON || cls.kind == Kind.TOKENS) &&
                        !seen.add(cls.kind)
                    if (!dup) {
                        val out = safeFile(root, cls.destRel)
                        if (out == null) rejected++ else {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { fos ->
                                val buf = ByteArray(128 * 1024)
                                var r: Int
                                while (tar.read(buf).also { r = it } > 0) {
                                    fos.write(buf, 0, r)
                                    written += r
                                    if (written - lastEmit >= 512 * 1024) {
                                        lastEmit = written
                                        onBytes(written)
                                    }
                                }
                            }
                            when (cls.kind) {
                                Kind.ONNX -> model = true
                                Kind.JSON -> json = true
                                Kind.TOKENS -> tokens = true
                                Kind.ESPEAK -> espeak++
                            }
                        }
                    }
                }
                e = tar.nextEntry
            }
        }
        onBytes(written)
        return ExtractResult(model, json, tokens, espeak, rejected)
    }

    /** Variante para un archivo ya descargado en disco. */
    fun extractFile(tarFile: File, destDir: File, onBytes: (Long) -> Unit = {}): ExtractResult =
        tarFile.inputStream().use { extract(it, destDir, onBytes) }

    /** ¿La entrada es una ruta segura contenida en `root`? */
    private fun safeFile(root: File, rel: String): File? {
        if (rel.isEmpty()) return null
        if (rel.split('/').any { it == ".." }) return null
        val f = File(root, rel)
        return try {
            if (f.canonicalFile.path.startsWith(root.path + File.separator) || f.canonicalFile == root) f else null
        } catch (_: Throwable) { null }
    }

    /** Abre el tar autodetectando la compresión por los primeros bytes. */
    private fun openTar(input: InputStream): TarArchiveInputStream {
        val buffered = BufferedInputStream(input, 128 * 1024)
        buffered.mark(8)
        val magic = ByteArray(8)
        val n = buffered.read(magic)
        buffered.reset()
        return when {
            n >= 3 && magic[0] == 'B'.code.toByte() && magic[1] == 'Z'.code.toByte() && magic[2] == 'h'.code.toByte() ->
                TarArchiveInputStream(BZip2CompressorInputStream(buffered, true))
            n >= 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte() ->
                TarArchiveInputStream(GzipCompressorInputStream(buffered))
            else ->
                TarArchiveInputStream(buffered)
        }
    }

    /** ¿El nombre corresponde a un paquete comprimido aceptado? */
    fun isArchive(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".tar.bz2") || n.endsWith(".tbz2") || n.endsWith(".tbz") ||
            n.endsWith(".tar.gz") || n.endsWith(".tgz") || n.endsWith(".tar")
    }

    /** Nombre base del paquete, sin extensión de archivo ni prefijo `vits-piper-`. */
    fun baseIdFromArchiveName(name: String): String {
        var n = name.trim()
        for (ext in listOf(".tar.bz2", ".tbz2", ".tbz", ".tar.gz", ".tgz", ".tar")) {
            if (n.endsWith(ext, ignoreCase = true)) { n = n.dropLast(ext.length); break }
        }
        if (n.startsWith("vits-piper-", ignoreCase = true)) n = n.substring("vits-piper-".length)
        return n
    }
}
