package com.zota.traductor

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Motor de traducción **NLLB-200-distilled-600M** por ONNX Runtime.
 *
 * Sustituye al LLM (Qwen por llama.cpp) cuando el usuario elige el motor NLLB.
 * Usa el mismo `onnxruntime-android` que ya emplean el VAD Silero y el OCR.
 *
 * Es la misma tubería validada en el Mac (FASE 1, `scripts/nllb_onnx.py`):
 *
 *   encoder( input_ids, attention_mask ) -> last_hidden_state
 *   decoder merged: input_ids, encoder_hidden_states, encoder_attention_mask,
 *                   past_key_values.*, use_cache_branch -> logits + present.*
 *
 * Convenciones (M2M100/NLLB):
 *  - fuente:  [ <src_lang>, …tokens…, </s> ]
 *  - decoder: paso 0 -> [ </s>, <tgt_lang> ]; siguientes -> [ token_anterior ]
 *
 * Notas de implementación (aprendidas en la FASE 1):
 *  - El modelo "merged" de Optimum devuelve los `present.*.encoder.*` VACÍOS en
 *    la rama con caché; como la KV de cross-attention es constante, se conserva
 *    la del primer paso (si no, el decoder falla al reshapear).
 *  - NLLB con varias frases tiende a emitir </s> tras la primera y perder el
 *    resto, así que el texto se trocea por FRASES.
 *
 * Es una clase pesada (≈900 MB de modelos): úsala desde un hilo de IO y cierra
 * con [close] al terminar.
 */
class NllbEngine(private val ctx: Context) {

