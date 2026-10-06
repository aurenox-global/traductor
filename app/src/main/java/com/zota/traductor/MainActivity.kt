package com.zota.traductor

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zota.traductor.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * UI estilo Google Translate 100% offline:
 *  - barra con idioma origen ⇄ destino (+ detección automática)
 *  - panel de entrada editable + panel de traducción
 *  - micrófono con VAD, TTS, copiar/pegar/limpiar
 *  - traducción al escribir (con debounce) y botón Traducir
 *  - ajustes de modelos (descarga / importación) e historial local
 */
class MainActivity : AppCompatActivity(), TranslationPipeline.Callbacks {

    private lateinit var b: ActivityMainBinding
    private lateinit var pipeline: TranslationPipeline
    private val tts = TtsRouter(this)

    /** Motor OCR perezoso (las sesiones ONNX se crean solo cuando se usa la cámara). */
    private var ocrEngine: OcrEngine? = null
    private var pendingCameraUri: Uri? = null

    private var listening = false
    private var busy = false
    private var suppressWatcher = false
    private var lastTranslatedKey: String? = null

    private var source: Lang = Languages.AUTO
    private var target: Lang = Languages.byCode(Languages.DEFAULT_TARGET)

    private val handler = Handler(Looper.getMainLooper())
    private val debounce = Runnable { autoTranslate() }

