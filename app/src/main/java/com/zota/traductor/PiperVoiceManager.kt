package com.zota.traductor

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Gestión de voces Piper (TTS neuronal offline) para sherpa-onnx.
 *
 * Fuente PRINCIPAL de descarga: los paquetes oficiales de sherpa-onnx
 * `vits-piper-<id>.tar.bz2` (ya traen `.onnx` + `.onnx.json` + `tokens.txt` +
 * `espeak-ng-data/`), servidos desde GitHub Releases. Si el paquete fallara, se
 * recurre como respaldo a las voces crudas de rhasspy/piper-voices (conversión
 * on-device a tokens.txt + metadata).
 *
 * También permite **importar** un `.tar.bz2` (o `.tar`/`.tgz`) desde el
 * almacenamiento (SAF), o los ficheros sueltos `.onnx` + `.onnx.json` + `tokens.txt`.
 *
 * Las voces NO se empaquetan en el APK: se descargan o importan.
 */
object PiperVoiceManager {

    private const val TAG = "PiperVoiceManager"

    /** Carpeta raíz de las voces dentro de filesDir. */
    const val DIR_VOICES = "piper_voices"

    /** Subcarpeta de las voces importadas por el usuario. */
    const val DIR_IMPORTED = "imported"

    private const val ASSET_ESPEAK = "espeak-ng-data.zip"
    private const val ESPEAK_DIR = "espeak-ng-data"

    private const val HF = "https://huggingface.co/rhasspy/piper-voices/resolve/main"

    /** Releases de sherpa-onnx con los paquetes Piper listos para usar. */
    private const val SHERPA_TTS = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

    /** Excepción de descarga cancelada por el usuario. */
    class Cancelled(message: String = "Descarga cancelada") : Exception(message)

    /**
     * Voz Piper del catálogo. `id` es el nombre base del modelo
     * (p.ej. `es_AR-daniela-high`).
     *  - `tarUrl`: paquete oficial sherpa-onnx (`vits-piper-<id>.tar.bz2`) — fuente principal.
     *  - `onnxUrl`/`jsonUrl`: voces crudas de rhasspy — respaldo si el paquete falla.
     */
    data class Spec(
        val id: String,
        val label: String,
        val lang: String,
        val tarUrl: String,
        val onnxUrl: String,
        val jsonUrl: String,
        val approxBytes: Long,
        val speakerId: Int = 0,
        val gender: String = "F"
    )

    /** Voz instalada y lista para usar. */
    data class Voice(
        val id: String,
        val label: String,
        val lang: String,
        val dir: File,
        val speakerId: Int = 0
    ) {
        val onnx: File get() = File(dir, "model.onnx")
        val tokens: File get() = File(dir, "tokens.txt")

        /** `espeak-ng-data` propio de la voz (extraído del paquete), si existe. */
        val espeak: File get() = File(dir, "espeak-ng-data")
    }

    /** `path` es la ruta dentro de la repo piper-voices y `file` el nombre base del modelo. */
    private fun spec(
        id: String,
        label: String,
        lang: String,
        path: String,
        file: String,
        approxBytes: Long,
        speakerId: Int = 0,
        gender: String = "F"
    ): Spec {
        check(file == id) { "id ($id) != file ($file)" }
        return Spec(
            id = id,
            label = label,
            lang = lang,
            tarUrl = "$SHERPA_TTS/vits-piper-$file.tar.bz2",
            onnxUrl = "$HF/$path/$file.onnx",
            jsonUrl = "$HF/$path/$file.onnx.json",
            approxBytes = approxBytes,
            speakerId = speakerId,
            gender = gender
        )
    }

    /**
     * Voz SIN paquete oficial de sherpa-onnx (esa URL da HTTP 404): se descargan los
     * ficheros CRUDOS de rhasspy (`<file>.onnx` + `.onnx.json`) y se convierten
     * on-device (`convert()`). `tarUrl` queda vacío para no intentar un paquete que no
     * existe; `download()` salta directo al camino crudo.
     */
    private fun rawSpec(
        id: String,
        label: String,
        lang: String,
        path: String,
        file: String,
        approxBytes: Long,
        speakerId: Int = 0,
        gender: String = "F"
    ): Spec {
        check(file == id) { "id ($id) != file ($file)" }
        return Spec(
            id = id,
            label = label,
            lang = lang,
            tarUrl = "",
            onnxUrl = "$HF/$path/$file.onnx",
            jsonUrl = "$HF/$path/$file.onnx.json",
            approxBytes = approxBytes,
            speakerId = speakerId,
            gender = gender
        )
    }