    companion object {
        private const val TAG = "NllbEngine"
        const val ASSET_TOKENIZER = "nllb/tokenizer.bin"
        private const val DECODER_LAYERS = 12
        private const val HEADS = 16
        private const val HEAD_DIM = 64
        private const val HIDDEN = 1024
        private const val EOS = 2
        private const val DECODER_START = 2
        private const val MAX_NEW_TOKENS = 256
        private const val MAX_CHARS_PER_CHUNK = 400
        private const val UNTRANSLATED_MARKER = Prompts.UNTRANSLATED_MARKER

        /**
         * ¿Un tensor `present.*` del decoder es inservible?
         *
         * El decoder *merged* de Optimum, en la rama con caché, devuelve los
         * `present.*.encoder.*` con **lote 0** (forma `(0,16,1,64)`): el eje
         * inservible es [0], no el 2. También tratamos como inservible el caso
         * de 0 tokens en el eje 2. En ambos casos hay que conservar la KV previa.
         */
        internal fun presentIsUnusable(shape: LongArray): Boolean =
            shape.isEmpty() || shape[0] == 0L || (shape.size >= 3 && shape[2] == 0L)

        /**
         * Una frase por trozo. NLLB, con varias frases en la entrada, emite </s>
         * tras la primera y descarta el resto (verificado en el Mac, FASE 1). Si
         * una frase excede [maxChars] se parte además por palabras.
         */
        fun splitSentences(text: String, maxChars: Int): List<String> {
            val sentences = ArrayList<String>()
            for (line in text.split('\n')) {
                val l = line.trim()
                if (l.isEmpty()) continue
                val sb = StringBuilder()
                var i = 0
                while (i < l.length) {
                    val c = l[i]
                    sb.append(c)
                    i++
                    val latinEnd = c == '.' || c == '!' || c == '?' || c == '…' || c == ':'
                    val cjkEnd = c == '\u3002' || c == '\uff01' || c == '\uff1f'  // 。！？
                    if (latinEnd || cjkEnd) {
                        var j = i
                        while (j < l.length && l[j].isWhitespace()) j++
                        // La puntuación latina exige espacio detrás (no corta "U.S." ni "3.14").
                        if (cjkEnd || j > i) {
                            val s = sb.toString().trim()
                            if (s.isNotEmpty()) sentences.add(s)
                            sb.setLength(0)
                            i = j
                        }
                    }
                }
                val rest = sb.toString().trim()
                if (rest.isNotEmpty()) sentences.add(rest)
            }

            // Trocea por palabras las frases que excedan el presupuesto.
            val units = ArrayList<String>(sentences.size)
            for (s in sentences) {
                if (s.length <= maxChars) {
                    units.add(s)
                } else {
                    var cur = ""
                    for (word in s.split(' ')) {
                        if (cur.isNotEmpty() && cur.length + 1 + word.length > maxChars) {
                            units.add(cur); cur = word
                        } else {
                            cur = if (cur.isEmpty()) word else "$cur $word"
                        }
                    }
                    if (cur.isNotEmpty()) units.add(cur)
                }
            }
            return units.ifEmpty { listOf(text) }
        }
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private var tokenizer: NllbTokenizer? = null

    /** Nombres de las 48 entradas de past y de las 48 salidas present (orden fijo). */
    private val pastNames: List<String> = buildList {
        val parts = listOf("decoder.key", "decoder.value", "encoder.key", "encoder.value")
        for (l in 0 until DECODER_LAYERS) for (p in parts) add("past_key_values.$l.$p")
    }
    private val presentNames: List<String> = pastNames.map { "present." + it.removePrefix("past_key_values.") }

    /** KV de cross-attention (constante tras el primer paso) por nombre. */
    private var encPast: HashMap<String, Pair<FloatArray, LongArray>> = HashMap()

    fun isLoaded(): Boolean = encoder != null && decoder != null && tokenizer != null

    /** Carga tokenizador + sesiones ONNX. Bloqueante. */
    fun load(): Boolean {
        if (isLoaded()) return true
        val encFile: File? = NllbModels.fileFor(ctx, NllbModels.ENCODER)
        val decFile: File? = NllbModels.fileFor(ctx, NllbModels.DECODER)
        if (encFile == null || !encFile.isFile || decFile == null || !decFile.isFile) {
            Log.w(TAG, "faltan los ONNX de NLLB (encoder/decoder)")
            return false
        }
        return try {
            ctx.assets.open(ASSET_TOKENIZER).use { tokenizer = NllbTokenizer.load(it) }

            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            }
            encoder = env.createSession(encFile.absolutePath, opts)
            decoder = env.createSession(decFile.absolutePath, opts)
            Log.i(TAG, "NLLB listo (${encFile.name}, ${decFile.name})")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "no se pudo cargar NLLB: ${t.message}")
            close()
            false
        }
    }

    /**
     * Traduce texto (posiblemente largo) de `srcIso` a `tgtIso` (ISO-639-1 o
     * código NLLB). Trocea por frases y las une con saltos de línea.
     */
    fun translate(text: String, srcIso: String, tgtIso: String): String {
        if (!isLoaded() && !load()) return ""
        val tk = tokenizer ?: return ""
        val input = text.trim()
        if (input.isEmpty()) return ""

        val srcLang = NllbLangs.toNllb(srcIso, fallback = NllbLangs.toNllb(
            ModelPrefs.lastDetected(ctx) ?: "en"))
        val tgtLang = NllbLangs.toNllb(tgtIso)
        val tgtId = tk.langId(tgtLang)

        val chunks = splitSentences(input, MAX_CHARS_PER_CHUNK)
        val out = ArrayList<String>(chunks.size)
        for (chunk in chunks) {
            val one = try {
                translateChunk(tk, chunk, srcLang, tgtId)
            } catch (t: Throwable) {
                Log.e(TAG, "fallo traduciendo trozo: ${t.message}")
                ""
            }
            out.add(one.ifBlank { "$UNTRANSLATED_MARKER $chunk" })
        }
        return out.joinToString("\n")
    }

    /** Traduce una frase suelta (una llamada a encoder + bucle greedy del decoder). */
    private fun translateChunk(tk: NllbTokenizer, text: String, srcLang: String,
                               tgtId: Int): String {
        val srcIds = tk.encodeSource(text, srcLang)
        val hidden = runEncoder(srcIds)
        val generated = greedyDecode(tk, hidden, srcIds.size, tgtId)
        return tk.decode(generated)
    }

    // ---------------- encoder ----------------

    private fun runEncoder(srcIds: LongArray): FloatArray {
        val enc = encoder ?: error("encoder sin cargar")
        val s = srcIds.size
        val mask = LongArray(s) { 1L }
        val inIds = OnnxTensor.createTensor(env, LongBuffer.wrap(srcIds), longArrayOf(1, s.toLong()))
        val inMask = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), longArrayOf(1, s.toLong()))
        try {
            enc.run(mapOf("input_ids" to inIds, "attention_mask" to inMask)).use { res ->
                val t = res.get("last_hidden_state").get() as OnnxTensor
                return copyFloats(t)
            }
        } finally {
            inIds.close(); inMask.close()
        }
    }

    // ---------------- decoder (greedy) ----------------

    private fun greedyDecode(tk: NllbTokenizer, hidden: FloatArray, srcLen: Int,
                             tgtId: Int): IntArray {
        val dec = decoder ?: error("decoder sin cargar")
        val encShape = longArrayOf(1, srcLen.toLong(), HIDDEN.toLong())
        val encMask = LongArray(srcLen) { 1L }

        val hiddenT = OnnxTensor.createTensor(env, FloatBuffer.wrap(hidden), encShape)
        val maskT = OnnxTensor.createTensor(env, LongBuffer.wrap(encMask),
            longArrayOf(1, srcLen.toLong()))

        encPast = HashMap()
        var stepIds = longArrayOf(DECODER_START.toLong(), tgtId.toLong())
        var first = true
        val out = ArrayList<Int>(64)

        try {
            for (step in 0 until MAX_NEW_TOKENS) {
                val logits = decoderStep(dec, hiddenT, maskT, stepIds, first)
                first = false
                var best = 0
                var bestV = Float.NEGATIVE_INFINITY
                for (v in logits.indices) {
                    if (logits[v] > bestV) { bestV = logits[v]; best = v }
                }
                if (best == EOS) break
                out.add(best)
                stepIds = longArrayOf(best.toLong())
            }
        } finally {
            hiddenT.close(); maskT.close()
        }
        return out.toIntArray()
    }

    /**
     * Un paso del decoder merged. Devuelve la fila de logits del último token.
     * Actualiza [encPast] con la KV de cross-attention del primer paso.
     */
    private fun decoderStep(dec: OrtSession, hiddenT: OnnxTensor, maskT: OnnxTensor,
                            ids: LongArray, first: Boolean): FloatArray {
        val feeds = HashMap<String, OnnxTensor>(pastNames.size + 4)
        val created = ArrayList<OnnxTensor>(pastNames.size + 4)

        val idT = OnnxTensor.createTensor(env, LongBuffer.wrap(ids),
            longArrayOf(1, ids.size.toLong()))
        created.add(idT)
        feeds["input_ids"] = idT
        feeds["encoder_hidden_states"] = hiddenT
        feeds["encoder_attention_mask"] = maskT
        val useCache = OnnxTensor.createTensor(env, boolBuffer(!first), longArrayOf(1),
            OnnxJavaType.BOOL)
        created.add(useCache)
        feeds["use_cache_branch"] = useCache

        for (name in pastNames) {
            val p = encPast[name]
            if (p != null) {
                val t = OnnxTensor.createTensor(env, FloatBuffer.wrap(p.first), p.second)
                created.add(t)
                feeds[name] = t
            } else {
                // past vacío (longitud 0): el grafo lo ignora con use_cache_branch=false
                val shape = longArrayOf(1, HEADS.toLong(), 0, HEAD_DIM.toLong())
                val t = OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(0)), shape)
                created.add(t)
                feeds[name] = t
            }
        }

        try {
            dec.run(feeds).use { res ->
                // present.* -> nuevo past.
                //
                // El decoder *merged* de Optimum, en la rama CON caché, devuelve los
                // present.*.encoder.* VACÍOS con forma **(0, 16, 1, 64)**: el eje 0
                // (lote) es 0 (no el eje 2). La KV de cross-attention es constante
                // entre pasos, así que hay que conservar la del primer paso; si se
                // reinyecta la vacía, ONNX Runtime falla al reshaparla
                // ("dimension with value zero exceeds the dimension size").
                val newEnc = HashMap<String, Pair<FloatArray, LongArray>>()
                for ((inName, outName) in pastNames.zip(presentNames)) {
                    val t = res.get(outName).get() as OnnxTensor
                    val shape = t.info.shape
                    // "present" inservible: sin lote (shape[0]==0) o sin tokens (shape[2]==0).
                    val empty = presentIsUnusable(shape)
                    val prev = encPast[inName]
                    if (!first && prev != null && empty) {
                        newEnc[inName] = prev          // conserva la KV del primer paso
                    } else {
                        newEnc[inName] = copyFloats(t) to shape
                    }
                }
                encPast = newEnc

                val logits = res.get("logits").get() as OnnxTensor
                val flat = copyFloats(logits)              // [1, T, V]
                val vocab = logits.info.shape[2].toInt()
                val start = flat.size - vocab
                return flat.copyOfRange(start, flat.size)
            }
        } finally {
            created.forEach { runCatching { it.close() } }
        }
    }

    private fun boolBuffer(v: Boolean): ByteBuffer =
        ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder()).put(if (v) 1 else 0).apply { rewind() }

    private fun copyFloats(t: OnnxTensor): FloatArray {
        val fb = t.floatBuffer
        val n = fb.remaining()
        val out = FloatArray(n)
        fb.get(out)
        return out
    }

    fun close() {
        runCatching { encoder?.close() }
        runCatching { decoder?.close() }
        encoder = null
        decoder = null
        tokenizer = null
        encPast = HashMap()
    }

}
