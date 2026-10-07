package com.zota.traductor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verifica el tokenizador Kotlin de NLLB **contra la referencia de HuggingFace**
 * (`tokenizers` en Python, mismos textos y vectores generados por
 * `scripts/gen_tokenizer_refs.py`). Es la prueba de que el port a Kotlin es
 * fiel al original, sin necesidad de dispositivo.
 *
 * Requiere `app/src/main/assets/nllb/tokenizer.bin`, generado con
 * `scripts/build_nllb_tokenizer.py`.
 */
class NllbTokenizerTest {

    private val CASES = listOf(
            "Hello, how are you today? I would like a coffee, please." to intArrayOf(94124, 248079, 11657, 2442, 1259, 30435, 248130, 117, 9713, 6399, 9, 121318, 248079, 43205, 248075),
            "Buenos días, ¿dónde está la estación de tren más cercana?" to intArrayOf(139066, 36660, 248079, 2247, 248072, 49196, 5766, 82, 178249, 79, 16303, 4229, 3888, 22088, 248130),
            "Der Zug nach Berlin fährt jeden Morgen um acht Uhr ab." to intArrayOf(6856, 184121, 5139, 48644, 42, 33263, 248065, 26608, 153815, 505, 75649, 167078, 513, 248075),
            "Minä pidän suomalaisesta saunasta ja järvistä, erityisesti kesällä." to intArrayOf(54142, 19263, 751, 327, 65726, 79351, 127, 24, 2387, 423, 12903, 200273, 248079, 96821, 2774, 73383, 248075),
            "আমি বাংলা ভাষা শিখছি এবং আমার পরিবার বাংলাদেশে থাকে।" to intArrayOf(6211, 79642, 123588, 154523, 7373, 7834, 10425, 145589, 98896, 248208, 29208, 248225),
            "The northern lights are a natural phenomenon." to intArrayOf(1617, 114473, 2114, 177, 128266, 2442, 9, 25307, 164135, 5835, 18, 248075),
            "Buongiorno, vorrei un caffè e un cornetto, per favore." to intArrayOf(2056, 11239, 70124, 248079, 68047, 159, 771, 244421, 23, 159, 3655, 2799, 208, 248079, 311, 47337, 248075),
            "Доброе утро, где ближайшая станция метро?" to intArrayOf(148258, 248089, 203968, 248079, 29039, 21879, 55588, 65322, 231166, 133368, 248130),
            "おはようございます、今日はいい天気ですね。" to intArrayOf(12613, 248243, 1851, 180712, 253131, 117767, 13157, 249910, 249689, 44325, 253935),
            "مرحبا، أين أقرب محطة قطار؟" to intArrayOf(147168, 248238, 53673, 37120, 4195, 3587, 37186, 206769, 248727),
            "Artificial intelligence is transforming the world." to intArrayOf(97823, 31194, 171850, 248, 42806, 87, 349, 15697, 248075),
            "A" to intArrayOf(70),
            "one two three four five" to intArrayOf(4990, 9872, 29213, 34047, 54369),
        )

    private val tokenizer: NllbTokenizer by lazy {
        val f = File("src/main/assets/nllb/tokenizer.bin")
        assertTrue("falta ${f.absolutePath} (ejecuta scripts/build_nllb_tokenizer.py)",
            f.isFile)
        f.inputStream().use { NllbTokenizer.load(it) }
    }

    @Test
    fun coincide_con_huggingface_tokenizers() {
        var ok = 0
        for ((text, expected) in CASES) {
            val got = tokenizer.encode(text)
            assertArrayEquals("texto: $text", expected, got)
            ok++
        }
        assertEquals(CASES.size, ok)
    }

    @Test
    fun ids_de_idioma_nllb() {
        assertEquals(256047, tokenizer.langId("eng_Latn"))
        assertEquals(256161, tokenizer.langId("spa_Latn"))
        assertEquals(256042, tokenizer.langId("deu_Latn"))
        assertEquals(256055, tokenizer.langId("fin_Latn"))
        assertTrue(tokenizer.langId("ben_Beng") > 256000)
    }

    @Test
    fun encode_source_anade_prefijo_y_eos() {
        val ids = tokenizer.encodeSource("A", "spa_Latn")
        assertEquals(256161L, ids[0])
        assertEquals(70L, ids[1])
        assertEquals(2L, ids[2])
        assertEquals(3, ids.size)
    }

    @Test
    fun decode_reconstruye_el_texto() {
        val text = "Hello, how are you today?"
        val ids = tokenizer.encode(text)
        assertEquals(text, tokenizer.decode(ids))
    }

    @Test
    fun decode_ignora_especiales_y_lang() {
        val ids = intArrayOf(2, 256161, 70, 2)
        assertEquals("A", tokenizer.decode(ids))
    }
}