    /**
     * Catálogo de voces descargables. **TODAS FEMENINAS**, verificado empíricamente
     * midiendo el pitch (F0) del audio sintetizado con sherpa-onnx (femenino ≈ 165-250 Hz;
     * referencias: es_ES-davefx-medium = 117 Hz MASCULINA excluida, en_US-lessac-medium = 190 Hz):
     *
     *   es_AR-daniela-high      182 Hz
     *   es_MX-claude-high       190 Hz
     *   es_ES-sharvard-medium   202 Hz (hablante F = sid 1; es multi-speaker)
     *   en_US-hfc_female-medium 212 Hz
     *   en_US-lessac-medium     190 Hz
     *   fr_FR-siwis-medium      212 Hz
     *   de_DE-ramona-low        208 Hz
     *   de_DE-eva_k-x_low       174 Hz
     *   it_IT-paola-medium      193 Hz
     *   zh_CN-huayan-medium     202 Hz
     *
     * Añadidas en v0.7 (medidas con el mismo método):
     *   pl_PL-gosia-medium              206 Hz  femenina
     *   ko_KR-kss-medium                306 Hz  femenina
     *   hu_HU-anna-medium               184 Hz  femenina
     *   ja_JP-hi_fi_captain-medium      269 Hz  femenina (sid 0; el sid 1 = 159 Hz, masculino)
     *   ar_JO-kareem-medium             106 Hz  masculina (única voz disponible)
     *   tr_TR-dfki-medium               109 Hz  masculina (única voz disponible)
     *   bg_BG-dimitar-medium            113 Hz  masculina (única voz disponible)
     *   ro_RO-mihai-medium              130 Hz  masculina (única voz disponible)
     *
     * La primera voz de cada idioma es la preferida por defecto.
     * `approxBytes` = tamaño real del `.tar.bz2` en sherpa-onnx (o del `.onnx` crudo
     * cuando el idioma no tiene paquete oficial).
     */
    val CATALOG: List<Spec> = listOf(
        // ---- Español (femeninas) ----
        spec("es_AR-daniela-high", "Español (AR) · daniela · femenina (high)", "es", "es/es_AR/daniela/high", "es_AR-daniela-high", 115_562_134L),
        spec("es_MX-claude-high", "Español (MX) · claude · femenina (high)", "es", "es/es_MX/claude/high", "es_MX-claude-high", 67_207_890L),
        spec("es_ES-sharvard-medium", "Español (ES) · sharvard · femenina (speaker F)", "es", "es/es_ES/sharvard/medium", "es_ES-sharvard-medium", 80_318_184L, speakerId = 1),
        // ---- Inglés (femeninas) ----
        spec("en_US-hfc_female-medium", "Inglés (US) · hfc_female · femenina (medium)", "en", "en/en_US/hfc_female/medium", "en_US-hfc_female-medium", 67_228_166L),
        spec("en_US-lessac-medium", "Inglés (US) · lessac · femenina (medium)", "en", "en/en_US/lessac/medium", "en_US-lessac-medium", 67_230_653L),
        // ---- Francés (femenina) ----
        spec("fr_FR-siwis-medium", "Francés · siwis · femenina (medium)", "fr", "fr/fr_FR/siwis/medium", "fr_FR-siwis-medium", 67_207_459L),
        // ---- Alemán (femeninas) ----
        spec("de_DE-ramona-low", "Alemán · ramona · femenina (low)", "de", "de/de_DE/ramona/low", "de_DE-ramona-low", 67_084_795L),
        spec("de_DE-eva_k-x_low", "Alemán · eva_k · femenina (x_low, ligera)", "de", "de/de_DE/eva_k/x_low", "de_DE-eva_k-x_low", 26_521_242L),
        // ---- Italiano (femenina) ----
        spec("it_IT-paola-medium", "Italiano · paola · femenina (medium)", "it", "it/it_IT/paola/medium", "it_IT-paola-medium", 67_221_173L),
        // ---- Chino (femenina) ----
        spec("zh_CN-huayan-medium", "Chino · huayan · femenina (medium)", "zh", "zh/zh_CN/huayan/medium", "zh_CN-huayan-medium", 67_255_926L),
        // ---- Polaco (femenina) ----
        spec("pl_PL-gosia-medium", "Polaco · gosia · femenina (medium) ♀", "pl", "pl/pl_PL/gosia/medium", "pl_PL-gosia-medium", 67_211_182L),
        // ---- Árabe (solo hay voz masculina) ----
        spec("ar_JO-kareem-medium", "Árabe (JO) · kareem · masculina (medium) ♂", "ar", "ar/ar_JO/kareem/medium", "ar_JO-kareem-medium", 67_177_830L, gender = "M"),
        // ---- Turco (solo hay voz masculina) ----
        spec("tr_TR-dfki-medium", "Turco · dfki · masculina (medium) ♂", "tr", "tr/tr_TR/dfki/medium", "tr_TR-dfki-medium", 67_201_221L, gender = "M"),
        // ---- Coreano (femenina; SIN paquete sherpa -> crudo + convert) ----
        rawSpec("ko_KR-kss-medium", "Coreano · kss · femenina (medium) ♀", "ko", "ko/ko_KR/kss/medium", "ko_KR-kss-medium", 63_222_238L),
        // ---- Búlgaro (solo hay voz masculina; SIN paquete sherpa -> crudo + convert) ----
        rawSpec("bg_BG-dimitar-medium", "Búlgaro · dimitar · masculina (medium) ♂", "bg", "bg/bg_BG/dimitar/medium", "bg_BG-dimitar-medium", 63_222_114L, gender = "M"),
        // ---- Húngaro (femenina) ----
        spec("hu_HU-anna-medium", "Húngaro · anna · femenina (medium) ♀", "hu", "hu/hu_HU/anna/medium", "hu_HU-anna-medium", 67_167_701L),
        // ---- Rumano (solo hay voz masculina) ----
        spec("ro_RO-mihai-medium", "Rumano · mihai · masculina (medium) ♂", "ro", "ro/ro_RO/mihai/medium", "ro_RO-mihai-medium", 67_182_137L, gender = "M"),
        // ---- Japonés (femenina = speaker 0; SIN paquete sherpa -> crudo + convert) ----
        rawSpec("ja_JP-hi_fi_captain-medium", "Japonés · hi_fi_captain · femenina (medium, speaker F) ♀", "ja", "ja/ja_JP/hi_fi_captain/medium", "ja_JP-hi_fi_captain-medium", 76_753_970L, speakerId = 0)
    )

