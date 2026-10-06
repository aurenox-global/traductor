package com.zota.traductor

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.sqrt

/** Detector de actividad de voz: probabilidad de habla en un frame. */
interface VadDetector {
    fun speechProbability(frame: FloatArray): Float
    fun reset() {}
    fun close() {}
}

/**
 * Silero VAD v5 via ONNX Runtime.
 * Frame obligatorio: 512 muestras @16kHz. Se antepone un contexto de 64 muestras.
 */
class SileroVad(private val modelPath: String) : VadDetector {

    companion object {
        private const val TAG = "SileroVad"
        const val FRAME = 512
        private const val CONTEXT = 64
        private const val SR = 16000L
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelPath, OrtSession.SessionOptions())
    private val context = FloatArray(CONTEXT)
    private var state: Array<Array<FloatArray>> = Array(2) { Array(1) { FloatArray(128) } }

    override fun speechProbability(frame: FloatArray): Float {
        val input = FloatArray(CONTEXT + frame.size)
        System.arraycopy(context, 0, input, 0, CONTEXT)
        System.arraycopy(frame, 0, input, CONTEXT, frame.size)
        if (frame.size >= CONTEXT) {
            System.arraycopy(frame, frame.size - CONTEXT, context, 0, CONTEXT)
        }

        val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, input.size.toLong()))
        val stateTensor = OnnxTensor.createTensor(env, state)
        val srTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(SR)), longArrayOf())

        try {
            session.run(mapOf("input" to inputTensor, "state" to stateTensor, "sr" to srTensor)).use { res ->
                val prob = ((res[0].value as Array<FloatArray>)[0])[0]
                @Suppress("UNCHECKED_CAST")
                val newState = res[1].value as Array<Array<FloatArray>>
                state = newState
                return prob
            }
        } catch (t: Throwable) {
            Log.e(TAG, "fallo inferencia silero: ${t.message}")
            return 0f
        } finally {
            inputTensor.close()
            stateTensor.close()
            srTensor.close()
        }
    }

    override fun reset() {
        context.fill(0f)
        state = Array(2) { Array(1) { FloatArray(128) } }
    }

    override fun close() {
        try { session.close() } catch (_: Throwable) {}
    }
}

/**
 * VAD ligera por energia con suelo de ruido adaptativo.
 * Fallback si el modelo Silero ONNX no esta disponible.
 */
class EnergyVad : VadDetector {
    private var noise = 0.0035f

    override fun speechProbability(frame: FloatArray): Float {
        var sum = 0.0
        for (s in frame) sum += (s * s).toDouble()
        val rms = sqrt(sum / frame.size).toFloat()

        // actualiza suelo de ruido solo en tramos claramente silenciosos
        if (rms < noise * 2.0f) {
            noise = 0.97f * noise + 0.03f * rms
        }
        val floor = (noise * 3.0f).coerceAtLeast(0.004f)
        val p = ((rms - floor) / (floor * 4f)).coerceIn(0f, 1f)
        return p
    }

    override fun reset() { noise = 0.0035f }
}

/**
 * Segmentador: alimenta muestras, dispara onSegment con cada utterance detectada.
 */
class VadSegmenter(
    private val vad: VadDetector,
    private val sampleRate: Int = 16000,
    private val frameSize: Int = 512,
    private val startThreshold: Float = 0.55f,
    private val endThreshold: Float = 0.30f,
    private val endSilenceMs: Int = 700,
    private val minSpeechMs: Int = 350,
    private val maxSpeechMs: Int = 15000,
    private val onSegment: (FloatArray) -> Unit
) {
    private var pending = FloatArray(frameSize * 8)
    private var pendingLen = 0
    private val segment = ArrayList<Float>(sampleRate * 2)

    private var inSpeech = false
    private var silenceSamples = 0
    private var speechSamples = 0
    private var preRoll = ArrayDeque<FloatArray>()

    private val framesPerEnd = (endSilenceMs * sampleRate / 1000) / frameSize
    private val framesPerMin = (minSpeechMs * sampleRate / 1000) / frameSize
    private val maxSamples = maxSpeechMs * sampleRate / 1000

    fun feed(samples: FloatArray, length: Int = samples.size) {
        if (pending.size < pendingLen + length) {
            val np = FloatArray(maxOf(pendingLen + length, pending.size * 2))
            System.arraycopy(pending, 0, np, 0, pendingLen)
            pending = np
        }
        System.arraycopy(samples, 0, pending, pendingLen, length)
        pendingLen += length

        var off = 0
        while (pendingLen - off >= frameSize) {
            val frame = FloatArray(frameSize)
            System.arraycopy(pending, off, frame, 0, frameSize)
            off += frameSize
            processFrame(frame)
        }
        val rem = pendingLen - off
        if (off > 0) {
            System.arraycopy(pending, off, pending, 0, rem)
            pendingLen = rem
        }
    }

    private fun processFrame(frame: FloatArray) {
        val p = vad.speechProbability(frame)

        if (!inSpeech) {
            // guarda algo de pre-roll para no cortar el inicio
            preRoll.addLast(frame)
            if (preRoll.size > 3) preRoll.removeFirst()

            if (p >= startThreshold) {
                inSpeech = true
                speechSamples = 0
                silenceSamples = 0
                segment.clear()
                // incluye pre-roll
                for (f in preRoll) for (s in f) segment.add(s)
                preRoll.clear()
                speechSamples = 3
            }
        } else {
            for (s in frame) segment.add(s)
            speechSamples++

            if (p < endThreshold) silenceSamples++ else silenceSamples = 0

            val tooLong = segment.size >= maxSamples
            val ended = silenceSamples >= framesPerEnd

            if (tooLong || ended) {
                if (speechSamples >= framesPerMin) {
                    val out = FloatArray(segment.size)
                    for (i in segment.indices) out[i] = segment[i]
                    onSegment(out)
                }
                inSpeech = false
                segment.clear()
                silenceSamples = 0
                speechSamples = 0
                preRoll.clear()
            }
        }
    }

    /** Fuerza el cierre del segmento en curso (al parar). */
    fun flush() {
        if (inSpeech && speechSamples >= framesPerMin) {
            val out = FloatArray(segment.size)
            for (i in segment.indices) out[i] = segment[i]
            onSegment(out)
        }
        inSpeech = false
        segment.clear()
        pendingLen = 0
        preRoll.clear()
    }

    fun reset() {
        flush()
        vad.reset()
    }
}
