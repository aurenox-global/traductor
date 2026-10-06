package com.zota.traductor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import java.io.File

class CoreTest {

    @Test
    fun cleanOutput_quita_bloque_think() {
        val raw = "thinking\nesto es razonamiento\n<｜end▁of▁thinking｜>\nHola mundo"
        assertEquals("Hola mundo", Prompts.cleanOutput(raw))
    }

    @Test
    fun cleanOutput_quita_prefijo_respuesta() {
        assertEquals("Hola", Prompts.cleanOutput("Respuesta: \"Hola\""))
    }

    @Test
    fun systemPrompt_incluye_destino() {
        assertTrue(Prompts.systemPrompt("es").contains("español"))
        assertTrue(Prompts.systemPrompt("ja").contains("japonés"))
    }

    @Test
    fun systemPrompt_incluye_origen_y_destino() {
        val p = Prompts.systemPrompt("en", "es")
        assertTrue("deberia indicar origen: $p", p.contains("español"))
        assertTrue("deberia indicar destino: $p", p.contains("inglés"))
        assertTrue("deberia ser 'del ... al ...'", p.contains("Traduce del"))
    }

    @Test
    fun systemPrompt_auto_pide_detectar() {
        val p = Prompts.systemPrompt("en", "auto")
        assertTrue("deberia pedir deteccion: $p", p.contains("Detecta el idioma"))
        assertTrue(p.contains("inglés"))
    }

    @Test
    fun languages_cubre_minimo_requerido() {
        val required = listOf("auto", "es", "en", "fr", "de", "it", "pt", "ru",
            "zh", "ja", "ko", "ar", "hi", "tr", "nl", "pl", "uk")
        for (code in required) {
            assertTrue("falta idioma $code", Languages.ALL.any { it.code == code })
        }
        // destino no debe incluir "detectar"
        assertFalse(Languages.TARGETS.any { it.code == "auto" })
    }

    @Test
    fun languages_etiquetas_y_codigos_unicos() {
        val codes = Languages.ALL.map { it.code }
        assertEquals("codigos duplicados en la lista", codes.size, codes.toSet().size)
        assertTrue(Languages.ALL.first { it.code == "es" }.label.contains("Español"))
        assertTrue(Languages.ALL.first { it.code == "ja" }.label.contains("Japonés"))
    }

    @Test
    fun whisperCode_traduce_auto() {
        assertEquals("auto", Languages.whisperCode("auto"))
        assertEquals("es", Languages.whisperCode("es"))
        assertEquals("zh", Languages.whisperCode("zh"))
    }

    @Test
    fun energyVad_distingue_silencio_de_voz() {
        val vad = EnergyVad()
        val silence = FloatArray(512) { 0.0002f }
        val speech = FloatArray(512) { 0.15f * (if (it % 2 == 0) 1f else -1f) }
        repeat(20) { vad.speechProbability(silence) }
        val pSilence = vad.speechProbability(silence)
        val pSpeech = vad.speechProbability(speech)
        assertTrue("silencio deberia dar prob baja ($pSilence)", pSilence < 0.3f)
        assertTrue("voz deberia dar prob alta ($pSpeech)", pSpeech > 0.5f)
    }

    @Test
    fun segmenter_emite_segmento_tras_silencio() {
        var emitted = 0
        val seg2 = VadSegmenter(
            vad = object : VadDetector {
                private var n = 0
                override fun speechProbability(frame: FloatArray): Float {
                    n++
                    return if (n < 3) 0.9f else 0.0f
                }
            },
            sampleRate = 16000,
            frameSize = 512,
            endSilenceMs = 100,
            minSpeechMs = 50,
            onSegment = { emitted++ }
        )
        seg2.feed(FloatArray(16000) { 0.1f })
        assertTrue("deberia emitir al menos un segmento (emitidos=$emitted)", emitted >= 1)
    }

    @Test
    fun modelSpecs_tienen_url_https() {
        val all = ModelManager.WHISPER_DOWNLOADS + ModelManager.MT_DOWNLOADS + listOf(ModelManager.VAD)
        for (spec in all) {
            assertTrue(spec.url.startsWith("https://"))
            assertFalse(spec.fileName.isBlank())
        }
    }

