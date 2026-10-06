package com.zota.traductor

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

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
 * con `AssetManager.open()` (soportado con seguridad), sin depender de listados.
 */
object BundledAssets {

    private const val TAG = "BundledAssets"

    private const val ASSET_ROOT = "bundled"
    private const val MANIFEST = "$ASSET_ROOT/manifest.json"

    private const val FILES_PREFIX = "files/"
    private const val PIPER_PREFIX = "piper/"
    private const val PIPER_VOICES_DIR = "piper_voices"

    /** Voces Piper incluidas en la APK FULL (valores por defecto del catálogo). */
    val VOICES: List<String> = listOf("es_AR-daniela-high", "en_US-hfc_female-medium")

    /** Copia en bloques de 256 KiB (modelos grandes). */
    private const val BUF = 256 * 1024

    /** ¿Se compiló como variante FULL? */
    val enabled: Boolean get() = BuildConfig.BUNDLED_MODELS

    /** ¿Está el flag activo Y hay assets bundleados de verdad? */
    fun available(ctx: Context): Boolean =
        enabled && runCatching { ctx.assets.open(MANIFEST).use { true } }.getOrDefault(false)

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
        ctx.assets.open(MANIFEST).use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
    } catch (t: Throwable) {
        Log.w(TAG, "sin manifest bundleado: ${t.message}")
        null
    }

    /**
     * Copia a `filesDir` todos los assets bundleados que **falten**, con progreso.
     * Idempotente y seguro de llamar varias veces. Devuelve los bytes copiados en
     * esta llamada (0 si no hay nada que hacer).
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
        val m = readManifest(ctx) ?: return 0L
        val entries = m.optJSONArray("entries") ?: return 0L
        val total = m.optLong("totalBytes", 0L)

        var done = 0L
        var copied = 0L
        var lastEmit = 0L
        var processed = 0

        // 1) contabiliza lo que ya está (para que la barra arranque en su sitio)
        val pending = ArrayList<Pair<String, Long>>(entries.length())
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
            }
        }
        onProgress(done, total)

        // 2) copia lo pendiente
        for ((path, size) in pending) {
            val dest = destRelative(path)?.let { File(ctx.filesDir, it) } ?: continue
            onStatus("Copiando ${dest.name} …")
            copyAsset(ctx, path, dest) { read ->
                done += read
                copied += read
                val now = done
                if (now - lastEmit > 512 * 1024) {
                    lastEmit = now
                    onProgress(done, total)
                }
            }
            onProgress(done, total)
            processed++
        }

        // 3) metadatos de las voces (info.txt + sid), como hace el download normal
        if (onlyPrefix == null || onlyPrefix.startsWith(PIPER_PREFIX)) {
            for (id in VOICES) {
                val dir = File(ctx.filesDir, "$PIPER_VOICES_DIR/$id")
                if (PiperVoiceManager.isReady(dir)) writeVoiceMeta(ctx, id, dir)
            }
        }
        if (processed > 0) Log.i(TAG, "bundleado: $processed ficheros, $copied bytes copiados")
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
     * Usa los modelos bundleados por defecto: Whisper base, Qwen y la primera voz
     * Piper disponible. No pisa selecciones ya guardadas por el usuario.
     */
    fun applyDefaults(ctx: Context) {
        if (ModelPrefs.activeAsrPath(ctx) == null && ModelManager.isPresent(ctx, ModelManager.ASR_BASE)) {
            ModelPrefs.setActiveAsr(ctx, ModelManager.fileFor(ctx, ModelManager.ASR_BASE))
        }
        if (ModelPrefs.activeMtPath(ctx) == null && ModelManager.isPresent(ctx, ModelManager.MT)) {
            ModelPrefs.setActiveMt(ctx, ModelManager.fileFor(ctx, ModelManager.MT))
        }
        if (ModelPrefs.activePiperVoiceId(ctx) == null) {
            VOICES.firstOrNull { PiperVoiceManager.isReady(PiperVoiceManager.specDir(ctx, it)) }
                ?.let { ModelPrefs.setActivePiperVoice(ctx, it) }
        }
    }

    // ---------------- internos ----------------

    private fun copyAsset(ctx: Context, assetPath: String, dest: File, onBytes: (Long) -> Unit) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        try {
            ctx.assets.open(assetPath).use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(BUF)
                    var read: Int
                    while (input.read(buf).also { read = it } > 0) {
                        out.write(buf, 0, read)
                        onBytes(read.toLong())
                    }
                }
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            throw t
        }
    }

    private fun writeVoiceMeta(ctx: Context, id: String, dir: File) {
        val spec = PiperVoiceManager.specFor(id)
        runCatching {
            File(dir, "info.txt").writeText(spec?.lang ?: PiperVoiceManager.langFromId(id))
            File(dir, "sid").writeText((spec?.speakerId ?: 0).toString())
        }
    }
}
