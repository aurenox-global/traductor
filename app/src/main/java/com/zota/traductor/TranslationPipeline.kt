package com.zota.traductor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Bucle completo: AudioRecord 16k mono -> VAD -> segmentos -> Whisper (ASR) ->
 * **NLLB-200 (ONNX)** como ÚNICO motor de traducción -> UI.
 *
 * Variante NLLB puro: NO hay LLM (Qwen/llama.cpp) en el camino de traducción.
 * Si NLLB falla, se muestra el error claramente; nunca se carga un GGUF.
 */
class TranslationPipeline(
    private val ctx: Context,
    private val cb: Callbacks
) {

    interface Callbacks {
        fun onStatus(text: String)
        fun onListening(listening: Boolean)
        fun onSegment(original: String, detectedLang: String)   // texto ASR listo
        fun onPartial(translationSoFar: String)                 // streaming MT
        fun onTranslation(original: String, translated: String, targetLang: String)
        fun onError(msg: String)
    }

    companion object {
        private const val TAG = "Pipeline"
        const val SAMPLE_RATE = 16000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ASR/NLLB se serializan (un solo modelo cada uno)
    private val inferMutex = Mutex()

    @Volatile private var listening = false
    private var captureThread: Thread? = null
    private var record: AudioRecord? = null
    private var segmenter: VadSegmenter? = null
    private var vad: VadDetector? = null

    private var whisperHandle: Long = 0L
    private var loadedAsrPath: String? = null

    /** Único motor de traducción: NLLB-200 por ONNX Runtime. */
    private var nllb: NllbEngine? = null

    private fun nllbEngine(): NllbEngine = nllb ?: NllbEngine(ctx).also { nllb = it }

    private val cancelRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    private var sourceLang = Languages.DEFAULT_SOURCE
    private var targetLang = Languages.DEFAULT_TARGET
    private var ttsEnabled = true
    var tts: TtsRouter? = null

    /** Ultimo idioma detectado por Whisper (para el swap y para el fallback de `auto`). */
    @Volatile var lastDetectedLang: String = ""
        private set

    /**
     * Carga/recarga los modelos nativos si hace falta (bloqueante; llamar desde IO).
     * Relee la seleccion activa de ModelPrefs, de modo que cambiar de modelo en
     * Ajustes se aplica sin reiniciar.
     */
    fun loadModels(): Boolean {
        val asr: File? = ModelManager.resolveAsr(ctx)

        if (asr == null || !asr.isFile) { cb.onError("Falta el modelo de voz (Whisper)"); return false }

        // Traductor: solo los ONNX de NLLB. Sin LLM ni GGUF de traducción.
        if (!NllbModels.isReady(ctx)) {
            cb.onError("Faltan los modelos NLLB (encoder/decoder ONNX). Descárgalos en Ajustes.")
            return false
        }
        if (!WhisperBridge.ensureLoaded()) { cb.onError("No se pudo cargar libwhisperjni.so"); return false }

        // recarga si cambio el archivo activo
        if (whisperHandle != 0L && loadedAsrPath != asr.absolutePath) {
            WhisperBridge.nativeFree(whisperHandle); whisperHandle = 0L
        }

        cb.onStatus("Cargando Whisper (${asr.name})…")
        if (whisperHandle == 0L) {
            whisperHandle = WhisperBridge.nativeInit(asr.absolutePath, threadsForAsr(), false)
            if (whisperHandle != 0L) loadedAsrPath = asr.absolutePath
        }
        if (whisperHandle == 0L) { cb.onError("Whisper no pudo inicializar"); return false }

        cb.onStatus("Cargando NLLB-200 (ONNX)…")
        if (!nllbEngine().load()) { cb.onError("NLLB no pudo inicializar"); return false }

        cb.onStatus("Modelos listos (NLLB)")
        return true
    }

    private fun threadsForAsr(): Int = min(4, Runtime.getRuntime().availableProcessors())

    fun modelsLoaded(): Boolean =
        whisperHandle != 0L && nllb?.isLoaded() == true

    /**
     * Traduce un texto ya escrito (sin ASR), con idiomas reales.
     *
     * El troceado por frases y el presupuesto del contexto los gestiona el propio
     * [NllbEngine] internamente. Si NLLB no está cargado o falla, se informa por
     * [Callbacks.onError] y se devuelve cadena vacía (NO se cae a ningún LLM).
     */
    suspend fun translateText(text: String, source: String, target: String): String {
        val input = text.trim()
        if (input.isEmpty()) return ""

        return inferMutex.withLock {
            cancelRequested.set(false)
            lastCancelled = false
            untranslatedChunks = 0
            cb.onStatus("Traduciendo con NLLB-200…")

            val out = try {
                withContext(Dispatchers.Default) {
                    nllbEngine().translate(input, source, target)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "NLLB fallo: ${t.message}")
                cb.onError("La traducción NLLB falló: ${t.message ?: "error desconocido"}")
                return@withLock ""
            }

            if (out.isBlank()) {
                cb.onError("NLLB no produjo traducción (salida vacía). Revisa los ONNX en Ajustes.")
                return@withLock ""
            }

            // Trozos que NLLB no pudo traducir (marcador ⟦sin traducir⟧).
            untranslatedChunks = out.lines().count { it.contains(Prompts.UNTRANSLATED_MARKER) }
            out
        }
    }

    /** Nº de trozos que quedaron marcados como NO traducidos. Se resetea en cada [translateText]. */
    @Volatile var untranslatedChunks: Int = 0
        private set

    /** Pide cancelar la traducción en curso. (NLLB es síncrono: se respeta entre frases.) */
    fun cancelTranslation() {
        cancelRequested.set(true)
    }

    /** true si la última [translateText] se abortó por cancelación. */
    @Volatile var lastCancelled: Boolean = false
        private set

    fun start(source: String, target: String, ttsEnabled: Boolean) {
        if (listening) return
        this.sourceLang = source
        this.targetLang = target
        this.ttsEnabled = ttsEnabled

        if (!hasMicPermission()) { cb.onError("Permiso de micrófono no concedido"); return }

        val vadModel = ModelManager.fileFor(ctx, ModelManager.VAD)
        vad = if (vadModel.isFile && vadModel.length() > 1024) {
            try {
                cb.onStatus("VAD: Silero v5 (ONNX)")
                silero(vadModel.absolutePath)
            } catch (t: Throwable) {
                Log.w(TAG, "Silero no disponible (${t.message}); uso VAD por energía")
                cb.onStatus("VAD: energía (fallback)")
                EnergyVad()
            }
        } else {
            cb.onStatus("VAD: energía (modelo Silero ausente)")
            EnergyVad()
        }

        val seg = VadSegmenter(
            vad = vad!!,
            sampleRate = SAMPLE_RATE,
            frameSize = if (vad is SileroVad) SileroVad.FRAME else 512,
            onSegment = { pcm -> onSegmentDetected(pcm) }
        )
        segmenter = seg

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf * 2, SAMPLE_RATE / 2 * 2)

        var ar: AudioRecord? = null
        for (src in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            try {
                val candidate = AudioRecord(src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize)
                if (candidate.state == AudioRecord.STATE_INITIALIZED) { ar = candidate; break }
                candidate.release()
            } catch (t: Throwable) {
                Log.w(TAG, "AudioRecord src=$src fallo: ${t.message}")
            }
        }
        if (ar == null) { cb.onError("No se pudo inicializar el micrófono"); return }
        record = ar

        listening = true
        cb.onListening(true)

        captureThread = Thread({
            val tmp = ShortArray(1024)
            ar.startRecording()
            while (listening) {
                val n = ar.read(tmp, 0, tmp.size)
                if (n > 0) {
                    val f = FloatArray(n)
                    for (i in 0 until n) f[i] = tmp[i] / 32768.0f
                    try { seg.feed(f, n) } catch (t: Throwable) { Log.e(TAG, "feed fallo: ${t.message}") }
                }
            }
            try { ar.stop() } catch (_: Throwable) {}
            try { ar.release() } catch (_: Throwable) {}
        }, "audio-capture").also { it.start() }
    }

    private fun silero(path: String): VadDetector = SileroVad(path)

    private fun onSegmentDetected(pcm: FloatArray) {
        scope.launch {
            try {
                inferMutex.withLock {
                    if (pcm.size < SAMPLE_RATE / 4) return@withLock // < 0.25 s -> ruido

                    // Whisper: idioma forzado si el origen no es "auto"; si no, autodetecta.
                    val whisperLang = Languages.whisperCode(sourceLang)
                    val asr = withContext(Dispatchers.Default) {
                        WhisperBridge.nativeTranscribe(whisperHandle, pcm, whisperLang)
                    }
                    val detected = try {
                        WhisperBridge.nativeLastLanguage(whisperHandle)
                    } catch (_: Throwable) { "" }
                    if (detected.isNotBlank()) {
                        lastDetectedLang = detected
                        ModelPrefs.setLastDetected(ctx, detected)
                    }
                    val original = Prompts.cleanOutput(asr)
                    if (original.isBlank()) return@withLock
                    cb.onSegment(original, detected)

                    // El idioma origen real (detectado si era auto) guía a NLLB.
                    val effectiveSource = if (sourceLang == Languages.AUTO.code) {
                        detected.ifBlank { Languages.AUTO.code }
                    } else sourceLang

                    cb.onStatus("Traduciendo con NLLB-200…")
                    val translated = withContext(Dispatchers.Default) {
                        try {
                            nllbEngine().translate(original, effectiveSource, targetLang)
                        } catch (t: Throwable) {
                            Log.e(TAG, "NLLB fallo: ${t.message}")
                            ""
                        }
                    }
                    if (translated.isBlank()) {
                        cb.onError("NLLB no produjo traducción (salida vacía)")
                        return@withLock
                    }
                    cb.onTranslation(original, translated, targetLang)
                    if (ttsEnabled) {
                        try { tts?.speak(translated, targetLang) } catch (_: Throwable) {}
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "pipeline fallo: ${t.message}")
                cb.onError("Error de inferencia: ${t.message}")
            }
        }
    }

    fun stop() {
        listening = false
        try { record?.stop() } catch (_: Throwable) {}
        captureThread?.join(1500)
        captureThread = null
        try { segmenter?.flush() } catch (_: Throwable) {}
        try { vad?.close() } catch (_: Throwable) {}
        vad = null
        segmenter = null
        cb.onListening(false)
    }

    fun release() {
        stop()
        if (whisperHandle != 0L) { WhisperBridge.nativeFree(whisperHandle); whisperHandle = 0L }
        try { nllb?.close() } catch (_: Throwable) {}
        nllb = null
        scope.cancel()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}