    @Test
    fun whisper_incluye_varios_tamanos() {
        val names = ModelManager.WHISPER_DOWNLOADS.map { it.fileName }
        assertTrue(names.contains("ggml-tiny.bin"))
        assertTrue(names.contains("ggml-base.bin"))
        assertTrue(names.contains("ggml-small.bin"))
    }

    @Test
    fun human_formatea_tamanos() {
        assertTrue(ModelManager.human(2_327_524L).contains("MB"))
        assertTrue(ModelManager.human(527_502_816L).contains("MB"))
    }

    // ---------------- Piper (OnnxMeta) ----------------

    @Test
    fun onnx_varint_codifica_correctamente() {
        assertArrayEquals(byteArrayOf(0), OnnxMeta.varint(0))
        assertArrayEquals(byteArrayOf(1), OnnxMeta.varint(1))
        assertArrayEquals(byteArrayOf(127), OnnxMeta.varint(127))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x01), OnnxMeta.varint(128))
        assertArrayEquals(byteArrayOf(0xAC.toByte(), 0x02), OnnxMeta.varint(300))
    }

    @Test
    fun onnx_metadata_prop_tiene_campo_14_y_clave_valor() {
        val blob = OnnxMeta.metadataProp("comment", "piper")
        // tag del campo 14 wire type 2 = (14<<3)|2 = 114 = 0x72
        assertEquals(0x72.toByte(), blob[0])
        val text = blob.toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("comment"))
        assertTrue(text.contains("piper"))
    }

    @Test
    fun onnx_encode_incluye_todas_las_claves() {
        val meta = OnnxMeta.piperMetadata("Spanish", "es", 1, 22050)
        val blob = OnnxMeta.encodeMetadataProps(meta)
        val text = blob.toString(Charsets.ISO_8859_1)
        for ((k, v) in meta) {
            assertTrue("falta clave $k", text.contains(k))
            assertTrue("falta valor $v", text.contains(v))
        }
        assertEquals("piper", meta.first { it.first == "comment" }.second)
        assertEquals("22050", meta.first { it.first == "sample_rate" }.second)
    }

    @Test
    fun onnx_tokens_genera_lineas_simbolo_id() {
        val txt = OnnxMeta.tokensFromPhonemeIdMap(listOf("_" to 0, "^" to 1, " " to 3, "a" to 14))
        val lines = txt.trim().split("\n")
        assertEquals(4, lines.size)
        assertTrue(lines.contains("_ 0"))
        assertTrue(lines.contains("^ 1"))
        assertTrue(lines.contains("  3"))
        assertTrue(lines.contains("a 14"))
    }

    @Test
    fun piper_catalogo_tiene_voces_y_urls() {
        assertTrue(PiperVoiceManager.CATALOG.any { it.id == "es_AR-daniela-high" && it.lang == "es" })
        assertTrue(PiperVoiceManager.CATALOG.any { it.id == "en_US-hfc_female-medium" && it.lang == "en" })
        for (s in PiperVoiceManager.CATALOG) {
            assertTrue(s.onnxUrl.startsWith("https://"))
            assertTrue(s.jsonUrl.endsWith(".onnx.json"))
            assertTrue(s.onnxUrl.endsWith(".onnx"))
        }
    }

    @Test
    fun piper_catalogo_es_solo_femenino() {
        // Todas las voces del catálogo deben estar marcadas femeninas.
        for (s in PiperVoiceManager.CATALOG) {
            assertEquals("voz no femenina: ${s.id}", "F", s.gender)
        }
        // La voz masculina conocida NO debe aparecer.
        assertFalse(PiperVoiceManager.CATALOG.any { it.id == "es_ES-davefx-medium" })
        assertFalse(PiperVoiceManager.CATALOG.any { it.id == "es_ES-carlfm-x_low" })
        // Español: opciones femeninas verificadas (daniela/claude/sharvard).
        val es = PiperVoiceManager.CATALOG.filter { it.lang == "es" }.map { it.id }
        assertTrue(es.contains("es_AR-daniela-high"))
        assertTrue(es.contains("es_MX-claude-high"))
        assertTrue(es.contains("es_ES-sharvard-medium"))
        // sharvard es multi-speaker: la voz femenina es el speaker 1.
        assertEquals(1, PiperVoiceManager.CATALOG.first { it.id == "es_ES-sharvard-medium" }.speakerId)
        // El resto de idiomas preparados también femeninos y con idioma.
        for (code in listOf("en", "fr", "de", "it", "zh")) {
            assertTrue("falta voz para $code", PiperVoiceManager.CATALOG.any { it.lang == code })
        }
    }

    // ---------------- Piper: paquetes sherpa (.tar.bz2) ----------------

    @Test
    fun piper_catalogo_usa_paquetes_sherpa() {
        for (s in PiperVoiceManager.CATALOG) {
            assertEquals(
                "URL tar incorrecta para ${s.id}",
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-${s.id}.tar.bz2",
                s.tarUrl
            )
            assertTrue(s.tarUrl.endsWith(".tar.bz2"))
            assertTrue("approxBytes irreal para ${s.id}", s.approxBytes > 10_000_000L)
        }
        // La primera del catálogo de español es daniela (por defecto).
        assertEquals("es_AR-daniela-high", PiperVoiceManager.CATALOG.first { it.lang == "es" }.id)
        assertEquals("en_US-hfc_female-medium", PiperVoiceManager.CATALOG.first { it.lang == "en" }.id)
    }

    @Test
    fun prompts_descarta_meta_razonamiento_del_prompt() {
        // Salida exacta que reportó el usuario (cita el prompt del sistema).
        val raw = "*   Wait, the user prompt says: \"Si el texto de entrada ya está en inglés, " +
            "devuélvelo tal cual.\" (If the input text is already in English, return it as is"
        assertEquals("", Prompts.cleanOutput(raw))

        // Si hay traducción real + ruido meta, se queda solo la traducción.
        val raw2 = "* Wait, the user prompt says to translate.\nHola, ¿qué tal?"
        assertEquals("Hola, ¿qué tal?", Prompts.cleanOutput(raw2))
    }

    @Test
    fun prompts_limpia_razonamiento_sin_etiquetas() {
        val raw = "Thinking Process:\n" +
            "1. **Analyze the Request:**\n" +
            "    * Role: Professional translator.\n" +
            "    * Task: Translate from English to Chinese.\n" +
            "2. **Analyze the Input:**\n" +
            "    * Input: Yeah, I thought about that too; thanks for the support.\n" +
            "\n" +
            "Translation: \"嗯，我也想过这件事，谢谢你的支持。\""
        val out = Prompts.cleanOutput(raw)
        assertTrue("no debe contener razonamiento: $out", !out.contains("Thinking", ignoreCase = true))
        assertTrue("debe quedar la traducción: $out", out.contains("我也想过"))
    }

    @Test
    fun prompts_limpia_bloques_think_con_etiquetas() {
        assertEquals("Hola, ¿qué tal?", Prompts.cleanOutput(" thinking\nLet me think...\n<｜end▁of▁thinking｜>\nHola, ¿qué tal?"))
    }

    @Test
    fun piper_tar_classify_mapea_nombres_canonicos() {
        val p = "vits-piper-es_AR-daniela-high/es_AR-daniela-high.onnx"
        assertEquals(PiperTar.Kind.ONNX, PiperTar.classify(p)?.kind)
        assertEquals(PiperTar.MODEL, PiperTar.classify(p)?.destRel)

        val j = "vits-piper-es_AR-daniela-high/es_AR-daniela-high.onnx.json"
        assertEquals(PiperTar.Kind.JSON, PiperTar.classify(j)?.kind)
        assertEquals(PiperTar.JSON, PiperTar.classify(j)?.destRel)

        val t = "vits-piper-es_AR-daniela-high/tokens.txt"
        assertEquals(PiperTar.Kind.TOKENS, PiperTar.classify(t)?.kind)
        assertEquals(PiperTar.TOKENS, PiperTar.classify(t)?.destRel)

        val e = "vits-piper-x/espeak-ng-data/phondata"
        assertEquals(PiperTar.Kind.ESPEAK, PiperTar.classify(e)?.kind)
        assertEquals("espeak-ng-data/phondata", PiperTar.classify(e)?.destRel)

        // La ruta del json NO debe confundirse con el .onnx.
        assertEquals(PiperTar.Kind.JSON, PiperTar.classify("m.onnx.json")?.kind)
        // Ruido ignorado.
        assertNull(PiperTar.classify("vits-piper-x/MODEL_CARD"))
        assertNull(PiperTar.classify("vits-piper-x/"))
    }

    @Test
    fun piper_tar_classify_rechaza_path_traversal() {
        assertNull("no debe aceptar '..'", PiperTar.classify("../evil.onnx"))
        assertNull(PiperTar.classify("vits/../../evil.onnx"))
        assertNull(PiperTar.classify("vits/espeak-ng-data/../../x"))
        assertNull("no debe aceptar rutas absolutas", PiperTar.classify("/etc/passwd"))
        assertNull(PiperTar.classify("vits/..\\x"))
    }

    @Test
    fun piper_tar_base_id_normaliza_nombre_de_paquete() {
        assertEquals("es_AR-daniela-high", PiperTar.baseIdFromArchiveName("vits-piper-es_AR-daniela-high.tar.bz2"))
        assertEquals("mi-voz", PiperTar.baseIdFromArchiveName("mi-voz.tgz"))
        assertEquals("mi-voz", PiperTar.baseIdFromArchiveName("mi-voz.tar"))
        assertTrue(PiperTar.isArchive("vits-piper-es_MX-claude-high.tar.bz2"))
        assertTrue(PiperTar.isArchive("x.tgz"))
        assertFalse(PiperTar.isArchive("model.onnx"))
    }

    private fun writeSyntheticPackage(file: File) {
        file.parentFile?.mkdirs()
        BZip2CompressorOutputStream(file.outputStream().buffered()).use { bz ->
            TarArchiveOutputStream(bz).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                fun dir(name: String) {
                    // Un nombre acabado en '/' marca la entrada como directorio.
                    val e = TarArchiveEntry(name)
                    tar.putArchiveEntry(e); tar.closeArchiveEntry()
                }
                fun entry(name: String, content: String) {
                    val e = TarArchiveEntry(name)
                    val bytes = content.toByteArray(Charsets.UTF_8)
                    e.size = bytes.size.toLong()
                    tar.putArchiveEntry(e)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
                dir("vits-piper-test-voice/")
                entry("vits-piper-test-voice/test-voice.onnx", "FAKE_ONNX_BYTES")
                entry("vits-piper-test-voice/test-voice.onnx.json", "{\"num_speakers\":1}")
                entry("vits-piper-test-voice/tokens.txt", "a 1\n")
                entry("vits-piper-test-voice/MODEL_CARD", "card")
                dir("vits-piper-test-voice/espeak-ng-data/")
                entry("vits-piper-test-voice/espeak-ng-data/phontab", "phon")
                // Intento de path traversal: debe rechazarse.
                entry("../pwned.txt", "evil")
            }
        }
    }

    @Test
    fun piper_tar_extrae_paquete_sherpa_streaming() {
        val root = File(System.getProperty("java.io.tmpdir"), "pipertar-test-${System.nanoTime()}")
        val pkg = File(root, "vits-piper-test-voice.tar.bz2")
        val out = File(root, "out")
        try {
            writeSyntheticPackage(pkg)
            val res = PiperTar.extractFile(pkg, out)
            assertTrue("debe encontrar el .onnx", res.model)
            assertTrue("debe encontrar tokens.txt", res.tokens)
            assertTrue("debe encontrar el .json", res.json)
            assertTrue("debe extraer ficheros de espeak-ng-data", res.espeakFiles >= 1)
            assertTrue("debe rechazar el traversal", res.rejected >= 1)
            assertTrue(res.usable)

            assertEquals("FAKE_ONNX_BYTES", File(out, "model.onnx").readText())
            assertEquals("a 1\n", File(out, "tokens.txt").readText())
            assertTrue(File(out, "voice.onnx.json").isFile)
            assertTrue(File(out, "espeak-ng-data/phontab").isFile)
            assertFalse("MODEL_CARD no debe copiarse", File(out, "MODEL_CARD").exists())
            assertFalse("no debe escapar del destino", File(root, "pwned.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