    fun specFor(id: String): Spec? = CATALOG.firstOrNull { it.id == id }

    // ---------------- cancelación de descargas ----------------

    @Volatile private var cancelRequested = false

    fun cancelDownload() { cancelRequested = true }

    private fun cancelled(): Boolean = cancelRequested

    private fun resetCancel() { cancelRequested = false }

    // ---------------- rutas ----------------

    fun voicesRoot(ctx: Context): File =
        File(ctx.filesDir, DIR_VOICES).apply { if (!exists()) mkdirs() }

    fun voiceDir(ctx: Context, id: String): File =
        File(voicesRoot(ctx), id).apply { if (!exists()) mkdirs() }

    fun espeakDir(ctx: Context): File = File(ctx.filesDir, ESPEAK_DIR)

    /** ¿Está la voz lista (modelo + tokens)? */
    fun isReady(dir: File): Boolean =
        File(dir, "model.onnx").let { it.isFile && it.length() > 1024 } &&
            File(dir, "tokens.txt").let { it.isFile && it.length() > 0 }

    /** ¿Tiene la voz sus propios datos de espeak-ng (extraídos del paquete)? */
    fun voiceHasEspeak(voice: Voice): Boolean =
        voice.espeak.isDirectory && (voice.espeak.list()?.isNotEmpty() == true)

