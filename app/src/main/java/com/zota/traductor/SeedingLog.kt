package com.zota.traductor

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnóstico **PERSISTENTE** del copiado ("seeding") de la variante FULL.
 *
 * Por qué existe: en la APK FULL el estado que se ve en pantalla es *transitorio*
 * (el mensaje de la copia se sobrescribe casi enseguida con el de la descarga de
 * respaldo), de modo que sin `adb` es imposible saber qué pasó realmente. Aquí se
 * guarda, en `SharedPreferences` **y** en `filesDir/seeding.log`, el resultado
 * COMPLETO del último intento:
 *
 *  - variante (FULL/LITE) y valor de `BuildConfig.BUNDLED_MODELS`
 *  - si el manifest bundleado se encontró (o el error exacto al abrirlo)
 *  - nº de entradas y bytes totales
 *  - bytes copiados / total y método de copia empleado (openFd / open streaming / open)
 *  - espacio libre (usableSpace) en filesDir antes y después
 *  - nº y lista de ficheros que fallaron
 *  - **el mensaje COMPLETO de la última excepción** (tipo + message), que la
 *    descarga de respaldo NO pisa
 *  - presencia (y tamaño real) de los modelos clave tras el intento
 *
 * Es la fuente de verdad: la UI de Ajustes lo muestra en "Diagnóstico offline".
 */
object SeedingLog {

    private const val TAG = "SeedingLog"
    private const val PREF = "traductor_seeding_diag"
    private const val FILE_NAME = "seeding.log"

