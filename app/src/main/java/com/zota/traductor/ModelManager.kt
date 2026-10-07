package com.zota.traductor

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gestion de modelos: descargas en el primer arranque a filesDir (con progreso),
 * importacion de modelos desde almacenamiento externo (SAF) y listado/seleccion.
 *
 * Los modelos descargados de fabrica viven en `filesDir/`.
 * Los importados por el usuario viven en subcarpetas para poder distinguir
 * Whisper del resto aunque compartan extensiones (.bin / .gguf).
 *
 * Variante NLLB puro: NO hay ningun modelo de traduccion GGUF/LLM aqui.
 * El traductor son los ONNX de NLLB (ver [NllbModels]).
 */
object ModelManager {

    private const val TAG = "ModelManager"

    /** Subcarpeta de los Whisper importados. */
    const val DIR_WHISPER_IMPORTS = "whisper_imports"

    /** Extensiones aceptadas al importar ASR. */
    val WHISPER_EXTS = listOf("bin", "gguf")

    data class ModelSpec(
        val id: String,
        val fileName: String,
        val url: String,
        val approxBytes: Long,
        val label: String
    )

    // ---------------- Modelos de voz (Whisper) ----------------

    val ASR_TINY = ModelSpec(
        id = "asr_tiny",
        fileName = "ggml-tiny.bin",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin",
        approxBytes = 77_691_713L,
        label = "Whisper tiny · multilingüe (74 MB)"
    )

    val ASR_BASE = ModelSpec(
        id = "asr_base",
        fileName = "ggml-base.bin",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
        approxBytes = 147_951_465L,
        label = "Whisper base · multilingüe (141 MB)"
    )

    val ASR_SMALL = ModelSpec(
        id = "asr_small",
        fileName = "ggml-small.bin",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin",
        approxBytes = 487_601_967L,
        label = "Whisper small · multilingüe (465 MB)"
    )

    // ---------------- VAD ----------------

    val VAD = ModelSpec(
        id = "vad",
        fileName = "silero_vad.onnx",
        url = "https://raw.githubusercontent.com/snakers4/silero-vad/master/src/silero_vad/data/silero_vad.onnx",
        approxBytes = 2_327_524L,
        label = "Silero VAD v5 · ONNX (2,2 MB)"
    )

    val WHISPER_DOWNLOADS: List<ModelSpec> = listOf(ASR_TINY, ASR_BASE, ASR_SMALL)

    /**
     * Descargas obligatorias del primer arranque. Solo el ASR (Whisper): el
     * traductor son los ONNX de NLLB, que se descargan aparte desde Ajustes.
     */
    val FIRST_RUN: List<ModelSpec> = listOf(ASR_BASE)

    // ---------------- Rutas ----------------

    fun fileFor(ctx: Context, spec: ModelSpec): File = File(ctx.filesDir, spec.fileName)

    fun importDir(ctx: Context, subdir: String): File =
        File(ctx.filesDir, subdir).apply { if (!exists()) mkdirs() }

    fun isPresent(ctx: Context, spec: ModelSpec): Boolean {
        val f = fileFor(ctx, spec)
        return f.isFile && f.length() > 1024
    }

    /** Modelos Whisper importados por el usuario (ordenados por nombre). */
    fun importedWhisper(ctx: Context): List<File> =
        listModels(importDir(ctx, DIR_WHISPER_IMPORTS), WHISPER_EXTS)

