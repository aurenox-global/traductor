package com.zota.traductor

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * TTS neuronal offline con Piper/VITS vía sherpa-onnx (libsherpa-onnx-jni.so,
 * que ya embebe onnxruntime + espeak-ng para arm64-v8a).
 *
 * No depende del motor TTS del sistema. Carga la voz a petición y reproduce con
 * AudioTrack. `espeak-ng-data` se extrae una sola vez de los assets.
 */
class PiperTts(private val ctx: Context) {

    companion object { private const val TAG = "PiperTts" }

    private var engine: OfflineTts? = null
    private var loadedKey: String? = null

    @Volatile private var track: AudioTrack? = null
    @Volatile private var stopRequested = false

    fun isLoadedFor(voice: PiperVoiceManager.Voice): Boolean =
        engine != null && loadedKey == voice.dir.absolutePath

    /** Carga (o reutiliza) el modelo de la voz. Devuelve false si no se puede. */
    @Synchronized
    fun ensureLoaded(voice: PiperVoiceManager.Voice): Boolean {
        val key = voice.dir.absolutePath
        if (engine != null && loadedKey == key) return true
        release()
        return try {
            // data_dir: preferimos el `espeak-ng-data` que trae la PROPIA voz
            // (extraído del paquete `vits-piper-*.tar.bz2`); si no existe, caemos al
            // asset global compartido.
            val dataDir = if (PiperVoiceManager.voiceHasEspeak(voice)) voice.espeak
            else PiperVoiceManager.ensureEspeakData(ctx)
            val cfg = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = File(voice.dir, "model.onnx").absolutePath,
                        tokens = File(voice.dir, "tokens.txt").absolutePath,
                        dataDir = dataDir.absolutePath
                    ),
                    numThreads = 2,
                    provider = "cpu"
                )
            )
            val e = OfflineTts(config = cfg)
            engine = e
            loadedKey = key
            Log.i(TAG, "voz Piper cargada: ${voice.id} @ ${e.sampleRate()} Hz · espeak=${dataDir.absolutePath}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "no se pudo cargar la voz ${voice.id}: ${t.message}")
            engine = null
            loadedKey = null
            false
        }
    }

    /** Sintetiza `text` y lo reproduce. Bloqueante: usar fuera del hilo de UI. */
    fun speak(text: String, voice: PiperVoiceManager.Voice, speed: Float = 1.0f): Boolean {
        if (text.isBlank()) return false
        if (!ensureLoaded(voice)) return false
        stopRequested = false
        val e = engine ?: return false
        val audio = e.generate(text, voice.speakerId, speed)
        val sr = if (audio.sampleRate > 0) audio.sampleRate else e.sampleRate()
        return play(audio.samples, sr)
    }

    private fun play(samples: FloatArray, sampleRate: Int): Boolean {
        if (samples.isEmpty()) return false
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val bufSize = maxOf(minBuf, 8192)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        try {
            t.play()
            var off = 0
            val chunk = 4096
            while (off < samples.size && !stopRequested) {
                val n = minOf(chunk, samples.size - off)
                val written = t.write(samples, off, n, AudioTrack.WRITE_BLOCKING)
                if (written < 0) break
                off += written
            }
            // Esperar a que el buffer termine de SONAR antes de parar: si no,
            // se descarta el final aún no reproducido y la frase se corta
            // ("¿Cuánto cuesta?" -> "¿Cuánto cuest").
            if (!stopRequested) {
                runCatching {
                    val totalFrames = samples.size // mono -> frames == nº de muestras
                    val deadline = System.currentTimeMillis() + 20_000
                    while (!stopRequested &&
                        t.playbackHeadPosition < totalFrames &&
                        System.currentTimeMillis() < deadline
                    ) {
                        Thread.sleep(10)
                    }
                }
                runCatching { t.stop() }
            }
        } catch (th: Throwable) {
            Log.e(TAG, "reproducción falló: ${th.message}")
        } finally {
            runCatching { t.release() }
            track = null
        }
        return true
    }

    fun stop() {
        stopRequested = true
        runCatching { track?.pause(); track?.flush() }
    }

    @Synchronized
    fun release() {
        stop()
        runCatching { engine?.release() }
        engine = null
        loadedKey = null
    }
}
