import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.zota.traductor.NllbEngine
import com.zota.traductor.NllbTokenizer
import java.io.File

/**
 * Arnés JVM: ejecuta el **mismo** [NllbEngine] (y [NllbTokenizer]) de Android
 * sobre los ONNX reales de Xenova/nllb-200-distilled-600M, en el Mac.
 *
 * Uso:
 *   java -cp out:onnxruntime.jar:kotlin-stdlib.jar HarnessKt <modelDir> <tokenizer.bin> <src> <tgt> <texto>
 */
class StubContext : android.content.Context()

fun setField(obj: Any, name: String, value: Any?) {
    val f = obj.javaClass.getDeclaredField(name)
    f.isAccessible = true
    f.set(obj, value)
}

fun main(args: Array<String>) {
    val modelDir = File(args[0])
    val tokPath = File(args[1])
    val src = args[2]
    val tgt = args[3]
    val text = args[4]

    val env = OrtEnvironment.getEnvironment()
    val opts = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setIntraOpNumThreads(4)
    }
    val enc = env.createSession(File(modelDir, "onnx/encoder_model_quantized.onnx").absolutePath, opts)
    val dec = env.createSession(File(modelDir, "onnx/decoder_model_merged_quantized.onnx").absolutePath, opts)

    println("ENC inputs : ${enc.inputNames}")
    println("ENC outputs: ${enc.outputInfo.keys}")
    println("DEC inputs : ${dec.inputNames}")
    println("DEC outputs: ${dec.outputInfo.keys}")

    val tk = tokPath.inputStream().use { NllbTokenizer.load(it) }
    println("tokenizer  : ${tk.vocabSize()} tokens; deu_Latn=${tk.langId("deu_Latn")} spa_Latn=${tk.langId("spa_Latn")} eng_Latn=${tk.langId("eng_Latn")}")

    val eng = NllbEngine(StubContext())
    setField(eng, "encoder", enc)
    setField(eng, "decoder", dec)
    setField(eng, "tokenizer", tk)
    println("isLoaded   : ${eng.isLoaded()}")

    val t0 = System.currentTimeMillis()
    val out = eng.translate(text, src, tgt)
    val dt = System.currentTimeMillis() - t0
    println("--- src=$src tgt=$tgt (${dt} ms) ---")
    println("IN : $text")
    println("OUT: $out")

    eng.close()
}
