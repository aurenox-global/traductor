package com.zota.traductor

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Variante **FULL**: modelos empaquetados dentro de los assets del APK.
 *
 * Cuando el APK se compila con `-Pbundled=true` (`BuildConfig.BUNDLED_MODELS = true`)
 * y los assets existen, el primer arranque **copia** los modelos y voces desde
 * `assets/bundled/…` a `filesDir` (con progreso) en vez de descargarlos. La lógica
 * de descarga se mantiene intacta: si un modelo/voz NO viene bundleado (p.ej. otra
 * voz del catálogo), se sigue descargando como siempre.
 *
 * El build normal (LITE) no define el flag, así que `ensureAll()` es un no-op
 * inmediato y el comportamiento es idéntico al actual.
 *
 * Layout de los assets (generado por `scripts/fetch_bundled_assets.sh`):
 *
 * ```
 * bundled/manifest.json                       { totalBytes, entries:[{p,s}], voices:[…] }
 * bundled/files/<nombre>                      -> filesDir/<nombre>
 * bundled/piper/<id>/model.onnx               -> filesDir/piper_voices/<id>/model.onnx
 * bundled/piper/<id>/tokens.txt               -> filesDir/piper_voices/<id>/tokens.txt
 * bundled/piper/<id>/espeak-ng-data/…         -> filesDir/piper_voices/<id>/espeak-ng-data/…
 * ```
 *
 * La copia se guía por el `manifest.json`: las rutas de assets anidadas se abren
 * con `AssetManager` sin depender de listados.
 *
 * **Robustez (v0.10.2):** cada fichero se copia dentro de su propio `try/catch`
 * (con **un reintento**), acumulando errores y CONTINUANDO con el resto en vez de
 * abortar toda la copia. Se prueban tres estrategias por fichero
 * (`openFd()` → `open(ACCESS_STREAMING)` → `open()`) y se deja registrado cuál
 * funcionó. Antes de copiar se comprueba `filesDir.usableSpace`. Todo el resultado
 * (manifest, entradas, bytes, espacio, método y la excepción completa) queda en
 * [SeedingLog] para poder diagnosticarlo en pantalla sin `adb`.
 */
object BundledAssets {

    private const val TAG = "BundledAssets"

    private const val ASSET_ROOT = "bundled"
    private const val MANIFEST = "$ASSET_ROOT/manifest.json"

    private const val FILES_PREFIX = "files/"
    private const val PIPER_PREFIX = "piper/"
    private const val PIPER_VOICES_DIR = "piper_voices"

    /**
     * Prefijo REAL del asset a abrir. El manifest guarda las rutas RELATIVAS a
     * `bundled/` (p.ej. `files/ggml-base.bin`), así que hay que anteponerlo para
     * llegar al asset correcto (`bundled/files/ggml-base.bin`). Sin esto, todas las
     * entradas anidadas lanzan FileNotFoundException aunque el manifest sí se abra.
     */
    private fun assetPathOf(manifestPath: String): String = "$ASSET_ROOT/$manifestPath"

    /** Voces Piper incluidas en la APK FULL (valores por defecto del catálogo). */
    val VOICES: List<String> = listOf("es_AR-daniela-high", "en_US-hfc_female-medium")

    /** Copia en bloques de 256 KiB (modelos grandes). */
    private const val BUF = 256 * 1024

    private const val METHOD_FD = "openFd"
    private const val METHOD_STREAM = "open(streaming)"
    private const val METHOD_PLAIN = "open()"

    /** Métodos de copia usados en la última llamada a [ensureAll]. */
    private val methodsUsed = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    /** ¿Se compiló como variante FULL? */
    val enabled: Boolean get() = BuildConfig.BUNDLED_MODELS

    // ---------------- diagnóstico ----------------

