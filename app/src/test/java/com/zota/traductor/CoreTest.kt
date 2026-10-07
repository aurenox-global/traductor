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
    fun systemPrompt_es_minimo() {
        // v0.9.4: el system es MINIMO (el 0.8B copiaba el prompt largo).
        assertEquals("Eres un traductor.", Prompts.systemPrompt("es"))
        assertEquals("Eres un traductor.", Prompts.systemPrompt("ja", "es"))
    }

    @Test
    fun userPrompt_incluye_destino_y_texto() {
        val p = Prompts.userPrompt("Hallo", "es")
        assertTrue("deberia indicar destino: $p", p.contains("español"))
        assertTrue("deberia incluir el texto: $p", p.contains("Hallo"))
        assertTrue("deberia pedir la traduccion: $p", p.contains("Traduce el texto anterior"))
    }

    @Test
    fun userPrompt_acepta_auto_y_origen() {
        assertTrue(Prompts.userPrompt("Hallo", "en", "auto").contains("inglés"))
        assertTrue(Prompts.userPrompt("Hola", "en", "es").contains("inglés"))
    }

    @Test
    fun languages_cubre_minimo_requerido() {
        val required = listOf("auto", "es", "en", "fr", "de", "it", "pt", "ru",
            "zh", "ja", "ko", "ar", "hi", "tr", "nl", "pl", "uk", "bg", "hu", "ro")
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
        val all = ModelManager.WHISPER_DOWNLOADS + NllbModels.ALL + listOf(ModelManager.VAD)
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
    fun onnx_tokens_omite_simbolos_multicodepoint() {
        // sherpa-onnx exige un solo codepoint por token; "aɪ" debe poder omitirse.
        val txt = OnnxMeta.tokensFromPhonemeIdMap(
            listOf("a" to 14, "aɪ" to 161, " " to 3),
            dropMultiCodepoint = true
        )
        val lines = txt.trim().split("\n")
        assertEquals(2, lines.size)
        assertTrue(lines.contains("a 14"))
        assertFalse("no debe conservarse el bigrama", lines.any { it.contains("aɪ") })
        // Sin filtro se conserva (comportamiento previo intacto).
        assertTrue(OnnxMeta.tokensFromPhonemeIdMap(listOf("aɪ" to 161)).contains("aɪ"))
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
    fun piper_catalogo_prefiere_femeninas_con_excepciones() {
        // Preferencia del usuario: voces FEMENINAS. Solo se admite una voz masculina
        // cuando es la ÚNICA disponible para ese idioma (y queda marcada como "M").
        val maleOnly = setOf("ar", "tr", "bg", "ro", "da", "fa", "fi", "he")
        for (s in PiperVoiceManager.CATALOG) {
            if (s.lang in maleOnly) {
                assertEquals("deberia ser masculina: ${s.id}", "M", s.gender)
            } else {
                assertEquals("deberia ser femenina: ${s.id}", "F", s.gender)
            }
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

    @Test
    fun piper_catalogo_cubre_los_ocho_idiomas_nuevos() {
        // v0.7: una voz por cada idioma nuevo. Género según la medición F0 real.
        // pl/ko/hu/ja -> femenina; ar/tr/bg/ro -> masculina (única disponible).
        val expected = mapOf(
            "pl" to ("pl_PL-gosia-medium" to "F"),
            "ar" to ("ar_JO-kareem-medium" to "M"),
            "tr" to ("tr_TR-dfki-medium" to "M"),
            "ko" to ("ko_KR-kss-medium" to "F"),
            "bg" to ("bg_BG-dimitar-medium" to "M"),
            "hu" to ("hu_HU-anna-medium" to "F"),
            "ro" to ("ro_RO-mihai-medium" to "M"),
            "ja" to ("ja_JP-hi_fi_captain-medium" to "F")
        )
        for ((lang, pair) in expected) {
            val (id, gender) = pair
            val spec = PiperVoiceManager.CATALOG.firstOrNull { it.id == id }
            assertTrue("falta la voz $id", spec != null)
            assertEquals("idioma de $id", lang, spec!!.lang)
            assertEquals("género de $id", gender, spec.gender)
            assertTrue("label sin nombre: ${spec.label}", spec.label.contains("·"))
        }
        // El japonés es multi-speaker y la voz femenina es el speaker 0.
        assertEquals(0, PiperVoiceManager.CATALOG.first { it.id == "ja_JP-hi_fi_captain-medium" }.speakerId)
        // Unicidad de ids.
        val ids = PiperVoiceManager.CATALOG.map { it.id }
        assertEquals("ids duplicados", ids.size, ids.toSet().size)
    }

    @Test
    fun piper_catalogo_cubre_todos_los_idiomas_de_la_app() {
        // v0.9: TODOS los idiomas destino de Languages.kt tienen al menos una voz Piper.
        for (code in Languages.TARGETS.map { it.code }) {
            assertTrue(
                "falta voz Piper para $code",
                PiperVoiceManager.CATALOG.any { it.lang == code }
            )
        }
        // Y el catálogo no declara idiomas que la app no ofrezca.
        val appCodes = Languages.TARGETS.map { it.code }.toSet()
        for (s in PiperVoiceManager.CATALOG) {
            assertTrue("el catálogo tiene un idioma ajeno a la app: ${s.lang}", s.lang in appCodes)
        }
    }

    @Test
    fun piper_catalogo_cubre_los_dieciseis_idiomas_nuevos() {
        // v0.9: una voz por cada idioma nuevo. Género según la medición F0 real (ver doc).
        // Femeninas: bn/ca/cs/el/id/nl/no/pt/sv/th/uk/vi.
        // Masculinas (única o todas las opciones masculinas): da/fa/fi/he.
        val expected = mapOf(
            "bn" to ("bn_BD-google-medium" to "F"),
            "ca" to ("ca_ES-upc_ona-medium" to "F"),
            "cs" to ("cs_CZ-kasandra-medium" to "F"),
            "da" to ("da_DK-talesyntese-medium" to "M"),
            "el" to ("el_GR-joy-medium" to "F"),
            "fa" to ("fa_IR-amir-medium" to "M"),
            "fi" to ("fi_FI-harri-medium" to "M"),
            "he" to ("he_IL-saspeech-medium" to "M"),
            "id" to ("id_ID-news_tts-medium" to "F"),
            "nl" to ("nl_BE-nathalie-medium" to "F"),
            "no" to ("no_NO-nvcc-medium" to "F"),
            "pt" to ("pt_PT-tugão-medium" to "F"),
            "sv" to ("sv_SE-alma-medium" to "F"),
            "th" to ("th_TH-tsync2-medium" to "F"),
            "uk" to ("uk_UA-tetiana-high" to "F"),
            "vi" to ("vi_VN-25hours_single-low" to "F")
        )
        for ((lang, pair) in expected) {
            val (id, gender) = pair
            val spec = PiperVoiceManager.CATALOG.firstOrNull { it.id == id }
            assertTrue("falta la voz $id", spec != null)
            assertEquals("idioma de $id", lang, spec!!.lang)
            assertEquals("género de $id", gender, spec.gender)
            assertTrue("label sin nombre: ${spec.label}", spec.label.contains("·"))
        }
        // Multi-speaker: la voz femenina está en un speaker concreto.
        assertEquals(12, PiperVoiceManager.CATALOG.first { it.id == "bn_BD-google-medium" }.speakerId)
        assertEquals(3, PiperVoiceManager.CATALOG.first { it.id == "no_NO-nvcc-medium" }.speakerId)
        // Unicidad de ids (catálogo completo, ahora con muchos más idiomas).
        val ids = PiperVoiceManager.CATALOG.map { it.id }
        assertEquals("ids duplicados", ids.size, ids.toSet().size)
    }

    @Test
    fun piper_catalogo_voces_crudas_v09_sin_paquete() {
        // Voces v0.9 sin paquete sherpa (HTTP 404): tarUrl vacío + ruta cruda correcta.
        val raw = listOf(
            "bn_BD-google-medium" to "bn/bn_BD/google/medium",
            "cs_CZ-kasandra-medium" to "cs/cs_CZ/kasandra/medium",
            "el_GR-joy-medium" to "el/el_GR/joy/medium",
            "he_IL-saspeech-medium" to "he/he_IL/saspeech/medium",
            "no_NO-nvcc-medium" to "no/no_NO/nvcc/medium",
            "pt_PT-tugão-medium" to "pt/pt_PT/tugão/medium",
            "th_TH-tsync2-medium" to "th/th_TH/tsync2/medium",
            "uk_UA-tetiana-high" to "uk/uk_UA/tetiana/high"
        )
        for ((id, path) in raw) {
            val s = PiperVoiceManager.CATALOG.first { it.id == id }
            assertEquals("tarUrl debe estar vacío en $id", "", s.tarUrl)
            assertEquals(
                "onnxUrl incorrecta en $id",
                "https://huggingface.co/rhasspy/piper-voices/resolve/main/$path/$id.onnx",
                s.onnxUrl
            )
            assertTrue("approxBytes irreal en $id", s.approxBytes > 10_000_000L)
        }
    }

    @Test
    fun piper_catalogo_voces_crudas_sin_paquete() {
        // ko/bg/ja no tienen paquete sherpa (HTTP 404): tarUrl vacío + URL cruda correcta.
        val raw = listOf(
            "ko_KR-kss-medium" to "ko/ko_KR/kss/medium",
            "bg_BG-dimitar-medium" to "bg/bg_BG/dimitar/medium",
            "ja_JP-hi_fi_captain-medium" to "ja/ja_JP/hi_fi_captain/medium"
        )
        for ((id, path) in raw) {
            val s = PiperVoiceManager.CATALOG.first { it.id == id }
            assertEquals("tarUrl debe estar vacío en $id", "", s.tarUrl)
            assertEquals(
                "onnxUrl incorrecta en $id",
                "https://huggingface.co/rhasspy/piper-voices/resolve/main/$path/$id.onnx",
                s.onnxUrl
            )
            assertEquals(
                "jsonUrl incorrecta en $id",
                "https://huggingface.co/rhasspy/piper-voices/resolve/main/$path/$id.onnx.json",
                s.jsonUrl
            )
            assertTrue("approxBytes irreal en $id", s.approxBytes > 10_000_000L)
        }
    }

    // ---------------- Piper: paquetes sherpa (.tar.bz2) ----------------

    @Test
    fun piper_catalogo_usa_paquetes_sherpa() {
        for (s in PiperVoiceManager.CATALOG) {
            if (s.tarUrl.isNotBlank()) {
                assertEquals(
                    "URL tar incorrecta para ${s.id}",
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-${s.id}.tar.bz2",
                    s.tarUrl
                )
                assertTrue(s.tarUrl.endsWith(".tar.bz2"))
            }
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
    fun piper_tar_extrae_paquete_sherpa_streaming() {        val root = File(System.getProperty("java.io.tmpdir"), "pipertar-test-${System.nanoTime()}")
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

    // ---------------- Variante FULL (BundledAssets) ----------------

    @Test
    fun bundled_dest_relative_mapea_files_y_piper() {
        // Modelos sueltos -> raíz de filesDir.
        assertEquals("Qwen3.5-0.8B-Q4_K_M.gguf", BundledAssets.destRelative("files/Qwen3.5-0.8B-Q4_K_M.gguf"))
        assertEquals("ggml-base.bin", BundledAssets.destRelative("files/ggml-base.bin"))
        // Voces -> piper_voices/<id>/…
        assertEquals(
            "piper_voices/es_AR-daniela-high/model.onnx",
            BundledAssets.destRelative("piper/es_AR-daniela-high/model.onnx")
        )
        assertEquals(
            "piper_voices/en_US-hfc_female-medium/espeak-ng-data/phondata",
            BundledAssets.destRelative("piper/en_US-hfc_female-medium/espeak-ng-data/phondata")
        )
        // Entradas inválidas -> null.
        assertNull(BundledAssets.destRelative("manifest.json"))
        assertNull(BundledAssets.destRelative("piper/solo-id"))
        assertNull(BundledAssets.destRelative("otra/ruta.bin"))
    }

    // ---------------- TextChunker (textos largos) ----------------

    @Test
    fun chunker_texto_corto_devuelve_un_trozo_identico() {
        val corto = "Hola mundo, esto es una prueba corta."
        val chunks = TextChunker.chunk(corto)
        assertEquals(1, chunks.size)
        assertEquals(corto, chunks[0])
        // También por debajo del presupuesto explícito.
        assertEquals(listOf(corto), TextChunker.chunk(corto, 100))
    }

    @Test
    fun chunker_texto_vacio_no_produce_trozos() {
        assertTrue(TextChunker.chunk("").isEmpty())
    }

    @Test
    fun chunker_texto_largo_trocea_sin_cortar_palabras_y_respeta_presupuesto() {
        val maxChars = 120
        val oracion = "Esta es una oración de relleno que se repite para alargar el texto de prueba. "
        val texto = oracion.repeat(12).trim()
        assertTrue("el texto debe ser largo", texto.length > maxChars)

        val chunks = TextChunker.chunk(texto, maxChars)
        assertTrue("debe haber varios trozos", chunks.size > 1)
        for (c in chunks) {
            assertTrue("trozo <= $maxChars (era ${c.length})", c.length <= maxChars)
        }
        assertFalse("ningún trozo vacío", chunks.any { it.isBlank() })

        // No se ha cortado ninguna palabra ni se ha perdido texto: la secuencia de
        // palabras del original y de los trozos recomuestos coincide (normalizando
        // los espacios/saltos).
        val palabrasOriginal = texto.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val palabrasTrozos = TextChunker.recompose(chunks)
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        assertEquals(palabrasOriginal, palabrasTrozos)
    }

    @Test
    fun chunker_una_sola_frase_larga_se_parte_por_palabras_sin_cortar() {
        val maxChars = 60
        // Sin signos de fin de frase -> solo puede partirse por palabras.
        val texto = "uno dos tres cuatro cinco seis siete ocho nueve diez once doce trece catorce quince dieciseis"
        val chunks = TextChunker.chunk(texto, maxChars)
        assertTrue(chunks.size > 1)
        for (c in chunks) assertTrue(c.length <= maxChars)
        for (c in chunks) assertFalse("no debe partir una palabra", c.endsWith("-"))
        assertEquals(
            texto.split(" "),
            TextChunker.recompose(chunks).split(Regex("\\s+"))
        )
    }

    @Test
    fun chunker_recomposicion_conserva_parrafos() {
        val maxChars = 90
        val p1 = "Primera oración del primer párrafo. Segunda oración del primer párrafo."
        val p2 = "Primera oración del segundo párrafo. Segunda oración del segundo párrafo."
        val texto = "$p1\n\n$p2"
        assertTrue(texto.length > maxChars)

        val chunks = TextChunker.chunk(texto, maxChars)
        assertTrue(chunks.size > 1)
        for (c in chunks) assertTrue(c.length <= maxChars)

        val recom = TextChunker.recompose(chunks)
        // Contenido íntegro (normalizando espacios).
        assertEquals(
            texto.replace(Regex("\\s+"), " ").trim(),
            recom.replace(Regex("\\s+"), " ").trim()
        )
        // Ambas oraciones de ambos párrafos están y en orden.
        val iPrim = recom.indexOf("Segunda oración del primer párrafo.")
        val iSeg = recom.indexOf("Primera oración del segundo párrafo.")
        assertTrue(iPrim in 0 until iSeg)
        // El salto de párrafo se conserva como separación (no todo en una línea).
        assertTrue("debe quedar un salto entre párrafos", recom.substring(iPrim, iSeg).contains("\n"))
    }

    @Test
    fun chunker_encaja_texto_largo_en_el_presupuesto_del_contexto() {
        // Un texto realista largo: debe caber en trozos holgados tipo ~350-400 tokens.
        val texto = ("The quick brown fox jumps over the lazy dog. " +
            "Pack my box with five dozen liquor jugs. ").repeat(20).trim()
        val chunks = TextChunker.chunk(texto)
        assertTrue(chunks.size > 1)
        for (c in chunks) assertTrue(c.length <= TextChunker.DEFAULT_MAX_CHARS)
        assertEquals(
            texto.split(Regex("\\s+")),
            TextChunker.recompose(chunks).split(Regex("\\s+"))
        )
    }

    @Test
    fun bundled_voices_son_las_por_defecto_del_catalogo() {
        // La APK FULL empaqueta la voz por defecto de ES y de EN del catálogo.
        assertEquals("es_AR-daniela-high", PiperVoiceManager.CATALOG.first { it.lang == "es" }.id)
        assertEquals("en_US-hfc_female-medium", PiperVoiceManager.CATALOG.first { it.lang == "en" }.id)
        assertEquals(listOf("es_AR-daniela-high", "en_US-hfc_female-medium"), BundledAssets.VOICES)
        for (id in BundledAssets.VOICES) {
            val spec = PiperVoiceManager.specFor(id)
            assertTrue("la voz bundleada $id debe estar en el catálogo", spec != null)
            assertTrue("la voz bundleada $id debe tener paquete sherpa", spec!!.tarUrl.endsWith(".tar.bz2"))
        }
    }
}