    // ---------------- espeak-ng-data (assets -> filesDir) ----------------

    /**
     * Extrae `espeak-ng-data` del asset zip la primera vez (respaldo compartido por
     * las voces que no traen el suyo). Devuelve el directorio de datos de espeak.
     */
    fun ensureEspeakData(ctx: Context): File {
        val root = espeakDir(ctx)
        val marker = File(root, ".ok")
        if (marker.isFile) return root
        Log.i(TAG, "extrayendo espeak-ng-data desde assets…")
        ctx.assets.open(ASSET_ESPEAK).use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    val name = e.name
                    if (name.isNotBlank() && !name.contains("..") && !name.startsWith("/")) {
                        val out = File(ctx.filesDir, name)
                        if (e.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { fos -> zis.copyTo(fos) }
                        }
                    }
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }
        }
        marker.writeText("ok")
        return root
    }

    // ---------------- conversión (raw piper -> sherpa) ----------------

    /**
     * Prepara el contenido de `dir` (model.onnx + voice.onnx.json + tokens.txt).
     * Si NO hay `tokens.txt` válido lo genera desde `phoneme_id_map`; si tampoco hay
     * `.onnx.json` no hay nada que hacer. Añade la metadata piper al `.onnx`
     * **solo si no viene ya** (los paquetes sherpa ya la traen). Idempotente.
     * Devuelve true si la voz queda lista.
     */
    fun convert(dir: File): Boolean {
        val onnx = File(dir, "model.onnx")
        if (!onnx.isFile || onnx.length() < 1024) return false
        val jsonFile = File(dir, "voice.onnx.json")
        val tokens = File(dir, "tokens.txt")
        val metaMarker = File(dir, ".meta")

        if (!jsonFile.isFile && !tokens.isFile) return false

        if (jsonFile.isFile && !tokens.isFile) {
            runCatching {
                val obj = JSONObject(jsonFile.readText())
                val idMap = obj.getJSONObject("phoneme_id_map")
                val entries = ArrayList<Pair<String, Int>>(idMap.length())
                val it = idMap.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val arr = idMap.getJSONArray(k)
                    if (arr.length() > 0) entries.add(k to arr.getInt(0))
                }
                // sherpa-onnx no acepta tokens multi-codepoint (ko/ja usan p.ej. "aɪ").
                tokens.writeText(OnnxMeta.tokensFromPhonemeIdMap(entries, dropMultiCodepoint = true))
                Log.i(TAG, "tokens.txt generado (${entries.size} símbolos)")
            }.onFailure { Log.e(TAG, "no se pudo generar tokens.txt: ${it.message}") }
        }

        if (metaMarker.isFile) {
            // ya preparado
        } else if (OnnxMeta.hasPiperMetadata(onnx)) {
            metaMarker.writeText("ok") // el paquete ya viene convertido
        } else if (jsonFile.isFile) {
            runCatching {
                val obj = JSONObject(jsonFile.readText())
                val meta = OnnxMeta.piperMetadata(
                    languageEnglish = obj.optJSONObject("language")?.optString("name_english").orEmpty(),
                    espeakVoice = obj.getJSONObject("espeak").getString("voice"),
                    numSpeakers = obj.optInt("num_speakers", 1),
                    sampleRate = obj.getJSONObject("audio").getInt("sample_rate")
                )
                OnnxMeta.appendMetadata(onnx, meta)
                metaMarker.writeText("ok")
                Log.i(TAG, "metadata piper añadida al .onnx")
            }.onFailure { Log.e(TAG, "no se pudo añadir metadata: ${it.message}") }
        }
        // Modelo ya convertido (sin json): damos por buena la metadata existente.
        if (!jsonFile.isFile && tokens.isFile && !metaMarker.isFile) metaMarker.writeText("ok")
        return isReady(dir)
    }

    // ---------------- listado / descarga / import ----------------

    /**
     * Voces instaladas (catálogo completas + importadas). El idioma se toma del
     * prefijo del id (`es_ES-...` -> `es`) o del `info.txt` si existe.
     */
    fun installed(ctx: Context): List<Voice> {
        val root = voicesRoot(ctx)
        val dirs = (root.listFiles() ?: emptyArray()).filter { it.isDirectory && !it.name.startsWith(".") }
        val out = ArrayList<Voice>()
        for (d in dirs.sortedBy { it.name.lowercase() }) {
            if (!isReady(d)) continue
            val id = d.name
            val spec = specFor(id)
            val lang = spec?.lang ?: readLang(d) ?: langFromId(id)
            val label = spec?.label ?: id
            val sid = spec?.speakerId ?: readSid(d)
            out.add(Voice(id, label, lang, d, sid))
        }
        return out
    }

    private fun readLang(dir: File): String? =
        File(dir, "info.txt").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotBlank() }

    private fun readSid(dir: File): Int =
        File(dir, "sid").takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: 0

    fun langFromId(id: String): String = id.substringBefore('_').substringBefore('-').lowercase()

    /** Ruta del modelo descargado de fábrica (aún sin descargar). */
    fun specDir(ctx: Context, id: String): File = File(voicesRoot(ctx), id)

    /**
     * Instala una voz del catálogo. Intenta primero el paquete oficial
     * `vits-piper-<id>.tar.bz2` (streaming, sin conversión) y, si falla, cae a las
     * voces crudas de rhasspy (conversión on-device).
     * `onProgress(bytes, total)` y `onStage(texto)` para la UI.
     */
    fun download(
        ctx: Context,
        spec: Spec,
        onProgress: (Long, Long) -> Unit,
        onStage: (String) -> Unit = {}
    ): File {
        val dir = voiceDir(ctx, spec.id)
        if (isReady(dir)) return dir
        resetCancel()
        ensureSpace(ctx, spec.approxBytes)
        if (spec.tarUrl.isBlank()) {
            // Idiomas sin paquete oficial de sherpa: directo al .onnx crudo + convert().
            onStage("Descargando voz (rhasspy, conversión on-device)…")
            return try {
                downloadRaw(ctx, spec, dir, onProgress)
            } catch (c: Cancelled) {
                deleteDir(dir)
                throw c
            } catch (t: Throwable) {
                deleteDir(dir)
                throw RuntimeException("no se pudo instalar ${spec.id}: ${t.message}", t)
            }
        }
        return try {
            onStage("Descargando paquete Piper…")
            downloadTar(ctx, spec, dir, onProgress, onStage)
        } catch (c: Cancelled) {
            deleteDir(dir)
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "paquete tar falló (${t.message}); respaldo rhasspy")
            onStage("Respaldo: descarga cruda de rhasspy…")
            try {
                downloadRaw(ctx, spec, dir, onProgress)
            } catch (t2: Throwable) {
                deleteDir(dir)
                throw RuntimeException("no se pudo instalar ${spec.id}: ${t2.message}", t2)
            }
        }
    }

    /** Descarga el .tar.bz2 oficial, lo extrae en streaming y lo deja canónico. */
    private fun downloadTar(
        ctx: Context,
        spec: Spec,
        dir: File,
        onProgress: (Long, Long) -> Unit,
        onStage: (String) -> Unit
    ): File {
        val tarTmp = File(voicesRoot(ctx), ".${spec.id}.tar.bz2.part")
        runCatching { tarTmp.delete() }
        ModelManager.downloadTo(spec.tarUrl, tarTmp, onProgress) { cancelled() }
        if (cancelled()) { tarTmp.delete(); throw Cancelled() }

        val tmpDir = File(voicesRoot(ctx), ".tmp-${spec.id}")
        deleteDir(tmpDir)
        tmpDir.mkdirs()
        onStage("Extrayendo paquete…")
        val res = try {
            PiperTar.extractFile(tarTmp, tmpDir)
        } finally {
            tarTmp.delete()
        }
        Log.i(TAG, "extraído ${spec.id}: model=${res.model} json=${res.json} tokens=${res.tokens} espeakFiles=${res.espeakFiles} rechazados=${res.rejected}")
        if (!res.model || !res.tokens) {
            deleteDir(tmpDir)
            throw RuntimeException("paquete incompleto (model=${res.model}, tokens=${res.tokens})")
        }
        if (cancelled()) { deleteDir(tmpDir); throw Cancelled() }

        // Reemplazar el directorio de la voz por el extraído (atómico si es posible).
        deleteDir(dir)
        if (!tmpDir.renameTo(dir)) {
            tmpDir.copyRecursively(dir, overwrite = true)
            deleteDir(tmpDir)
        }
        markConvertedIfNeeded(dir)
        writeMetaFiles(ctx, spec, dir)
        onStage("Preparando voz…")
        if (!convert(dir)) throw RuntimeException("conversión Piper fallida para ${spec.id}")
        onProgress(1, 1)
        return dir
    }

    /** Respaldo: voces crudas rhasspy (.onnx + .onnx.json) + conversión on-device. */
    private fun downloadRaw(
        ctx: Context,
        spec: Spec,
        dir: File,
        onProgress: (Long, Long) -> Unit
    ): File {
        dir.mkdirs()
        val onnx = File(dir, "model.onnx")
        val json = File(dir, "voice.onnx.json")

        if (!json.isFile) {
            ModelManager.downloadTo(spec.jsonUrl, json, onProgress) { cancelled() }
        }
        if (!onnx.isFile || onnx.length() < 1024) {
            ModelManager.downloadTo(spec.onnxUrl, onnx, onProgress) { cancelled() }
        }
        writeMetaFiles(ctx, spec, dir)
        if (!convert(dir)) throw RuntimeException("conversión Piper fallida para ${spec.id}")
        return dir
    }

    /** Si el `.onnx` ya trae metadata piper (paquete sherpa), marca para no repetirla. */
    private fun markConvertedIfNeeded(dir: File) {
        val onnx = File(dir, "model.onnx")
        val marker = File(dir, ".meta")
        if (!marker.isFile && OnnxMeta.hasPiperMetadata(onnx)) marker.writeText("ok")
    }

    private fun writeMetaFiles(ctx: Context, spec: Spec?, dir: File) {
        val lang = spec?.lang ?: langFromId(dir.name.removePrefix("imp_"))
        File(dir, "info.txt").writeText(lang)
        File(dir, "sid").writeText((spec?.speakerId ?: 0).toString())
    }

    /** Comprueba espacio libre suficiente para el paquete + su extracción. */
    private fun ensureSpace(ctx: Context, tarBytes: Long) {
        val need = tarBytes * 2 + 32L * 1024 * 1024
        val free = try { StatFs(ctx.filesDir.path).availableBytes } catch (_: Throwable) { Long.MAX_VALUE }
        if (free < need) {
            throw RuntimeException(
                "Espacio insuficiente: quedan ${ModelManager.human(free)} y se necesitan ~${ModelManager.human(need)}"
            )
        }
    }

    /**
     * Importa una voz desde almacenamiento externo (SAF).
     *  - Un único `.tar.bz2`/`.tar.gz`/`.tgz`/`.tar` (paquete Piper/sherpa) → se extrae.
     *  - O selección múltiple con `.onnx` + `.onnx.json` (y opcional `tokens.txt`).
     */
    fun importFromUris(
        ctx: Context,
        uris: List<Uri>,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        onStage: (String) -> Unit = {}
    ): Voice {
        require(uris.isNotEmpty()) { "sin archivos" }

        val archiveUri = uris.firstOrNull { PiperTar.isArchive(ModelManager.displayName(ctx, it)) }
        if (archiveUri != null) return importArchive(ctx, archiveUri, onProgress, onStage)

        // --- importación clásica de ficheros sueltos ---
        val onnxUri = uris.firstOrNull {
            val n = ModelManager.displayName(ctx, it)
            n.endsWith(".onnx", true) && !n.endsWith(".json", true)
        } ?: uris.first()
        val base = ModelManager.displayName(ctx, onnxUri).removeSuffix(".onnx")
        val id = "imp_${base.take(48)}"
        val dir = voiceDir(ctx, id)

        var jsonUri: Uri? = null
        var tokensUri: Uri? = null
        for (u in uris) {
            val n = ModelManager.displayName(ctx, u)
            when {
                n.endsWith(".json", true) -> jsonUri = u
                n.equals("tokens.txt", true) -> tokensUri = u
            }
        }
        for (u in uris) {
            val n = ModelManager.displayName(ctx, u)
            if (n.endsWith(".onnx", true) && !n.endsWith(".json", true)) {
                copyUri(ctx, u, File(dir, "model.onnx"), onProgress)
            }
        }
        jsonUri?.let { copyUri(ctx, it, File(dir, "voice.onnx.json"), onProgress) }
        tokensUri?.let { copyUri(ctx, it, File(dir, "tokens.txt"), onProgress) }

        File(dir, "info.txt").writeText(langFromId(base))
        File(dir, "sid").writeText("0")
        markConvertedIfNeeded(dir)
        if (!convert(dir)) throw RuntimeException("voz importada inválida: falta .onnx.json o tokens.txt")
        return Voice(id, base, langFromId(base), dir, 0)
    }

    /** Importa un paquete comprimido (tar.bz2/tar.gz/tar) desde una Uri SAF. */
    private fun importArchive(
        ctx: Context,
        uri: Uri,
        onProgress: (Long, Long) -> Unit,
        onStage: (String) -> Unit
    ): Voice {
        val name = ModelManager.displayName(ctx, uri)
        val base = PiperTar.baseIdFromArchiveName(name)
        val id = if (specFor(base) != null) base else "imp_${base.take(48)}"
        val dir = voiceDir(ctx, id)
        val tmpDir = File(voicesRoot(ctx), ".tmp-imp-${id.take(40)}")
        deleteDir(tmpDir)
        tmpDir.mkdirs()

        onStage("Extrayendo paquete…")
        val res = ctx.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "no se pudo abrir $uri" }
            PiperTar.extract(input, tmpDir) { bytes -> onProgress(bytes, -1) }
        }
        if (!res.model || !res.tokens) {
            deleteDir(tmpDir)
            throw RuntimeException("paquete incompleto (model=${res.model}, tokens=${res.tokens})")
        }

        deleteDir(dir)
        if (!tmpDir.renameTo(dir)) {
            tmpDir.copyRecursively(dir, overwrite = true)
            deleteDir(tmpDir)
        }
        val spec = specFor(id)
        markConvertedIfNeeded(dir)
        writeMetaFiles(ctx, spec, dir)
        if (!convert(dir)) throw RuntimeException("voz importada inválida")
        onProgress(1, 1)
        return Voice(
            id = id,
            label = spec?.label ?: base,
            lang = spec?.lang ?: langFromId(base),
            dir = dir,
            speakerId = spec?.speakerId ?: 0
        )
    }

    /** Copia un contenido SAF (Uri) a un archivo destino concreto. */
    private fun copyUri(ctx: Context, uri: Uri, dest: File, onProgress: (Long, Long) -> Unit) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        ctx.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "no se pudo abrir $uri" }
            val total = runCatching {
                ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            }.getOrDefault(-1L)
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
        if (tmp.renameTo(dest)) return
        tmp.copyTo(dest, overwrite = true)
        tmp.delete()
    }

    fun delete(ctx: Context, voice: Voice): Boolean = deleteDir(voice.dir)

    fun deleteDir(dir: File): Boolean = try {
        dir.walkBottomUp().forEach { it.delete() }
        !dir.exists()
    } catch (_: Throwable) { false }

    // ---------------- resolución por idioma ----------------

    /**
     * Voz Piper a usar para `lang` (código ISO-639-1):
     *   1) la voz activa si su idioma coincide,
     *   2) cualquier voz instalada de ese idioma,
     *   3) null -> el llamante debe recurrir al TTS del sistema.
     */
    fun resolveForLang(ctx: Context, lang: String): Voice? {
        if (!ModelPrefs.piperEnabled(ctx)) return null
        val code = lang.lowercase().substringBefore('-')
        val all = installed(ctx)
        ModelPrefs.activePiperVoiceId(ctx)?.let { active ->
            all.firstOrNull { it.id == active && it.lang == code }?.let { return it }
        }
        return all.firstOrNull { it.lang == code }
    }
}
