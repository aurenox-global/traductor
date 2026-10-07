package com.zota.traductor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zota.traductor.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Ajustes: descarga / importacion / seleccion de los modelos de voz (Whisper),
 * los ONNX de traduccion (NLLB-200), estado del VAD y gestion de voces Piper.
 *
 * Variante NLLB puro: no hay modelo de traduccion GGUF/LLM que gestionar.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private val tts by lazy { TtsRouter(this) }

    private val importWhisper =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            importUri(uri, ModelManager.DIR_WHISPER_IMPORTS, ModelManager.WHISPER_EXTS)
        }

    private val importPiper =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            importPiperVoices(uris)
        }

    /** Selecciona una CARPETA (ACTION_OPEN_DOCUMENT_TREE) con los modelos NLLB. */
    private val importNllbFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            importNllbFromTree(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }
        b.btnImportWhisper.setOnClickListener {
            importWhisper.launch(arrayOf("application/octet-stream", "*/*"))
        }
        b.btnImportPiper.setOnClickListener {
            importPiper.launch(arrayOf("application/octet-stream", "application/json", "*/*"))
        }
        b.btnImportNllb.setOnClickListener {
            setStatus(getString(R.string.nllb_import_choose))
            importNllbFolder.launch(null)
        }
        b.btnTestPiper.setOnClickListener { testPiper() }
        b.btnCancelPiper.setOnClickListener {
            PiperVoiceManager.cancelDownload()
            setStatus("Cancelando…")
        }
        b.switchPiper.isChecked = ModelPrefs.piperEnabled(this)
        b.switchPiper.setOnCheckedChangeListener { _, checked ->
            ModelPrefs.setPiperEnabled(this, checked)
        }

        // Motor de traducción fijo: NLLB-200 (ONNX). No hay nada que conmutar.

        b.btnDiagRefresh.setOnClickListener {
            b.txtDiag.text = SeedingLog.format(this)
            setStatus("Diagnóstico actualizado")
        }

        bindAbout()
        refresh()
    }

    /** Seccion "Acerca de" + credito del autor. */
    private fun bindAbout() {
        b.txtAboutApp.text = getString(R.string.app_name)
        b.txtAboutVersion.text = getString(
            R.string.settings_about_version,
            appVersionName(),
            getString(if (BundledAssets.enabled) R.string.variant_full else R.string.variant_lite)
        )
        val author = getString(R.string.settings_credit_author)
        val credit = getString(R.string.settings_credit, author)
        val sp = android.text.SpannableString(credit)
        val start = credit.indexOf(author)
        if (start >= 0) {
            sp.setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                start, start + author.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        b.txtCredit.text = sp
    }

    private fun appVersionName(): String = try {
        @Suppress("DEPRECATION")
        val info = packageManager.getPackageInfo(packageName, 0)
        info.versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------------- construccion de la lista ----------------

    private fun refresh() {
        b.containerAsr.removeAllViews()
        b.containerVad.removeAllViews()
        b.containerPiper.removeAllViews()

        val activeAsr = ModelPrefs.activeAsrPath(this)
        val effAsr = ModelManager.resolveAsr(this)?.absolutePath

        // --- NLLB (único motor de traducción: ONNX) ---
        refreshNllb()

        // --- Whisper ---
        for (spec in ModelManager.WHISPER_DOWNLOADS) {
            val file = ModelManager.fileFor(this, spec)
            addSpecRow(b.containerAsr, spec, file, isAsr = true,
                active = isActive(file, activeAsr, effAsr))
        }
        for (f in ModelManager.importedWhisper(this)) {
            addFileRow(b.containerAsr, f, imported = true,
                active = isActive(f, activeAsr, effAsr))
        }

        // --- VAD ---
        val vadFile = ModelManager.fileFor(this, ModelManager.VAD)
        addSpecRow(b.containerVad, ModelManager.VAD, vadFile, isAsr = false,
            active = ModelManager.isPresent(this, ModelManager.VAD), showUse = false)

        // --- Piper ---
        refreshPiper()

        // --- Diagnóstico offline (seeding) ---
        b.txtDiag.text = SeedingLog.format(this)

        setStatus("Modelo de voz activo: ${ModelManager.resolveAsr(this)?.name ?: "ninguno"}")
    }

    /** Filas de los ONNX de NLLB (encoder/decoder) + tokenizador. Descarga/import propia. */
    private fun refreshNllb() {
        b.containerNllb.removeAllViews()
        for (spec in NllbModels.ALL) {
            val file = NllbModels.fileFor(this, spec)
            val present = NllbModels.isPresent(this, spec)
            val detail = buildString {
                append(if (present) getString(R.string.settings_present) else getString(R.string.settings_missing))
                append(" · ")
                append(if (present) ModelManager.human(file.length()) else ModelManager.human(spec.approxBytes))
                if (present) append(" · ").append(file.absolutePath)
            }
            val row = inflateRow(b.containerNllb, spec.label, detail)
            row.findViewById<ImageButton>(R.id.rowDownload).visibility =
                if (present) View.GONE else View.VISIBLE
            row.findViewById<MaterialButton>(R.id.rowUse).visibility = View.GONE
            row.findViewById<ImageButton>(R.id.rowDelete).visibility =
                if (present) View.VISIBLE else View.GONE
            row.findViewById<ImageButton>(R.id.rowDownload).setOnClickListener { downloadNllb(spec) }
            row.findViewById<ImageButton>(R.id.rowDelete).setOnClickListener {
                confirmDelete(file, isAsr = false)
            }
            b.containerNllb.addView(row)
        }

        // Tokenizador: importado por el usuario o el que viaja en la APK.
        val tk = NllbModels.tokenizerFile(this)
        val tkImported = NllbModels.tokenizerImported(this)
        val tkDetail = if (tkImported) {
            getString(R.string.settings_imported) + " · " +
                ModelManager.human(tk.length()) + " · " + tk.absolutePath
        } else {
            getString(R.string.nllb_tokenizer_bundled)
        }
        val tkRow = inflateRow(b.containerNllb, getString(R.string.nllb_tokenizer_label), tkDetail)
        tkRow.findViewById<ImageButton>(R.id.rowDownload).visibility = View.GONE
        tkRow.findViewById<MaterialButton>(R.id.rowUse).visibility = View.GONE
        tkRow.findViewById<ImageButton>(R.id.rowDelete).visibility =
            if (tkImported) View.VISIBLE else View.GONE
        tkRow.findViewById<ImageButton>(R.id.rowDelete).setOnClickListener {
            confirmDeleteFile(tk)
        }
        b.containerNllb.addView(tkRow)
    }

    private fun downloadNllb(spec: ModelManager.ModelSpec) {
        showProgress()
        setStatus(getString(R.string.settings_downloading) + " " + spec.label)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    NllbModels.downloadOne(this@SettingsActivity, spec) { done, total ->
                        runOnUiThread { setProgress(done, total) }
                    }
                }
                setStatus("Descarga completada: ${spec.fileName}")
            } catch (t: Throwable) {
                setStatus("Error de descarga: ${t.message}")
            } finally {
                hideProgress()
                refresh()
            }
        }
    }

    // ---------------- Importación de modelos NLLB ----------------

    /**
     * Importa los modelos NLLB desde la carpeta elegida (`ACTION_OPEN_DOCUMENT_TREE`).
     * Clasifica los ficheros por nombre ([NllbImport.plan]); si falta alguno, avisa
     * y no copia nada. Si están todos, los copia a la carpeta NLLB con los nombres
     * que espera el motor y quedan como modelo activo (ya no se descargan).
     */
    private fun importNllbFromTree(treeUri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val files: List<Pair<String, Uri>> = try {
            listTreeFiles(treeUri)
        } catch (t: Throwable) {
            setStatus("Error leyendo la carpeta: ${t.message}")
            return
        }
        if (files.isEmpty()) {
            setStatus(getString(R.string.nllb_import_empty))
            return
        }

        val plan = NllbImport.plan(files.map { it.first })
        if (!plan.complete) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.import_nllb)
                .setMessage(getString(R.string.nllb_import_missing, NllbImport.missingLabels(plan)))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val uriByName: Map<String, Uri> = files.toMap()
        showProgress()
        setStatus(getString(R.string.nllb_import_start))
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    NllbModels.importAll(this@SettingsActivity, plan.matches, { uriByName.getValue(it) }) { done, total ->
                        runOnUiThread { setProgress(done, total) }
                    }
                }
                setStatus(getString(R.string.nllb_import_done))
            } catch (t: Throwable) {
                setStatus("Error importando NLLB: ${t.message}")
            } finally {
                hideProgress()
                refresh()
            }
        }
    }

    /** Lista (nombre, uri) de los ficheros de una carpeta elegida con SAF. */
    private fun listTreeFiles(treeUri: Uri): List<Pair<String, Uri>> {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val out = ArrayList<Pair<String, Uri>>()
        contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ), null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val id = c.getString(1) ?: continue
                val mime = c.getString(2)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                out += name to DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
            }
        }
        return out
    }

    private fun confirmDeleteFile(file: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_delete)
            .setMessage(file.name)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.settings_delete) { _, _ ->
                ModelManager.delete(file)
                refresh()
            }
            .show()
    }

    private fun refreshPiper() {
        val installedIds = PiperVoiceManager.installed(this).associateBy { it.id }
        val activeId = ModelPrefs.activePiperVoiceId(this)
        for (spec in PiperVoiceManager.CATALOG) {
            val dir = PiperVoiceManager.specDir(this, spec.id)
            val present = PiperVoiceManager.isReady(dir)
            val active = activeId == spec.id ||
                (activeId == null && installedIds.containsKey(spec.id) && spec.lang == "es")
            val detail = buildString {
                append(if (present) getString(R.string.settings_present) else getString(R.string.settings_missing))
                append(" · ")
                append(if (present) ModelManager.human(dirLength(dir)) else ModelManager.human(spec.approxBytes))
                if (present) append(" · ").append(dir.absolutePath)
            }
            val row = inflateRow(b.containerPiper, "${spec.label}${if (active) "  ✓" else ""}", detail)
            row.findViewById<ImageButton>(R.id.rowDownload).visibility =
                if (present) View.GONE else View.VISIBLE
            row.findViewById<MaterialButton>(R.id.rowUse).visibility =
                if (present && !active) View.VISIBLE else View.GONE
            row.findViewById<ImageButton>(R.id.rowDelete).visibility =
                if (present) View.VISIBLE else View.GONE
            row.findViewById<ImageButton>(R.id.rowDownload).setOnClickListener { downloadPiper(spec) }
            row.findViewById<MaterialButton>(R.id.rowUse).setOnClickListener {
                ModelPrefs.setActivePiperVoice(this, spec.id); refresh()
            }
            row.findViewById<ImageButton>(R.id.rowDelete).setOnClickListener {
                confirmDeletePiper(spec.id, PiperVoiceManager.specDir(this, spec.id))
            }
            b.containerPiper.addView(row)
        }
        for (voice in PiperVoiceManager.installed(this)) {
            if (PiperVoiceManager.specFor(voice.id) != null) continue // ya listada como catálogo
            val active = activeId == voice.id
            val detail = buildString {
                append(getString(R.string.settings_imported))
                append(" · ").append(ModelManager.human(dirLength(voice.dir)))
                append(" · ").append(voice.dir.absolutePath)
            }
            val row = inflateRow(b.containerPiper, "${voice.label}${if (active) "  ✓" else ""}", detail)
            row.findViewById<ImageButton>(R.id.rowDownload).visibility = View.GONE
            row.findViewById<MaterialButton>(R.id.rowUse).visibility =
                if (active) View.GONE else View.VISIBLE
            row.findViewById<ImageButton>(R.id.rowDelete).visibility = View.VISIBLE
            row.findViewById<MaterialButton>(R.id.rowUse).setOnClickListener {
                ModelPrefs.setActivePiperVoice(this, voice.id); refresh()
            }
            row.findViewById<ImageButton>(R.id.rowDelete).setOnClickListener {
                confirmDeletePiper(voice.id, voice.dir)
            }
            b.containerPiper.addView(row)
        }
        if (PiperVoiceManager.installed(this).isEmpty()) {
            val tv = TextView(this).apply {
                text = getString(R.string.piper_no_voices)
                setTextColor(0xFF9AA0A6.toInt())
                textSize = 12f
                setPadding(0, 8, 0, 0)
            }
            b.containerPiper.addView(tv)
        }
    }

    private fun dirLength(dir: File): Long {
        var n = 0L
        dir.listFiles()?.forEach { if (it.isFile) n += it.length() }
        return n
    }

    private fun isActive(file: File, activePath: String?, effective: String?): Boolean {
        val ap = activePath
        return if (ap != null) ap == file.absolutePath else effective == file.absolutePath
    }

    private fun addSpecRow(
        parent: LinearLayout,
        spec: ModelManager.ModelSpec,
        file: File,
        isAsr: Boolean,
        active: Boolean,
        showUse: Boolean = true
    ) {
        val present = ModelManager.isPresent(this, spec)
        val detail = buildString {
            append(if (present) getString(R.string.settings_present) else getString(R.string.settings_missing))
            append(" · ")
            append(if (present) ModelManager.human(file.length()) else ModelManager.human(spec.approxBytes))
            if (present) append(" · ").append(file.absolutePath)
        }
        val row = inflateRow(parent, "${spec.label}${if (active) "  ✓" else ""}", detail)
        row.findViewById<ImageButton>(R.id.rowDownload).visibility =
            if (present) View.GONE else View.VISIBLE
        row.findViewById<MaterialButton>(R.id.rowUse).visibility =
            if (showUse && present && !active) View.VISIBLE else View.GONE
        row.findViewById<ImageButton>(R.id.rowDelete).visibility =
            if (present) View.VISIBLE else View.GONE

        row.findViewById<ImageButton>(R.id.rowDownload).setOnClickListener {
            downloadSpec(spec)
        }
        row.findViewById<MaterialButton>(R.id.rowUse).setOnClickListener {
            if (isAsr) ModelPrefs.setActiveAsr(this, file)
            refresh()
        }
        row.findViewById<ImageButton>(R.id.rowDelete).setOnClickListener {
            confirmDelete(file, isAsr)
        }
        parent.addView(row)
    }

    private fun addFileRow(parent: LinearLayout, file: File, imported: Boolean, active: Boolean) {
        val detail = buildString {
            append(getString(if (imported) R.string.settings_imported else R.string.settings_present))
            append(" · ").append(ModelManager.human(file.length()))
            append(" · ").append(file.absolutePath)
        }
        val row = inflateRow(parent, "${file.name}${if (active) "  ✓" else ""}", detail)
        row.findViewById<ImageButton>(R.id.rowDownload).visibility = View.GONE
        row.findViewById<MaterialButton>(R.id.rowUse).visibility =
            if (active) View.GONE else View.VISIBLE
        row.findViewById<ImageButton>(R.id.rowDelete).visibility = View.VISIBLE

        row.findViewById<MaterialButton>(R.id.rowUse).setOnClickListener {
            ModelPrefs.setActiveAsr(this, file)
            refresh()
        }
        row.findViewById<ImageButton>(R.id.rowDelete).setOnClickListener {
            confirmDelete(file, isAsr = false)
        }
        parent.addView(row)
    }

    private fun inflateRow(parent: LinearLayout, title: String, detail: String): View {
        val row = layoutInflater.inflate(R.layout.item_model, parent, false)
        row.findViewById<TextView>(R.id.rowTitle).text = title
        row.findViewById<TextView>(R.id.rowDetail).text = detail
        return row
    }

    // ---------------- acciones ----------------

    private fun downloadSpec(spec: ModelManager.ModelSpec) {
        showProgress()
        setStatus(getString(R.string.settings_downloading) + " " + spec.label)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ModelManager.download(this@SettingsActivity, spec) { done, total ->
                        runOnUiThread { setProgress(done, total) }
                    }
                }
                // si no habia modelo activo, activamos el recien descargado (solo ASR)
                if (spec.id != ModelManager.VAD.id && ModelManager.WHISPER_DOWNLOADS.any { it.id == spec.id }) {
                    if (ModelPrefs.activeAsrPath(this@SettingsActivity) == null) {
                        ModelPrefs.setActiveAsr(this@SettingsActivity, ModelManager.fileFor(this@SettingsActivity, spec))
                    }
                }
                setStatus("Descarga completada: ${spec.fileName}")
            } catch (t: Throwable) {
                setStatus("Error de descarga: ${t.message}")
            } finally {
                hideProgress()
                refresh()
            }
        }
    }

    private fun importUri(uri: android.net.Uri, subdir: String, exts: List<String>) {
        val name = ModelManager.displayName(this, uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty() && !exts.contains(ext)) {
            setStatus("Extensión no esperada (.$ext). Se importará de todos modos.")
        }
        showProgress()
        setStatus("Importando $name …")
        lifecycleScope.launch {
            try {
                val dest = withContext(Dispatchers.IO) {
                    ModelManager.importFromUri(this@SettingsActivity, uri, name, subdir) { done, total ->
                        runOnUiThread { setProgress(done, total) }
                    }
                }
                ModelPrefs.setActiveAsr(this@SettingsActivity, dest)
                setStatus("Importado y activado: ${dest.name}")
            } catch (t: Throwable) {
                setStatus("Error importando: ${t.message}")
            } finally {
                hideProgress()
                refresh()
            }
        }
    }

    private fun confirmDelete(file: File, isAsr: Boolean) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_delete)
            .setMessage(file.name)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.settings_delete) { _, _ ->
                ModelManager.delete(file)
                if (ModelPrefs.activeAsrPath(this) == file.absolutePath) ModelPrefs.clearActiveAsr(this)
                refresh()
            }
            .show()
    }

    // ---------------- Piper ----------------

    private fun downloadPiper(spec: PiperVoiceManager.Spec) {
        showProgress()
        showPiperCancel(true)
        setStatus(getString(R.string.settings_downloading) + " " + spec.label)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    PiperVoiceManager.download(this@SettingsActivity, spec, { done, total ->
                        runOnUiThread { setProgress(done, total) }
                    }, { stage ->
                        runOnUiThread { setStatus("$stage ${spec.label}") }
                    })
                }
                if (ModelPrefs.activePiperVoiceId(this@SettingsActivity) == null) {
                    ModelPrefs.setActivePiperVoice(this@SettingsActivity, spec.id)
                }
                setStatus("Voz lista: ${spec.label}")
            } catch (c: PiperVoiceManager.Cancelled) {
                setStatus("Descarga cancelada: ${spec.id}")
            } catch (t: Throwable) {
                setStatus("Error descargando voz: ${t.message}")
            } finally {
                hideProgress(); showPiperCancel(false); refresh()
            }
        }
    }

    private fun importPiperVoices(uris: List<android.net.Uri>) {
        showProgress()
        setStatus("Importando voz Piper…")
        lifecycleScope.launch {
            try {
                val voice = withContext(Dispatchers.IO) {
                    PiperVoiceManager.importFromUris(this@SettingsActivity, uris, { done, total ->
                        runOnUiThread { setProgress(done, total) }
                    }, { stage ->
                        runOnUiThread { setStatus(stage) }
                    })
                }
                ModelPrefs.setActivePiperVoice(this@SettingsActivity, voice.id)
                setStatus("Voz importada y activada: ${voice.label}")
            } catch (t: Throwable) {
                setStatus("Error importando voz: ${t.message}")
            } finally {
                hideProgress(); refresh()
            }
        }
    }

    private fun confirmDeletePiper(id: String, dir: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_delete)
            .setMessage(id)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.settings_delete) { _, _ ->
                PiperVoiceManager.deleteDir(dir)
                if (ModelPrefs.activePiperVoiceId(this) == id) ModelPrefs.clearActivePiperVoice(this)
                refresh()
            }
            .show()
    }

    private fun testPiper() {
        val voices = PiperVoiceManager.installed(this)
        if (voices.isEmpty()) { setStatus(getString(R.string.piper_no_voices)); return }
        val edit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            hint = getString(R.string.piper_test_hint)
            setText("Buenos dias. Esta es una prueba de voz neuronal Piper, sin conexion.")
        }
        val pad = (resources.displayMetrics.density * 20).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
            addView(edit)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.piper_test)
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.cd_speak_test) { _, _ ->
                val text = edit.text?.toString().orEmpty()
                if (text.isBlank()) return@setPositiveButton
                val activeId = ModelPrefs.activePiperVoiceId(this)
                val lang = voices.firstOrNull { it.id == activeId }?.lang ?: voices.first().lang
                tts.speak(text, lang)
            }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.shutdown()
    }

    private fun setStatus(text: String) {
        runOnUiThread { b.txtSettingsStatus.text = text }
    }

    private fun showProgress() {
        runOnUiThread { b.progressSettings.visibility = View.VISIBLE }
    }

    private fun hideProgress() {
        runOnUiThread { b.progressSettings.visibility = View.GONE }
    }

    private fun showPiperCancel(show: Boolean) {
        runOnUiThread { b.btnCancelPiper.visibility = if (show) View.VISIBLE else View.GONE }
    }

    private fun setProgress(done: Long, total: Long) {
        b.progressSettings.progress =
            if (total > 0) ((done * 1000) / total).toInt().coerceIn(0, 1000) else 0
        b.txtSettingsStatus.text = "Descargando… ${ModelManager.human(done)}" +
            if (total > 0) " / ${ModelManager.human(total)}" else ""
    }
}
