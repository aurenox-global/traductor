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
import kotlin.math.min

/**
 * Bucle completo: AudioRecord 16k mono -> VAD -> segmentos -> Whisper (ASR) -> Qwen (MT) -> UI.
 * Los idiomas origen/destino se propagan de verdad: Whisper recibe el idioma forzado
 * (o autodetecta) y Qwen recibe "Traduce de <origen> a <destino>".
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

    // ASR/MT se serializan (un solo modelo cada uno)
    private val inferMutex = Mutex()

    @Volatile private var listening = false
    private var captureThread: Thread? = null
    private var record: AudioRecord? = null
    private var segmenter: VadSegmenter? = null
    private var vad: VadDetector? = null

    private var whisperHandle: Long = 0L
    private var llamaHandle: Long = 0L
    private var loadedAsrPath: String? = null
    private var loadedMtPath: String? = null

    private var sourceLang = Languages.DEFAULT_SOURCE
    private var targetLang = Languages.DEFAULT_TARGET
    private var ttsEnabled = true
    var tts: TtsRouter? = null

    /** Ultimo idioma detectado por Whisper (para el swap y para el prompt). */
    @Volatile var lastDetectedLang: String = ""
        private set

    /**
     * Carga/recarga los modelos nativos si hace falta (bloqueante; llamar desde IO).
     * Relee la seleccion activa de ModelPrefs, de modo que cambiar de modelo en
     * Ajustes se aplica sin reiniciar.
     */
    fun loadModels(): Boolean {
        val asr: File? = ModelManager.resolveAsr(ctx)
        val mt: File? = ModelManager.resolveMt(ctx)

        if (asr == null || !asr.isFile) { cb.onError("Falta el modelo de voz (Whisper)"); return false }
        if (mt == null || !mt.isFile) { cb.onError("Falta el modelo de traducción (Qwen)"); return false }

        if (!WhisperBridge.ensureLoaded()) { cb.onError("No se pudo cargar libwhisperjni.so"); return false }
        if (!LlamaBridge.ensureLoaded()) { cb.onError("No se pudo cargar libllamajni.so"); return false }

        // recarga si cambio el archivo activo
        if (whisperHandle != 0L && loadedAsrPath != asr.absolutePath) {
            WhisperBridge.nativeFree(whisperHandle); whisperHandle = 0L
        }
        if (llamaHandle != 0L && loadedMtPath != mt.absolutePath) {
            LlamaBridge.nativeFree(llamaHandle); llamaHandle = 0L
        }

        cb.onStatus("Cargando Whisper (${asr.name})…")
        if (whisperHandle == 0L) {
            whisperHandle = WhisperBridge.nativeInit(asr.absolutePath, threadsForAsr(), false)
            if (whisperHandle != 0L) loadedAsrPath = asr.absolutePath
        }
        if (whisperHandle == 0L) { cb.onError("Whisper no pudo inicializar"); return false }

        cb.onStatus("Cargando Qwen (${mt.name})…")
        if (llamaHandle == 0L) {
            llamaHandle = LlamaBridge.nativeInit(mt.absolutePath, threadsForMt(), 2048)
            if (llamaHandle != 0L) loadedMtPath = mt.absolutePath
        }
        if (llamaHandle == 0L) { cb.onError("Qwen no pudo inicializar"); return false }

        cb.onStatus("Modelos listos")
        return true
    }

    private fun threadsForAsr(): Int = min(4, Runtime.getRuntime().availableProcessors())
    private fun threadsForMt(): Int = min(4, Runtime.getRuntime().availableProcessors())

    fun modelsLoaded(): Boolean = whisperHandle != 0L && llamaHandle != 0L

    /** Traduce un texto ya escrito (sin ASR), con idiomas reales. */
    suspend fun translateText(text: String, source: String, target: String): String {
        if (llamaHandle == 0L) return ""
        return inferMutex.withLock {
            val system = Prompts.systemPrompt(target, source)
            val out = withContext(Dispatchers.Default) {
                LlamaBridge.nativeGenerate(llamaHandle, system, Prompts.userPrompt(text), 256, null)
            }
            Prompts.cleanOutput(out)
        }
    }

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

                    // El prompt usa el idioma origen real (detectado si era auto).
                    val effectiveSource = if (sourceLang == Languages.AUTO.code) {
                        detected.ifBlank { Languages.AUTO.code }
                    } else sourceLang

                    val system = Prompts.systemPrompt(targetLang, effectiveSource)
                    val user = Prompts.userPrompt(original)

                    val acc = StringBuilder()
                    var lastEmit = 0L
                    val full = withContext(Dispatchers.Default) {
                        LlamaBridge.nativeGenerate(llamaHandle, system, user, 256) { piece ->
                            acc.append(piece)
                            val now = System.currentTimeMillis()
                            if (now - lastEmit > 140) {
                                lastEmit = now
                                cb.onPartial(Prompts.cleanOutput(acc.toString()))
                            }
                        }
                    }
                    val translated = Prompts.cleanOutput(full)
                    cb.onTranslation(original, translated.ifBlank { "(sin salida)" }, targetLang)
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
        if (llamaHandle != 0L) { LlamaBridge.nativeFree(llamaHandle); llamaHandle = 0L }
        scope.cancel()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}
