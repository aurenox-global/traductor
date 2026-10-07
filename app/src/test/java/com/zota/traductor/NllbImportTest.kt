package com.zota.traductor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Evidencia de host (sin dispositivo) de la **importación de modelos NLLB**:
 * clasificación de nombres y validación de que no falta ninguno. El copiado real
 * (ContentResolver -> `filesDir/nllb_models/`) no se prueba aquí.
 */
class NllbImportTest {

    // ---------------- classify ----------------

    @Test
    fun clasifica_nombres_originales_de_huggingface() {
        assertEquals(NllbImport.Role.ENCODER, NllbImport.classify("encoder_model_quantized.onnx"))
        assertEquals(NllbImport.Role.DECODER, NllbImport.classify("decoder_model_merged_quantized.onnx"))
        assertEquals(NllbImport.Role.TOKENIZER, NllbImport.classify("tokenizer.bin"))
    }

    @Test
    fun clasifica_nombres_destino_de_la_app() {
        assertEquals(NllbImport.Role.ENCODER, NllbImport.classify("nllb_encoder_model_quantized.onnx"))
        assertEquals(NllbImport.Role.DECODER, NllbImport.classify("nllb_decoder_model_merged_quantized.onnx"))
        assertEquals(NllbImport.Role.TOKENIZER, NllbImport.classify("tokenizer.bin"))
    }

    @Test
    fun clasifica_ignora_mayusculas_y_rutas() {
        assertEquals(NllbImport.Role.ENCODER, NllbImport.classify("ONNX/NLLB_Encoder_Model_Quantized.ONNX"))
        assertEquals(NllbImport.Role.TOKENIZER, NllbImport.classify("sub/dir/Tokenizer.BIN"))
    }

    @Test
    fun clasifica_descarta_ficheros_ajenos() {
        assertNull(NllbImport.classify("ggml-base.bin"))
        assertNull(NllbImport.classify("silero_vad.onnx"))
        assertNull(NllbImport.classify("ppocr_v6_rec.onnx"))
        assertNull(NllbImport.classify("README.md"))
        assertNull(NllbImport.classify(""))
    }

    @Test
    fun clasifica_no_confunde_encoder_con_decoder() {
        assertFalse(NllbImport.classify("decoder_model_merged_quantized.onnx") == NllbImport.Role.ENCODER)
        assertFalse(NllbImport.classify("encoder_model_quantized.onnx") == NllbImport.Role.DECODER)
    }

    // ---------------- plan / validación ----------------

    @Test
    fun plan_completo_con_nombres_originales() {
        val plan = NllbImport.plan(
            listOf("encoder_model_quantized.onnx", "decoder_model_merged_quantized.onnx", "tokenizer.bin")
        )
        assertTrue(plan.complete)
        assertTrue(plan.missing.isEmpty())
        assertEquals(3, plan.matches.size)
        assertEquals(
            "encoder_model_quantized.onnx",
            plan.matchFor(NllbImport.Role.ENCODER)?.source
        )
        assertEquals(
            "decoder_model_merged_quantized.onnx",
            plan.matchFor(NllbImport.Role.DECODER)?.source
        )
        assertEquals("tokenizer.bin", plan.matchFor(NllbImport.Role.TOKENIZER)?.source)
    }

    @Test
    fun plan_detecta_los_que_faltan() {
        val plan = NllbImport.plan(listOf("encoder_model_quantized.onnx", "README.md"))
        assertFalse(plan.complete)
        assertEquals(listOf(NllbImport.Role.DECODER, NllbImport.Role.TOKENIZER), plan.missing)
        assertEquals("decoder ONNX, tokenizer.bin", NllbImport.missingLabels(plan))
    }

    @Test
    fun plan_ignora_ficheros_ajenos_y_duplicados() {
        // El bosque de ficheros trae basura + un encoder duplicado: el primero gana.
        val plan = NllbImport.plan(
            listOf(
                "manifest.json",
                "encoder_model.onnx",
                "encoder_model_quantized.onnx",
                "decoder_model_merged_quantized.onnx",
                "tokenizer.bin",
                "silero_vad.onnx"
            )
        )
        assertTrue(plan.complete)
        assertEquals("encoder_model.onnx", plan.matchFor(NllbImport.Role.ENCODER)?.source)
    }

    @Test
    fun plan_vacio_no_esta_completo() {
        val plan = NllbImport.plan(emptyList())
        assertFalse(plan.complete)
        assertEquals(3, plan.missing.size)
    }

    @Test
    fun target_name_coincide_con_lo_que_espera_el_motor() {
        // El copiado debe dejar los ficheros con los nombres que usa NllbEngine.
        assertEquals("nllb_encoder_model_quantized.onnx", NllbImport.Role.ENCODER.targetName)
        assertEquals("nllb_decoder_model_merged_quantized.onnx", NllbImport.Role.DECODER.targetName)
        assertEquals("tokenizer.bin", NllbImport.Role.TOKENIZER.targetName)
        assertEquals(NllbModels.ENCODER.fileName, NllbImport.Role.ENCODER.targetName)
        assertEquals(NllbModels.DECODER.fileName, NllbImport.Role.DECODER.targetName)
    }
}