    private fun listModels(dir: File, exts: List<String>): List<File> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.length() > 1024 && exts.contains(it.extension.lowercase()) }
            .sortedBy { it.name.lowercase() }

    // ---------------- Seleccion activa ----------------

    /** Resuelve el modelo Whisper activo (preferencia guardada -> descargado -> importado). */
    fun resolveAsr(ctx: Context): File? {
        ModelPrefs.activeAsrPath(ctx)?.let { p ->
            val f = File(p)
            if (f.isFile && f.length() > 1024) return f
        }
        for (spec in listOf(ASR_BASE, ASR_TINY, ASR_SMALL)) {
            if (isPresent(ctx, spec)) return fileFor(ctx, spec)
        }
        return importedWhisper(ctx).firstOrNull()
    }

    // ---------------- Descarga ----------------

    /**
     * Descarga `spec` a filesDir. Soporta redirecciones (HuggingFace -> CDN).
     * onProgress(bytesDescargados, totalBytes| -1)
     */
    fun download(ctx: Context, spec: ModelSpec, onProgress: (Long, Long) -> Unit): File {
        val dest = fileFor(ctx, spec)
        if (isPresent(ctx, spec)) return dest
        return downloadTo(spec.url, dest, onProgress)
    }

    /** Excepción de descarga cancelada por el usuario. */
    class DownloadCancelled(message: String = "Descarga cancelada") : Exception(message)

    /**
     * Descarga genérica de una URL a un archivo destino (soporta redirecciones
     * HuggingFace -> CDN). Usada por los modelos y por las voces Piper.
     * `isCancelled` permite abortar la descarga a mitad (se borra el `.part`).
     */
    fun downloadTo(
        url: String,
        dest: File,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean = { false }
    ): File {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")

        var conn: HttpURLConnection? = null
        try {
            var current = URL(url)
            var redirects = 0
            while (true) {
                conn = (current.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "traductor-android/0.1")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: break
                    conn.disconnect()
                    current = URL(current, loc)
                    redirects++
                    if (redirects > 8) throw RuntimeException("demasiadas redirecciones")
                    continue
                }
                if (code != 200) throw RuntimeException("HTTP $code para $url")
                break
            }

            val total = conn!!.contentLengthLong
            conn!!.inputStream.use { input: InputStream ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(128 * 1024)
                    var read: Int
                    var done = 0L
                    var lastEmit = 0L
                    while (input.read(buf).also { read = it } > 0) {
                        if (isCancelled()) throw DownloadCancelled()
                        out.write(buf, 0, read)
                        done += read
                        if (done - lastEmit > 512 * 1024) {
                            onProgress(done, total)
                            lastEmit = done
                        }
                    }
                    out.flush()
                    onProgress(done, total)
                    Log.i(TAG, "descargado ${dest.name}: $done bytes")
                }
            }
        } catch (t: Throwable) {
            // No dejar restos de descargas fallidas/canceladas.
            runCatching { tmp.delete() }
            throw t
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }

        if (tmp.renameTo(dest)) return dest
        tmp.copyTo(dest, overwrite = true)
        tmp.delete()
        return dest
    }

    /**
     * Importa un modelo desde almacenamiento externo (SAF) a filesDir.
     * Si `subdir` no es nulo, se copia dentro de esa subcarpeta (importados).
     */
    fun importFromUri(
        ctx: Context,
        uri: Uri,
        destName: String,
        subdir: String? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): File {
        val dir = if (subdir != null) importDir(ctx, subdir) else ctx.filesDir
        val dest = File(dir, destName)
        val tmp = File(dir, "$destName.part")
        ctx.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "no se pudo abrir $uri" }
            val total = try {
                ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            } catch (_: Throwable) { -1L }
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(128 * 1024)
                var read: Int
                var done = 0L
                while (input.read(buf).also { read = it } > 0) {
                    out.write(buf, 0, read)
                    done += read
                    onProgress(done, total)
                }
            }
        }
        if (tmp.renameTo(dest)) return dest
        tmp.copyTo(dest, overwrite = true)
        tmp.delete()
        return dest
    }

    /** Nombre de archivo limpio a partir de una Uri (query DISPLAY_NAME o ultimo segmento). */
    fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    val n = c.getString(idx)
                    if (!n.isNullOrBlank()) return sanitize(n)
                }
            }
        } catch (_: Throwable) { /* ignore */ }
        val last = uri.lastPathSegment ?: "modelo.bin"
        return sanitize(last)
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(120)

    fun delete(file: File): Boolean = try {
        if (file.isFile) file.delete() else false
    } catch (_: Throwable) { false }

    fun human(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> String.format("%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format("%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
