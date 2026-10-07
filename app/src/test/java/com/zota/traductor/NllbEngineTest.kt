package com.zota.traductor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Partes puras (JVM) del motor NLLB: mapeo de idiomas de la app -> NLLB y el
 * troceado por frases (necesario porque NLLB-600M descarta el texto tras la
 * primera frase — verificado en la FASE 1 en el Mac).
 */
class NllbEngineTest {

    @Test
    fun mapeo_iso_a_codigo_nllb() {
        assertEquals("spa_Latn", NllbLangs.toNllb("es"))
        assertEquals("eng_Latn", NllbLangs.toNllb("en"))
        assertEquals("deu_Latn", NllbLangs.toNllb("de"))
        assertEquals("fin_Latn", NllbLangs.toNllb("fi"))
        // El bengalí de NLLB es ben_Beng (no ben_Latn).
        assertEquals("ben_Beng", NllbLangs.toNllb("bn"))
        // Acepta ya un código NLLB.
        assertEquals("zho_Hans", NllbLangs.toNllb("zho_Hans"))
    }

    @Test
    fun auto_usa_el_fallback() {
        assertEquals("eng_Latn", NllbLangs.toNllb(Languages.AUTO.code))
        assertEquals("spa_Latn", NllbLangs.toNllb(Languages.AUTO.code, fallback = "spa_Latn"))
    }

    @Test
    fun iso_inverso() {
        assertEquals("es", NllbLangs.toIso("spa_Latn"))
        assertEquals("bn", NllbLangs.toIso("ben_Beng"))
        assertEquals(null, NllbLangs.toIso("xxx_Yyyy"))
    }

    @Test
    fun trocea_una_frase_por_trozo() {
        val text = "The weather forecast promised clear skies. The hikers packed their jackets. It rained anyway."
        val chunks = NllbEngine.splitSentences(text, 400)
        assertEquals(3, chunks.size)
        assertEquals("The weather forecast promised clear skies.", chunks[0])
        assertEquals("It rained anyway.", chunks[2])
    }

    @Test
    fun respeta_puntos_finales_no_latinos() {
        val chunks = NllbEngine.splitSentences("おはようございます。今日はいい天気ですね。", 400)
        assertEquals(2, chunks.size)
    }

    @Test
    fun parte_frases_demasiado_largas_por_palabras() {
        val text = (1..40).joinToString(" ") { "palabra$it" }
        val chunks = NllbEngine.splitSentences(text, 60)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 60 })
    }

    @Test
    fun present_vacio_por_lote_o_por_tokens() {
        // Rama con caché del decoder merged: present.encoder.* con lote 0 -> inservible.
        assertTrue(NllbEngine.presentIsUnusable(longArrayOf(0, 16, 1, 64)))
        // 0 tokens en el eje 2 -> inservible.
        assertTrue(NllbEngine.presentIsUnusable(longArrayOf(1, 16, 0, 64)))
        // KV válida del primer paso y del decoder -> utilizable.
        assertTrue(!NllbEngine.presentIsUnusable(longArrayOf(1, 16, 9, 64)))
        assertTrue(!NllbEngine.presentIsUnusable(longArrayOf(1, 16, 2, 64)))
    }

    @Test
    fun texto_vacio_o_sin_frases_no_pierde_contenido() {
        assertEquals(listOf("Hola mundo"), NllbEngine.splitSentences("Hola mundo", 400))
    }
}
