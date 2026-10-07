package com.zota.traductor

/**
 * Mapeo ISO-639-1 (el que usa la app) -> código de idioma de NLLB-200
 * (`<iso639-3>_<script>`, p.ej. `spa_Latn`). Kotlin puro, testeable en JVM.
 */
object NllbLangs {

    private val MAP: Map<String, String> = mapOf(
        "es" to "spa_Latn", "en" to "eng_Latn", "fr" to "fra_Latn", "de" to "deu_Latn",
        "it" to "ita_Latn", "pt" to "por_Latn", "ru" to "rus_Cyrl", "zh" to "zho_Hans",
        "ja" to "jpn_Jpan", "ko" to "kor_Hang", "ar" to "arb_Arab", "hi" to "hin_Deva",
        "tr" to "tur_Latn", "nl" to "nld_Latn", "pl" to "pol_Latn", "uk" to "ukr_Cyrl",
        "ro" to "ron_Latn", "bg" to "bul_Cyrl", "hu" to "hun_Latn", "sv" to "swe_Latn",
        "da" to "dan_Latn", "fi" to "fin_Latn", "no" to "nob_Latn", "cs" to "ces_Latn",
        "el" to "ell_Grek", "he" to "heb_Hebr", "fa" to "pes_Arab", "id" to "ind_Latn",
        "vi" to "vie_Latn", "th" to "tha_Thai", "bn" to "ben_Beng", "ca" to "cat_Latn",
        // extras
        "sr" to "srp_Cyrl", "hr" to "hrv_Latn", "sk" to "slk_Latn", "sl" to "slv_Latn",
        "lt" to "lit_Latn", "lv" to "lvs_Latn", "et" to "est_Latn", "ms" to "zsm_Latn",
        "tl" to "tgl_Latn", "sw" to "swh_Latn", "ur" to "urd_Arab", "ta" to "tam_Taml",
        "te" to "tel_Telu", "ml" to "mal_Mlym", "gu" to "guj_Gujr", "kn" to "kan_Knda",
        "mr" to "mar_Deva", "ne" to "npi_Deva", "si" to "sin_Sinh", "km" to "khm_Khmr",
        "my" to "mya_Mymr", "ka" to "kat_Geor", "hy" to "hye_Armn", "az" to "azj_Latn",
        "kk" to "kaz_Cyrl", "uz" to "uzn_Latn", "af" to "afr_Latn", "is" to "isl_Latn",
        "ga" to "gle_Latn", "cy" to "cym_Latn", "eu" to "eus_Latn", "gl" to "glg_Latn"
    )

    private val INVERSE: Map<String, String> = MAP.entries.associate { (k, v) -> v to k }

    /**
     * Código NLLB para un idioma de la app. Acepta ya un código NLLB.
     * `auto` no existe en NLLB (es un modelo supervisado sin detección), así que
     * se resuelve con [fallback] (normalmente el último idioma detectado por
     * Whisper) o inglés.
     */
    fun toNllb(iso: String, fallback: String = "eng_Latn"): String {
        if (iso.contains('_')) return iso
        if (iso == Languages.AUTO.code) return fallback
        return MAP[iso.lowercase()] ?: fallback
    }

    /** ISO-639-1 a partir de un código NLLB (o null). */
    fun toIso(nllb: String): String? = INVERSE[nllb]

    fun isSupported(iso: String): Boolean = MAP.containsKey(iso.lowercase())
}