    private fun openManifestRaw(ctx: Context): JSONObject =
        ctx.assets.open(MANIFEST).use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }

    /** ¿Está el flag activo Y hay assets bundleados de verdad? */
    fun available(ctx: Context): Boolean =
        enabled && runCatching { openManifestRaw(ctx); true }.getOrDefault(false)

    /**
     * Diagnóstico de arranque SIN copiar nada. Registra en [SeedingLog] la variante,
     * el valor de `BUNDLED_MODELS`, si el manifest se pudo abrir (con el error exacto
     * si no) y el espacio libre en `filesDir`.
     *
     * Devuelve `true` si la copia bundleada se puede intentar.
     */
    fun probe(ctx: Context): Boolean {
        SeedingLog.beginRun(ctx, "startup")
        SeedingLog.setVariant(ctx, if (enabled) "FULL" else "LITE")
        SeedingLog.setEnabled(ctx, enabled)
        SeedingLog.setSpace(ctx, ctx.filesDir.usableSpace, after = false)
        if (!enabled) {
            SeedingLog.setManifest(ctx, false, 0, 0, "BUNDLED_MODELS=false (variante LITE)")
            return false
        }
        return try {
            val obj = openManifestRaw(ctx)
            val entries = obj.optJSONArray("entries")?.length() ?: 0
            val total = obj.optLong("totalBytes", 0L)
            SeedingLog.setManifest(ctx, true, entries, total, null)
            true
        } catch (t: Throwable) {
            SeedingLog.setManifest(ctx, false, 0, 0, SeedingLog.describe(t))
            false
        }
    }

    /** ¿Esta voz del catálogo va dentro de la APK? */
    fun isBundledVoice(id: String): Boolean = enabled && VOICES.contains(id)

    /**
     * Ruta destino (relativa a `filesDir`) de una entrada del manifest.
     * Pura y sin Android para poder testearla en host. `null` si no se reconoce.
     */
    fun destRelative(entryPath: String): String? {
        val p = entryPath.trim().removePrefix("/")
        return when {
            p.startsWith(FILES_PREFIX) -> p.removePrefix(FILES_PREFIX).takeIf { it.isNotBlank() }
            p.startsWith(PIPER_PREFIX) -> {
                val rest = p.removePrefix(PIPER_PREFIX)
                val slash = rest.indexOf('/')
                if (slash <= 0) null
                else "$PIPER_VOICES_DIR/$rest"
            }
            else -> null
        }
    }

    private fun readManifest(ctx: Context): JSONObject? = try {
        openManifestRaw(ctx)
    } catch (t: Throwable) {
        Log.w(TAG, "sin manifest bundleado: ${t.message}")
        null
    }

    // ---------------- copia ----------------

    /**
     * Copia a `filesDir` todos los assets bundleados que **falten**, con progreso.
     * Idempotente y seguro de llamar varias veces. Devuelve los bytes copiados en
     * esta llamada (0 si no hay nada que hacer).
     *
     * Cada fichero se copia con `try/catch` + un reintento: un fallo aislado NO
     * aborta el resto. El resultado se registra en [SeedingLog].
     *
     * @param onlyPrefix si no es null, copia solo las entradas con ese prefijo
     *                   (p.ej. `"files/"` para modelos sueltos, `"piper/"` para voces).
     */
    fun ensureAll(
        ctx: Context,
        onStatus: (String) -> Unit = {},
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        onlyPrefix: String? = null
    ): Long {
        if (!enabled) return 0L

        val phase = "ensureAll(${onlyPrefix ?: "todo"})"
        SeedingLog.note(ctx, "$phase …")
        methodsUsed.clear()

        val usableBefore = ctx.filesDir.usableSpace
        SeedingLog.setSpace(ctx, usableBefore, after = false)

        val m: JSONObject = try {
            openManifestRaw(ctx)
        } catch (t: Throwable) {
            val d = SeedingLog.describe(t)
            SeedingLog.setManifest(ctx, false, 0, 0, d)
            SeedingLog.recordError(ctx, "$phase/manifest", t)
            Log.e(TAG, "manifest no legible: $d")
            return 0L
        }
        val entries = m.optJSONArray("entries") ?: return 0L
        val total = m.optLong("totalBytes", 0L)
        SeedingLog.setManifest(ctx, true, entries.length(), total, null)

        var done = 0L
        var copied = 0L
        var lastEmit = 0L

        // 1) contabiliza lo que ya está (para que la barra arranque en su sitio)
        val pending = ArrayList<Pair<String, Long>>(entries.length())
        var pendingBytes = 0L
        for (i in 0 until entries.length()) {
            val o = entries.optJSONObject(i) ?: continue
            val path = o.optString("p")
            if (path.isBlank()) continue
            val size = o.optLong("s", 0L)
            if (onlyPrefix != null && !path.startsWith(onlyPrefix)) continue

            val dest = destRelative(path)?.let { File(ctx.filesDir, it) } ?: continue
            if (dest.isFile && size > 0 && dest.length() == size) {
                done += size
            } else {
                pending += path to size
                pendingBytes += size
            }
        }
        onProgress(done, total)

        if (pendingBytes > 0 && usableBefore < pendingBytes) {
            SeedingLog.note(
                ctx,
                "AVISO espacio: libre=${ModelManager.human(usableBefore)} < necesario=${ModelManager.human(pendingBytes)}"
            )
        }

        // 2) copia lo pendiente (un fichero fallido NO aborta el resto)
        val failed = ArrayList<String>()
        val errors = ArrayList<String>()
        var processed = 0

        for ((path, size) in pending) {
            val dest = destRelative(path)?.let { File(ctx.filesDir, it) } ?: continue
            onStatus("Copiando ${dest.name} …")
            val assetPath = assetPathOf(path)
            var ok = false
            var attempt = 0
            while (!ok && attempt < 2) {
                attempt++
                try {
                    val n = copyAsset(ctx, assetPath, dest) { live ->
                        val now = done + live
                        if (now - lastEmit > 512 * 1024) {
                            lastEmit = now
                            onProgress(now, total)
                        }
                    }
                    done += n
                    copied += n
                    ok = true
                } catch (t: Throwable) {
                    runCatching { File(dest.parentFile, dest.name + ".part").delete() }
                    if (attempt >= 2) {
                        val d = SeedingLog.describe(t)
                        failed += "$path ($size B): $d"
                        errors += d
                        Log.e(TAG, "no se pudo copiar $path: $d")
                        onStatus("Error copiando ${dest.name}: ${t.message}")
                    }
                }
            }
            onProgress(done, total)
            processed++
        }

        SeedingLog.setSpace(ctx, ctx.filesDir.usableSpace, after = true)
        SeedingLog.finishCopy(
            ctx,
            copied = copied,
            total = total,
            methods = methodsUsed.toList(),
            failedFiles = failed,
            errorsCount = errors.size,
            aborted = null
        )

        // 3) metadatos de las voces (info.txt + sid), como hace el download normal
        if (onlyPrefix == null || onlyPrefix.startsWith(PIPER_PREFIX)) {
            for (id in VOICES) {
                runCatching {
                    val dir = File(ctx.filesDir, "$PIPER_VOICES_DIR/$id")
                    if (PiperVoiceManager.isReady(dir)) writeVoiceMeta(ctx, id, dir)
                }.onFailure { Log.w(TAG, "meta de voz $id: ${it.message}") }
            }
        }
        if (processed > 0) Log.i(TAG, "bundleado: $processed ficheros, $copied bytes copiados, ${errors.size} errores")
        return copied
    }

    /** Instala una voz bundleada concreta (usada por `PiperVoiceManager.download`). */
    fun copyVoice(ctx: Context, id: String, onProgress: (Long, Long) -> Unit = { _, _ -> }): Boolean {
        if (!isBundledVoice(id)) return false
        val before = PiperVoiceManager.isReady(PiperVoiceManager.specDir(ctx, id))
        ensureAll(ctx, {}, onProgress, onlyPrefix = "$PIPER_PREFIX$id/")
        val dir = PiperVoiceManager.specDir(ctx, id)
        return PiperVoiceManager.isReady(dir) || before
    }

    /**
     * Usa los modelos bundleados por defecto: Whisper base y la primera voz
     * Piper disponible. No pisa selecciones ya guardadas por el usuario.
     * (No hay modelo de traducción GGUF: el traductor son los ONNX de NLLB.)
     */
    fun applyDefaults(ctx: Context) {
        if (ModelPrefs.activeAsrPath(ctx) == null && ModelManager.isPresent(ctx, ModelManager.ASR_BASE)) {
            ModelPrefs.setActiveAsr(ctx, ModelManager.fileFor(ctx, ModelManager.ASR_BASE))
        }
        if (ModelPrefs.activePiperVoiceId(ctx) == null) {
            VOICES.firstOrNull { PiperVoiceManager.isReady(PiperVoiceManager.specDir(ctx, it)) }
                ?.let { ModelPrefs.setActivePiperVoice(ctx, it) }
        }
    }

    // ---------------- internos ----------------

    /**
     * Copia un asset a `dest` probando varias estrategias en orden:
     *  1. `openFd()` (assets sin comprimir; lo más eficiente),
     *  2. `open(ACCESS_STREAMING)` (sirve también para assets comprimidos),
     *  3. `open()` (último recurso).
     * Devuelve los bytes copiados. Deja en [methodsUsed] la estrategia que funcionó.
     * En caso de fallo borra el `.part` y lanza la última excepción.
     */
    private fun copyAsset(ctx: Context, assetPath: String, dest: File, onLive: (Long) -> Unit): Long {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        var last: Throwable? = null

        val strategies: List<Pair<String, (File) -> Long>> = listOf(
            METHOD_FD to { t -> writeFd(ctx, assetPath, t, onLive) },
            METHOD_STREAM to { t -> writeStream(ctx, assetPath, t, onLive, true) },
            METHOD_PLAIN to { t -> writeStream(ctx, assetPath, t, onLive, false) }
        )
        for ((name, fn) in strategies) {
            try {
                val n = fn(tmp)
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
                methodsUsed.add(name)
                return n
            } catch (t: Throwable) {
                last = t
                runCatching { tmp.delete() }
            }
        }
        throw last ?: RuntimeException("no se pudo copiar $assetPath")
    }

    private fun writeFd(ctx: Context, assetPath: String, tmp: File, onLive: (Long) -> Unit): Long {
        val afd = ctx.assets.openFd(assetPath)
        afd.use {
            val len = it.length
            it.createInputStream().use { input ->
                FileOutputStream(tmp).use { out -> pump(input, out, onLive) }
            }
            return len
        }
    }

    private fun writeStream(
        ctx: Context,
        assetPath: String,
        tmp: File,
        onLive: (Long) -> Unit,
        streaming: Boolean
    ): Long {
        val input = if (streaming) ctx.assets.open(assetPath, AssetManager.ACCESS_STREAMING)
        else ctx.assets.open(assetPath)
        return input.use { ins ->
            FileOutputStream(tmp).use { out -> pump(ins, out, onLive) }
        }
    }

    private fun pump(input: InputStream, out: OutputStream, onLive: (Long) -> Unit): Long {
        val buf = ByteArray(BUF)
        var total = 0L
        var read: Int
        while (input.read(buf).also { read = it } > 0) {
            out.write(buf, 0, read)
            total += read.toLong()
            onLive(total)
        }
        out.flush()
        return total
    }

    private fun writeVoiceMeta(ctx: Context, id: String, dir: File) {
        val spec = PiperVoiceManager.specFor(id)
        runCatching {
            File(dir, "info.txt").writeText(spec?.lang ?: PiperVoiceManager.langFromId(id))
            File(dir, "sid").writeText((spec?.speakerId ?: 0).toString())
        }
    }
}