    private val micPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) ensureModelsThenListen() else setStatus(getString(R.string.status_mic_denied))
        }

    private val historyLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode != RESULT_OK) return@registerForActivityResult
            val d = res.data ?: return@registerForActivityResult
            val original = d.getStringExtra(HistoryActivity.EXTRA_ORIGINAL) ?: return@registerForActivityResult
            val translated = d.getStringExtra(HistoryActivity.EXTRA_TRANSLATED) ?: ""
            val s = d.getStringExtra(HistoryActivity.EXTRA_SOURCE) ?: Languages.DEFAULT_SOURCE
            val t = d.getStringExtra(HistoryActivity.EXTRA_TARGET) ?: Languages.DEFAULT_TARGET
            setSource(s); setTarget(t)
            suppressWatcher = true
            b.editInput.setText(original)
            b.editInput.setSelection(b.editInput.text?.length ?: 0)
            suppressWatcher = false
            showOutput(translated)
            setStatus(getString(R.string.status_idle))
        }

    private val cameraPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchCamera() else setStatus(getString(R.string.status_camera_denied))
        }

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val uri = pendingCameraUri
            pendingCameraUri = null
            if (ok && uri != null) runOcr(uri) else setStatus(getString(R.string.status_idle))
        }

    private val galleryLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) runOcr(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        pipeline = TranslationPipeline(this, this)
        pipeline.tts = tts

        source = Languages.byCode(ModelPrefs.lastSource(this))
        target = Languages.byCode(ModelPrefs.lastTarget(this))
        if (target.code == Languages.AUTO.code) target = Languages.byCode(Languages.DEFAULT_TARGET)
        renderLangChips()

        b.switchAutoTts.isChecked = ModelPrefs.autoTts(this)

        b.btnSourceLang.setOnClickListener { pickLanguage(isSource = true) }
        b.btnTargetLang.setOnClickListener { pickLanguage(isSource = false) }
        b.btnSwap.setOnClickListener { swapLanguages() }
        b.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        b.btnHistory.setOnClickListener {
            historyLauncher.launch(Intent(this, HistoryActivity::class.java))
        }

        b.btnTranslate.setOnClickListener { translateNowManual() }
        b.btnClearAll.setOnClickListener { clearAll() }
        b.btnClearInput.setOnClickListener { clearInput() }

        b.btnMic.setOnClickListener { toggleListening() }
        b.btnCamera.setOnClickListener { chooseImage() }
        b.btnSpeakInput.setOnClickListener {
            val t = b.editInput.text?.toString().orEmpty()
            if (t.isNotBlank()) tts.speak(t, Languages.localeTag(sourceOrDetected()))
            else setStatus("Nada que leer")
        }
        b.btnSpeakOutput.setOnClickListener {
            val t = currentOutput()
            if (t.isNotBlank()) tts.speak(t, Languages.localeTag(target.code))
        }
        b.btnCopyInput.setOnClickListener { copyToClipboard(b.editInput.text?.toString().orEmpty()) }
        b.btnCopyOutput.setOnClickListener { copyToClipboard(currentOutput()) }
        b.btnPaste.setOnClickListener { pasteFromClipboard() }

        b.switchAutoTts.setOnCheckedChangeListener { _, checked ->
            ModelPrefs.setAutoTts(this, checked)
        }

        b.editInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateCount()
                if (suppressWatcher) return
                handler.removeCallbacks(debounce)
                val text = s?.toString()?.trim().orEmpty()
                if (text.length >= 2) handler.postDelayed(debounce, 700)
            }
        })

        tts.init { ok -> if (!ok) setStatus("TTS no disponible") }

        lifecycleScope.launch { ensureModels() }
    }

    // ---------------- idiomas ----------------

    private fun sourceOrDetected(): String {
        if (source.code != Languages.AUTO.code) return source.code
        val d = pipeline.lastDetectedLang.ifBlank { ModelPrefs.lastDetected(this) ?: "" }
        return d.ifBlank { "en" }
    }

    private fun renderLangChips() {
        b.btnSourceLang.text = source.label
        b.btnTargetLang.text = target.label
    }

    private fun pickLanguage(isSource: Boolean) {
        val list = if (isSource) Languages.ALL else Languages.TARGETS
        val selected = if (isSource) source.code else target.code
        LanguagePickerDialog.show(this, list, selected) { lang ->
            if (isSource) setSource(lang.code) else setTarget(lang.code)
            lastTranslatedKey = null
            handler.removeCallbacks(debounce)
            if (b.editInput.text?.toString()?.trim()?.length ?: 0 > 0) translateNowManual()
        }
    }

    private fun setSource(code: String) {
        source = Languages.byCode(code)
        ModelPrefs.setLastSource(this, source.code)
        renderLangChips()
    }

    private fun setTarget(code: String) {
        target = Languages.byCode(code)
        if (target.code == Languages.AUTO.code) target = Languages.byCode(Languages.DEFAULT_TARGET)
        ModelPrefs.setLastTarget(this, target.code)
        renderLangChips()
    }

    private fun swapLanguages() {
        val newTargetCode = if (source.code == Languages.AUTO.code) sourceOrDetected() else source.code
        val newSourceCode = target.code
        setSource(newSourceCode)
        setTarget(newTargetCode)
        lastTranslatedKey = null
        handler.removeCallbacks(debounce)
        if (b.editInput.text?.toString()?.trim()?.length ?: 0 > 0) translateNowManual()
    }

    // ---------------- traduccion de texto ----------------

    private fun autoTranslate() {
        val text = b.editInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        translate(text, auto = true)
    }

    private fun translateNowManual() {
        handler.removeCallbacks(debounce)
        val text = b.editInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) { setStatus("Escribe algo para traducir"); return }
        translate(text, auto = false)
    }

    private fun translate(text: String, auto: Boolean) {
        val key = "$text|${source.code}|${target.code}"
        if (auto && key == lastTranslatedKey) return
        if (busy) return
        busy = true
        lastTranslatedKey = key
        setBusyUi(true, getString(R.string.status_translating))

        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (pipeline.modelsLoaded()) true else pipeline.loadModels()
            }
            if (!ok) {
                setBusyUi(false, getString(R.string.status_missing_models))
                busy = false
                return@launch
            }
            val translated = withContext(Dispatchers.IO) {
                pipeline.translateText(text, source.code, target.code)
            }
            if (translated.isNotBlank()) {
                showOutput(translated)
                HistoryStore.add(
                    this@MainActivity,
                    HistEntry(text, translated, source.code, target.code, System.currentTimeMillis())
                )
                if (b.switchAutoTts.isChecked) tts.speak(translated, Languages.localeTag(target.code))
                setStatus(getString(R.string.status_idle))
            } else {
                setStatus("Sin salida del modelo")
            }
            setBusyUi(false, b.txtStatus.text?.toString() ?: "")
            busy = false
        }
    }

    // ---------------- escucha (VAD + Whisper) ----------------

    private fun toggleListening() {
        if (listening) stopListening() else {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) ensureModelsThenListen() else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun ensureModelsThenListen() {
        if (busy || listening) return
        busy = true
        setBusyUi(true, getString(R.string.status_loading))
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { pipeline.loadModels() }
            busy = false
            setBusyUi(false, "")
            if (ok) startListening()
        }
    }

    private fun startListening() {
        if (listening) return
        pipeline.start(source.code, target.code, b.switchAutoTts.isChecked)
        listening = true
        b.btnMic.setColorFilter(ContextCompat.getColor(this, R.color.danger))
        setStatus(getString(R.string.status_listening))
    }

    private fun stopListening() {
        if (!listening) return
        pipeline.stop()
        listening = false
        b.btnMic.clearColorFilter()
        b.btnMic.setImageResource(R.drawable.ic_mic)
        setStatus(getString(R.string.status_idle))
    }

    // ---------------- OCR de fotos ----------------

    private fun chooseImage() {
        val options = arrayOf(getString(R.string.ocr_take_photo), getString(R.string.ocr_pick_gallery))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ocr_choose_title)
            .setItems(options) { _, which ->
                if (which == 0) requestOrTakePhoto() else galleryLauncher.launch(arrayOf("image/*"))
            }
            .show()
    }

    private fun requestOrTakePhoto() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) launchCamera() else cameraPermLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun launchCamera() {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "ocr_${System.currentTimeMillis()}.jpg")
        val uri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (t: Throwable) {
            setStatus(t.message ?: getString(R.string.status_ocr_error)); return
        }
        pendingCameraUri = uri
        try {
            takePictureLauncher.launch(uri)
        } catch (_: ActivityNotFoundException) {
            pendingCameraUri = null
            setStatus(getString(R.string.status_camera_missing))
        }
    }

    /**
     * Carga la imagen (downscale + EXIF), asegura los modelos OCR y reconoce el texto.
     * El resultado va al panel de entrada (editable) y se traduce con el pipeline normal.
     */
    private fun runOcr(uri: Uri) {
        if (busy) return
        busy = true
        setBusyUi(true, getString(R.string.status_ocr_loading))
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { ImageLoad.load(this@MainActivity, uri) }
            if (bmp == null) {
                setBusyUi(false, getString(R.string.status_ocr_error)); busy = false; return@launch
            }
            val ready = withContext(Dispatchers.IO) {
                if (OcrModels.isReady(this@MainActivity)) true
                else {
                    runOnUiThread { b.progressDownload.visibility = View.VISIBLE }
                    setStatus(getString(R.string.status_ocr_models))
                    val ok = try {
                        OcrModels.ensureReady(
                            this@MainActivity,
                            onStatus = { s -> runOnUiThread { setStatus(s) } },
                            onProgress = { done, total -> runOnUiThread { setDownloadProgress(done, total) } }
                        )
                    } catch (t: Throwable) {
                        runOnUiThread { setStatus("OCR: ${t.message}") }; false
                    }
                    runOnUiThread { b.progressDownload.visibility = View.GONE }
                    ok
                }
            }
            if (!ready) {
                bmp.recycle()
                setBusyUi(false, getString(R.string.status_ocr_models)); busy = false; return@launch
            }
            val text = withContext(Dispatchers.IO) {
                val engine = ocrEngine ?: OcrEngine(this@MainActivity).also { ocrEngine = it }
                try {
                    engine.recognize(bmp) { s -> runOnUiThread { setStatus(s) } }.text
                } catch (t: Throwable) {
                    runOnUiThread { setStatus("OCR: ${t.message}") }
                    ""
                }
            }
            bmp.recycle()
            setBusyUi(false, "")
            if (text.isBlank()) {
                setStatus(getString(R.string.status_ocr_empty)); busy = false; return@launch
            }
            // El texto reconocido es editable: se puede corregir antes de traducir.
            suppressWatcher = true
            b.editInput.setText(text)
            b.editInput.setSelection(b.editInput.text?.length ?: 0)
            suppressWatcher = false
            updateCount()
            lastTranslatedKey = null
            setStatus(getString(R.string.status_ocr_done))
            busy = false
            translateNowManual()
        }
    }

    // ---------------- modelos ----------------

    private suspend fun ensureModels() {
        // Variante FULL: copia una sola vez los modelos incluidos en el APK.
        if (BundledAssets.enabled && BundledAssets.available(this)) {
            setStatus("Copiando modelos incluidos en la APK…")
            b.progressDownload.visibility = View.VISIBLE
            withContext(Dispatchers.IO) {
                try {
                    BundledAssets.ensureAll(
                        this@MainActivity,
                        onStatus = { s -> runOnUiThread { setStatus(s) } },
                        onProgress = { done, total -> runOnUiThread { setDownloadProgress(done, total) } }
                    )
                    BundledAssets.applyDefaults(this@MainActivity)
                } catch (t: Throwable) {
                    runOnUiThread { setStatus("Fallo copiando modelos incluidos: ${t.message}") }
                }
            }
            b.progressDownload.visibility = View.GONE
        }

        val pending = ModelManager.FIRST_RUN.filter { !ModelManager.isPresent(this, it) } +
            listOfNotNull(ModelManager.VAD.takeIf { !ModelManager.isPresent(this, it) })
        if (pending.isEmpty()) { setStatus(getString(R.string.status_models_ready)); return }

        setStatus(getString(R.string.status_downloading))
        b.progressDownload.visibility = View.VISIBLE
        withContext(Dispatchers.IO) {
            for (spec in pending) {
                try {
                    runOnUiThread { setStatus("Descargando ${spec.label} …") }
                    ModelManager.download(this@MainActivity, spec) { done, total ->
                        runOnUiThread { setDownloadProgress(done, total) }
                    }
                } catch (t: Throwable) {
                    runOnUiThread { setStatus("Fallo descargando ${spec.label}: ${t.message}") }
                    return@withContext
                }
            }
        }
        b.progressDownload.visibility = View.GONE
        setStatus(getString(R.string.status_download_done))
    }

    // ---------------- UI helpers ----------------

    private fun showOutput(text: String) {
        b.txtOutput.text = text.ifBlank { getString(R.string.output_hint) }
    }

    private fun currentOutput(): String {
        val t = b.txtOutput.text?.toString().orEmpty()
        return if (t == getString(R.string.output_hint)) "" else t
    }

    private fun clearInput() {
        suppressWatcher = true
        b.editInput.setText("")
        suppressWatcher = false
        updateCount()
        handler.removeCallbacks(debounce)
    }

    private fun clearAll() {
        clearInput()
        showOutput("")
        lastTranslatedKey = null
        setStatus(getString(R.string.status_idle))
    }

    private fun updateCount() {
        b.txtCount.text = (b.editInput.text?.length ?: 0).toString()
    }

    private fun copyToClipboard(text: String) {
        if (text.isBlank()) { setStatus(getString(R.string.status_clipboard_empty)); return }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("traduccion", text))
        setStatus(getString(R.string.status_copied))
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else ""
        if (text.isBlank()) { setStatus(getString(R.string.status_clipboard_empty)); return }
        suppressWatcher = true
        b.editInput.setText(text)
        b.editInput.setSelection(b.editInput.text?.length ?: 0)
        suppressWatcher = false
        updateCount()
        setStatus(getString(R.string.status_pasted))
        lastTranslatedKey = null
        translateNowManual()
    }

    private fun setBusyUi(showSpinner: Boolean, status: String) {
        b.progressTranslate.visibility = if (showSpinner) View.VISIBLE else View.GONE
        if (status.isNotBlank()) setStatus(status)
    }

    private fun setStatus(text: String) = runOnUiThread { b.txtStatus.text = text }

    private fun setDownloadProgress(done: Long, total: Long) {
        if (total > 0) {
            b.progressDownload.progress = ((done * 1000) / total).toInt().coerceIn(0, 1000)
            b.txtStatus.text = "Descargando… ${ModelManager.human(done)} / ${ModelManager.human(total)}"
        } else {
            b.txtStatus.text = "Descargando… ${ModelManager.human(done)}"
        }
    }

    // ---------------- Callbacks del pipeline ----------------

    override fun onStatus(text: String) = setStatus(text)

    override fun onListening(isListening: Boolean) {
        runOnUiThread {
            listening = isListening
            if (isListening) b.btnMic.setColorFilter(ContextCompat.getColor(this, R.color.danger))
            else b.btnMic.clearColorFilter()
        }
    }

    override fun onSegment(original: String, detectedLang: String) {
        runOnUiThread {
            suppressWatcher = true
            b.editInput.setText(original)
            b.editInput.setSelection(b.editInput.text?.length ?: 0)
            suppressWatcher = false
            updateCount()
            val tag = if (detectedLang.isNotBlank()) " ($detectedLang)" else ""
            setStatus("ASR$tag: $original")
        }
    }

    override fun onPartial(translationSoFar: String) {
        runOnUiThread { if (translationSoFar.isNotBlank()) showOutput(translationSoFar) }
    }

    override fun onTranslation(original: String, translated: String, targetLang: String) {
        runOnUiThread {
            showOutput(translated)
            HistoryStore.add(
                this,
                HistEntry(original, translated, source.code, targetLang, System.currentTimeMillis())
            )
            setStatus(getString(R.string.status_idle))
        }
    }

    override fun onError(msg: String) = runOnUiThread { setStatus("⚠ $msg") }

    override fun onDestroy() {
        handler.removeCallbacks(debounce)
        super.onDestroy()
        try { pipeline.release() } catch (_: Throwable) {}
        try { ocrEngine?.close() } catch (_: Throwable) {}
        ocrEngine = null
        tts.shutdown()
    }
}
