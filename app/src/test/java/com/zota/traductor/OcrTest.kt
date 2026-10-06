package com.zota.traductor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Evidencia de host (sin dispositivo) del nuevo motor OCR:
 *  - parser del diccionario PP-OCR (inference.yml),
 *  - preproceso (plan de resize + normalización BGR/CHW + recorte en perspectiva),
 *  - extracción de cajas del detector DB (componentes + min-area-rect + unclip),
 *  - decodificación CTC voraz con el diccionario.
 */
class OcrTest {

    // ---------------- diccionario ----------------

    @Test
    fun ocrDict_parsea_character_dict_yaml() {
        val yml = """
            PostProcess:
              name: CTCLabelDecode
              character_dict:
              - '!'
              - '"'
              - $
              - ''''
              - 　
              - 你
              - a
            PreProcess:
              x: 1
        """.trimIndent()
        val d = OcrDict.parseCharacterDict(yml)
        assertEquals(7, d.size)
        assertEquals("!", d[0])
        assertEquals("\"", d[1])
        assertEquals("$", d[2])
        assertEquals("'", d[3])
        assertEquals("\u3000", d[4])   // espacio ideográfico (línea "- 　")
        assertEquals("你", d[5])
        assertEquals("a", d[6])
    }

    @Test
    fun ocrDict_roundtrip_en_archivo() {
        val dir = File(System.getProperty("java.io.tmpdir"), "ocrdict-${System.nanoTime()}")
        val f = File(dir, "dict.txt")
        try {
            val chars = listOf("a", "ñ", "你", "\u3000", "\\")
            OcrDict.write(f, chars)
            assertEquals(chars, OcrDict.load(f))
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------- preproceso ----------------

    @Test
    fun ocrPreprocess_detPlan_limita_y_redondea_a_32() {
        // 900x300 no supera el límite: se redondea a múltiplos de 32.
        assertEquals(OcrPreprocess.Plan(896, 288), OcrPreprocess.detPlan(900, 300))
        // 4000x3000 -> lado mayor = 960 (ratio 0,24) y el otro a múltiplo de 32 (round bancario: 22,5 -> 22).
        val big = OcrPreprocess.detPlan(4000, 3000)
        assertEquals(960, big.dstW)
        assertEquals(0, big.dstW % 32)
        assertEquals(0, big.dstH % 32)
        assertTrue("alto proporcional: ${big.dstH}", big.dstH in 704..736)
        // Mínimo 32.
        assertEquals(OcrPreprocess.Plan(32, 32), OcrPreprocess.detPlan(10, 10))
    }

    @Test
    fun ocrPreprocess_recPlan_alto_fijo_48() {
        assertEquals(OcrPreprocess.Plan(96, 48), OcrPreprocess.recPlan(200, 100))
        assertEquals(OcrPreprocess.Plan(1, 48), OcrPreprocess.recPlan(0, 100))
    }

    @Test
    fun ocrPreprocess_resizeBilinear_interpola_y_conserva_colores() {
        // 2x1: rojo puro a la izquierda, azul puro a la derecha.
        val red = (0xFF shl 24) or (0xFF shl 16)
        val blue = (0xFF shl 24) or 0xFF
        val src = OcrImage(2, 1, intArrayOf(red, blue))
        val out = OcrPreprocess.resizeBilinear(src, 4, 2)
        assertEquals(4, out.width)
        assertEquals(2, out.height)
        // Primer píxel sigue siendo (casi) rojo y el último (casi) azul.
        assertTrue("izquierda rojiza", ((out.argb(0, 0) ushr 16) and 0xFF) > 200)
        assertTrue("derecha azulada", (out.argb(3, 0) and 0xFF) > 200)
        // En medio hay mezcla (ni rojo puro ni azul puro).
        val mid = out.argb(2, 0)
        assertTrue(((mid ushr 16) and 0xFF) in 1..254)
        assertTrue((mid and 0xFF) in 1..254)
    }

    @Test
    fun ocrPreprocess_toDetInput_usa_orden_BGR_y_normaliza() {
        // Un píxel: R=255, G=128, B=0.
        val px = (0xFF shl 24) or (0xFF shl 16) or (128 shl 8)
        val img = OcrImage(1, 1, intArrayOf(px))
        val t = OcrPreprocess.toDetInput(img)
        assertEquals(3, t.size)
        // canal 0 = B, canal 1 = G, canal 2 = R (mean/std de PP-OCR).
        assertEquals((0f - 0.485f) / 0.229f, t[0], 1e-3f)
        assertEquals((128f / 255f - 0.456f) / 0.224f, t[1], 1e-3f)
        assertEquals((255f / 255f - 0.406f) / 0.225f, t[2], 1e-3f)
    }

    @Test
    fun ocrPreprocess_toRecInput_normaliza_a_menos1_1() {
        val black = (0xFF shl 24)
        val white = (0xFF shl 24) or (0xFF shl 16) or (0xFF shl 8) or 0xFF
        val img = OcrImage(2, 1, intArrayOf(black, white))
        val t = OcrPreprocess.toRecInput(img)
        assertEquals(-1f, t[0], 1e-4f)   // B=0 -> -1
        assertEquals(1f, t[3], 1e-4f)    // R del píxel blanco -> +1
    }

    @Test
    fun ocrPreprocess_cropPerspective_extrae_region_exacta() {
        val white = (0xFF shl 24) or 0xFFFFFF
        val red = (0xFF shl 24) or (0xFF shl 16)
        val w = 20; val h = 20
        val px = IntArray(w * h) { white }
        for (y in 3..12) for (x in 5..14) px[y * w + x] = red
        val img = OcrImage(w, h, px)

        val box = floatArrayOf(5f, 3f, 14f, 3f, 14f, 12f, 5f, 12f) // tl,tr,br,bl
        val crop = OcrPreprocess.cropPerspective(img, box)
        assertTrue("ancho razonable: ${crop.width}", crop.width in 8..11)
        assertTrue("alto razonable: ${crop.height}", crop.height in 8..11)
        for (i in 0 until crop.width * crop.height) {
            assertEquals("recorte debe ser rojo (i=$i)", red, crop.pixels[i])
        }
    }

    @Test
    fun ocrPreprocess_homografia_es_identidad_para_mismo_rectangulo() {
        val pts = floatArrayOf(0f, 0f, 9f, 0f, 9f, 9f, 0f, 9f)
        val h = OcrPreprocess.solveHomography(pts, pts)
        assertEquals(1.0, h[0], 1e-6)
        assertEquals(0.0, h[1], 1e-6)
        assertEquals(0.0, h[2], 1e-6)
        assertEquals(0.0, h[3], 1e-6)
        assertEquals(1.0, h[4], 1e-6)
        assertEquals(1.0, h[8], 1e-6)
    }

    // ---------------- cajas DB ----------------

    @Test
    fun ocrBox_orderedPoints_devuelve_tl_tr_br_bl() {
        val box = OcrBox(cx = 10f, cy = 10f, w = 8f, h = 4f, angle = 0f, score = 1f)
        val p = box.orderedPoints()
        assertEquals(6f, p[0], 1e-3f); assertEquals(8f, p[1], 1e-3f)   // tl
        assertEquals(14f, p[2], 1e-3f); assertEquals(8f, p[3], 1e-3f)  // tr
        assertEquals(14f, p[4], 1e-3f); assertEquals(12f, p[5], 1e-3f) // br
        assertEquals(6f, p[6], 1e-3f); assertEquals(12f, p[7], 1e-3f)  // bl
    }

    @Test
    fun ocrBox_unclip_expande_el_contorno() {
        val box = OcrBox(cx = 0f, cy = 0f, w = 10f, h = 10f, angle = 0f, score = 1f)
        val up = box.unclip(1.4f)
        // d = (100·1.4)/(2·20) = 3,5 -> 10 + 7
        assertEquals(17f, up.w, 1e-3f)
        assertEquals(17f, up.h, 1e-3f)
    }

    @Test
    fun dbPostProcess_extrae_dos_cajas_con_score() {
        val w = 100; val h = 100
        val prob = FloatArray(w * h) { 0f }
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int) {
            for (y in y0..y1) for (x in x0..x1) prob[y * w + x] = 1f
        }
        rect(10, 10, 39, 29)
        rect(10, 50, 39, 69)

        val boxes = DbPostProcess.extractBoxes(prob, w, h)
        assertEquals("deben salir exactamente 2 cajas", 2, boxes.size)

        val sorted = DbPostProcess.sortReadingOrder(boxes)
        // orden de lectura: la de arriba primero
        assertTrue(sorted[0].cy < sorted[1].cy)
        assertEquals(24.5f, sorted[0].cx, 1.0f)
        assertEquals(19.5f, sorted[0].cy, 1.0f)
        assertEquals(24.5f, sorted[1].cx, 1.0f)
        assertEquals(59.5f, sorted[1].cy, 1.0f)
        // unclip expande la caja original (30x20 px de relleno -> 29x19 geométricos)
        assertTrue("w expandido: ${sorted[0].w}", sorted[0].w > 29f)
        assertTrue("h expandido: ${sorted[0].h}", sorted[0].h > 19f)
        assertEquals(1f, sorted[0].score, 1e-3f)
    }

    @Test
    fun dbPostProcess_descarta_cajas_con_score_bajo() {
        val w = 40; val h = 40
        val prob = FloatArray(w * h) { 0f }
        for (y in 5..25) for (x in 5..25) prob[y * w + x] = 0.3f // > thresh 0.2, < boxThresh 0.4
        val boxes = DbPostProcess.extractBoxes(prob, w, h)
        assertTrue("score 0,3 debe descartarse", boxes.isEmpty())
    }

    @Test
    fun dbPostProcess_ignora_ruido_puntual() {
        val w = 30; val h = 30
        val prob = FloatArray(w * h) { 0f }
        prob[5 * w + 5] = 1f       // 1 solo píxel (count < 3)
        prob[6 * w + 6] = 1f
        val boxes = DbPostProcess.extractBoxes(prob, w, h)
        assertTrue(boxes.isEmpty())
    }

    @Test
    fun dbPostProcess_minAreaRect_de_rectangulo() {
        val xs = IntArray(200); val ys = IntArray(200)
        var i = 0
        for (y in 0..9) for (x in 0..19) { xs[i] = x; ys[i] = y; i++ }
        val r = DbPostProcess.minAreaRect(xs, ys, i)
        assertEquals(9.5f, r[0], 0.1f)   // cx
        assertEquals(4.5f, r[1], 0.1f)   // cy
        assertEquals(19f, r[2], 0.1f)    // ancho (extensión geométrica)
        assertEquals(9f, r[3], 0.1f)     // alto
    }

    // ---------------- decodificación CTC ----------------

    private fun logits(steps: IntArray, classes: Int): FloatArray {
        val out = FloatArray(steps.size * classes)
        for (t in steps.indices) {
            for (c in 0 until classes) out[t * classes + c] = if (c == steps[t]) 1f else 0f
        }
        return out
    }

    @Test
    fun ctcDecoder_colapsa_repes_y_quita_blanks() {
        val dict = listOf("a", "b", "c")   // clases: 0=blank,1=a,2=b,3=c,4=espacio
        val seq = intArrayOf(0, 1, 1, 0, 2, 3, 0)
        val txt = CtcDecoder.decode(logits(seq, 5), seq.size, 5, dict)
        assertEquals("abc", txt)
    }

    @Test
    fun ctcDecoder_espacio_es_la_clase_extra() {
        val dict = listOf("a", "b")
        val seq = intArrayOf(1, 3, 2)      // a <espacio> b
        assertEquals("a b", CtcDecoder.decode(logits(seq, 4), seq.size, 4, dict))
    }

    @Test
    fun ctcDecoder_ignora_todo_si_solo_hay_blanks() {
        val dict = listOf("a", "b")
        val seq = intArrayOf(0, 0, 0)
        assertEquals("", CtcDecoder.decode(logits(seq, 4), seq.size, 4, dict))
    }

    @Test
    fun ctcDecoder_diccionario_chino_y_latino() {
        val dict = listOf("H", "i", "你", "好")
        val seq = intArrayOf(1, 2, 3, 4)   // H i 你 好
        assertEquals("Hi你好", CtcDecoder.decode(logits(seq, 6), seq.size, 6, dict))
    }

    // ---------------- catálogo ----------------

    @Test
    fun ocrModels_urls_https_y_nombres_coherentes() {
        for (spec in OcrModels.DOWNLOADS) {
            assertTrue(spec.url.startsWith("https://"))
            assertTrue(spec.fileName.isNotBlank())
            assertTrue(spec.approxBytes > 1000)
        }
        // alternativa PP-OCRv4 preparada
        val v4 = listOf(OcrModels.DET_V4, OcrModels.REC_V4, OcrModels.KEYS_V4)
        for (spec in v4) {
            assertTrue(spec.url.startsWith("https://"))
        }
        assertTrue(OcrModels.DET_V4.url.contains("ch_PP-OCRv4_det_infer.onnx"))
        assertTrue(OcrModels.REC_V4.url.contains("ch_PP-OCRv4_rec_infer.onnx"))
    }
}