    // ---- claves de SharedPreferences ----
    private const val K_TS = "ts"
    private const val K_VARIANT = "variant"
    private const val K_ENABLED = "enabled"
    private const val K_MANIFEST = "manifest_found"
    private const val K_MANIFEST_ERROR = "manifest_error"
    private const val K_ENTRIES = "entries"
    private const val K_TOTAL = "total_bytes"
    private const val K_COPIED = "copied_bytes"
    private const val K_USABLE_BEFORE = "usable_before"
    private const val K_USABLE_AFTER = "usable_after"
    private const val K_METHODS = "copy_methods"
    private const val K_FAILED_FILES = "failed_files"
    private const val K_ERRORS = "errors_count"
    private const val K_LAST_ERROR = "last_error"
    private const val K_LAST_ERROR_PHASE = "last_error_phase"
    private const val K_DOWNLOAD_ERROR = "download_error"
    private const val K_RUNS = "runs"
    private const val K_LAST_COPY_PREFIX = "last_copy_prefix"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun logFile(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    /** Tipo + mensaje completos de una excepción (nunca se descarta el mensaje). */
    fun describe(t: Throwable): String {
        val msg = t.message?.takeIf { it.isNotBlank() } ?: "(sin mensaje)"
        return "${t.javaClass.name}: $msg"
    }

    /** Añade una línea al fichero `seeding.log` (crea el fichero si hace falta). */
    @Synchronized
    fun append(ctx: Context, line: String) {
        runCatching {
            logFile(ctx).appendText("[${stamp()}] $line\n")
        }.onFailure { Log.w(TAG, "no se pudo escribir seeding.log: ${it.message}") }
    }

    fun note(ctx: Context, line: String) = append(ctx, line)

    /** Marca el comienzo de un intento de arranque/seed. */
    fun beginRun(ctx: Context, phase: String) {
        val runs = p(ctx).getInt(K_RUNS, 0) + 1
        p(ctx).edit()
            .putLong(K_TS, System.currentTimeMillis())
            .putInt(K_RUNS, runs)
            .putString(K_LAST_COPY_PREFIX, phase)
            .apply()
        append(ctx, "=========== ARRANQUE #$runs ($phase) ===========")
    }

    fun setVariant(ctx: Context, variant: String) =
        p(ctx).edit().putString(K_VARIANT, variant).apply()

    fun setEnabled(ctx: Context, enabled: Boolean) =
        p(ctx).edit().putBoolean(K_ENABLED, enabled).apply()

    fun setSpace(ctx: Context, usable: Long, after: Boolean) =
        p(ctx).edit().putLong(if (after) K_USABLE_AFTER else K_USABLE_BEFORE, usable).apply()

    fun setManifest(ctx: Context, found: Boolean, entries: Int, total: Long, error: String?) {
        p(ctx).edit()
            .putBoolean(K_MANIFEST, found)
            .putInt(K_ENTRIES, entries)
            .putLong(K_TOTAL, total)
            .putString(K_MANIFEST_ERROR, error)
            .apply()
        append(
            ctx,
            if (found) "manifest: OK ($entries entradas, $total bytes)" +
                (error?.let { " · aviso: $it" } ?: "")
            else "manifest: NO DISPONIBLE -> ${error ?: "(sin detalle)"}"
        )
    }

    /** Guarda el resultado de la fase de copia. */
    fun finishCopy(
        ctx: Context,
        copied: Long,
        total: Long,
        methods: Collection<String>,
        failedFiles: List<String>,
        errorsCount: Int,
        aborted: String?
    ) {
        p(ctx).edit()
            .putLong(K_COPIED, copied)
            .putString(K_METHODS, methods.joinToString(",").ifBlank { "(ninguno)" })
            .putInt(K_ERRORS, errorsCount)
            .putString(K_FAILED_FILES, failedFiles.joinToString("\n"))
            .apply()
        append(
            ctx,
            "copia: $copied/$total bytes · métodos=${methods.joinToString(",").ifBlank { "(ninguno)" }}" +
                " · errores=$errorsCount" + (aborted?.let { " · ABORTADO: $it" } ?: "")
        )
        for (f in failedFiles) append(ctx, "  fallo: $f")
    }

    /** Registra la excepción de la fase de copia (NO la pisa la descarga). */
    fun recordError(ctx: Context, phase: String, t: Throwable) {
        val d = describe(t)
        p(ctx).edit()
            .putString(K_LAST_ERROR, d)
            .putString(K_LAST_ERROR_PHASE, phase)
            .apply()
        append(ctx, "ERROR [$phase]: $d")
    }

    /** Un error de descarga se guarda aparte para no pisar el de la copia. */
    fun recordDownloadError(ctx: Context, t: Throwable) {
        val d = describe(t)
        p(ctx).edit().putString(K_DOWNLOAD_ERROR, d).apply()
        append(ctx, "descarga falló: $d")
    }

    // ---------------- lectura ----------------

    data class Snapshot(
        val timestamp: Long,
        val variant: String,
        val enabled: Boolean,
        val manifestFound: Boolean,
        val manifestError: String?,
        val entries: Int,
        val totalBytes: Long,
        val copiedBytes: Long,
        val usableBefore: Long,
        val usableAfter: Long,
        val methods: String,
        val failedFiles: List<String>,
        val errorsCount: Int,
        val lastError: String?,
        val lastErrorPhase: String?,
        val downloadError: String?,
        val runs: Int
    )

    fun snapshot(ctx: Context): Snapshot {
        val s = p(ctx)
        return Snapshot(
            timestamp = s.getLong(K_TS, 0L),
            variant = s.getString(K_VARIANT, "?") ?: "?",
            enabled = s.getBoolean(K_ENABLED, false),
            manifestFound = s.getBoolean(K_MANIFEST, false),
            manifestError = s.getString(K_MANIFEST_ERROR, null),
            entries = s.getInt(K_ENTRIES, 0),
            totalBytes = s.getLong(K_TOTAL, 0L),
            copiedBytes = s.getLong(K_COPIED, 0L),
            usableBefore = s.getLong(K_USABLE_BEFORE, 0L),
            usableAfter = s.getLong(K_USABLE_AFTER, 0L),
            methods = s.getString(K_METHODS, "(ninguno)") ?: "(ninguno)",
            failedFiles = s.getString(K_FAILED_FILES, "").orEmpty()
                .split('\n').map { it.trim() }.filter { it.isNotEmpty() },
            errorsCount = s.getInt(K_ERRORS, 0),
            lastError = s.getString(K_LAST_ERROR, null),
            lastErrorPhase = s.getString(K_LAST_ERROR_PHASE, null),
            downloadError = s.getString(K_DOWNLOAD_ERROR, null),
            runs = s.getInt(K_RUNS, 0)
        )
    }

    /** Un modelo clave y su estado real en disco. */
    data class KeyModel(val label: String, val path: String, val bytes: Long) {
        val present: Boolean get() = bytes > 1024
    }

    /** Modelos clave comprobados tras el intento de copia. */
    fun keyModels(ctx: Context): List<KeyModel> {
        val out = ArrayList<KeyModel>()
        fun add(label: String, file: File) {
            val n = if (file.isFile) file.length() else 0L
            out.add(KeyModel(label, file.absolutePath, n))
        }
        fun dirBytes(dir: File): Long {
            var n = 0L
            dir.walkTopDown().forEach { if (it.isFile) n += it.length() }
            return n
        }
        add("NLLB encoder", NllbModels.fileFor(ctx, NllbModels.ENCODER))
        add("NLLB decoder", NllbModels.fileFor(ctx, NllbModels.DECODER))
        add("Whisper base (ASR)", ModelManager.fileFor(ctx, ModelManager.ASR_BASE))
        add("VAD silero", ModelManager.fileFor(ctx, ModelManager.VAD))
        add("OCR det v6", OcrModels.fileFor(ctx, OcrModels.DET_V6))
        add("OCR rec v6", OcrModels.fileFor(ctx, OcrModels.REC_V6))
        add("OCR yml (dict)", OcrModels.fileFor(ctx, OcrModels.REC_V6_YML))
        add("OCR dict parsed", OcrModels.dictFile(ctx))
        for (id in BundledAssets.VOICES) {
            val dir = PiperVoiceManager.specDir(ctx, id)
            val ready = PiperVoiceManager.isReady(dir)
            val n = if (ready) dirBytes(dir) else 0L
            out.add(KeyModel("Voz $id", dir.absolutePath, n))
        }
        return out
    }

    /** Texto completo del diagnóstico (grande, pensado para una captura). */
    fun format(ctx: Context): String {
        val s = snapshot(ctx)
        val sb = StringBuilder()
        sb.append("DIAGNÓSTICO OFFLINE (seeding)\n")
        sb.append("Variante: ").append(s.variant)
            .append(" · BUNDLED_MODELS=").append(s.enabled).append('\n')
        sb.append("Arranques registrados: ").append(s.runs).append('\n')
        if (s.timestamp > 0L) {
            sb.append("Último: ").append(
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(s.timestamp))
            ).append('\n')
        }
        sb.append('\n')
        sb.append("Manifest bundleado: ")
            .append(if (s.manifestFound) "ENCONTRADO" else "NO DISPONIBLE").append('\n')
        if (s.manifestFound) {
            sb.append("  Entradas: ").append(s.entries).append('\n')
            sb.append("  Total: ").append(ModelManager.human(s.totalBytes)).append('\n')
        }
        s.manifestError?.let { sb.append("  Error manifest: ").append(it).append('\n') }

        sb.append("Copiado: ").append(ModelManager.human(s.copiedBytes))
            .append(" / ").append(ModelManager.human(s.totalBytes)).append('\n')
        sb.append("Método de copia: ").append(s.methods).append('\n')
        sb.append("Espacio libre filesDir: ")
            .append(ModelManager.human(s.usableBefore)).append("  ->  ")
            .append(ModelManager.human(s.usableAfter)).append('\n')
        sb.append("Ficheros fallidos: ").append(s.errorsCount).append('\n')
        for (f in s.failedFiles.take(12)) sb.append("  · ").append(f).append('\n')
        if (s.failedFiles.size > 12) sb.append("  · … (+").append(s.failedFiles.size - 12).append(")\n")

        sb.append('\n')
        sb.append("ÚLTIMO ERROR DE COPIA")
        s.lastErrorPhase?.let { sb.append(" [").append(it).append(']') }
        sb.append(":\n")
        sb.append("  ").append(s.lastError ?: "(ninguno)").append('\n')

        s.downloadError?.let {
            sb.append("Último error de descarga:\n  ").append(it).append('\n')
        }

        sb.append('\n')
        sb.append("ESTADO DE LOS MODELOS CLAVE\n")
        for (m in keyModels(ctx)) {
            sb.append(if (m.present) "✔ " else "✘ ").append(m.label).append(": ")
            sb.append(if (m.present) ModelManager.human(m.bytes) else "AUSENTE")
            sb.append('\n')
            sb.append("    ").append(m.path).append('\n')
        }
        return sb.toString()
    }
}
