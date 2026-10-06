package com.zota.traductor

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Conversion on-device de un modelo Piper crudo (.onnx + .onnx.json) al formato
 * que consume sherpa-onnx/VITS:
 *
 *   1) `tokens.txt` a partir de `phoneme_id_map` del .onnx.json.
 *   2) `metadata_props` embebidas en el propio .onnx (campo 14 de `ModelProto`):
 *      comment=piper, voice=<espeak voice>, sample_rate, n_speakers, has_espeak…​
 *
 * El truco: en protobuf el orden de los campos no importa y los campos repetidos
 * pueden aparecer varias veces, así que basta con **añadir** al final del archivo
 * las entradas codificadas a mano (no hace falta cargar el .onnx de 63 MB en RAM).
 *
 * Todo es Kotlin puro (sin Android) para poder verificar el encoder en tests host.
 */
object OnnxMeta {

    /** Entero como varint protobuf (base-128, little-endian de 7 bits). */
    fun varint(value: Long): ByteArray {
        var v = value
        val out = ByteArrayOutputStream(10)
        while (true) {
            val b = (v and 0x7FL).toInt()
            v = v ushr 7
            if (v != 0L) out.write(b or 0x80) else { out.write(b); break }
        }
        return out.toByteArray()
    }

    /** Campo protobuf "length-delimited" (wire type 2). */
    fun bytesField(fieldNo: Int, payload: ByteArray): ByteArray {
        val tag = varint(((fieldNo shl 3) or 2).toLong())
        return tag + varint(payload.size.toLong()) + payload
    }

    /** Campo protobuf string (también wire type 2). */
    fun stringField(fieldNo: Int, s: String): ByteArray =
        bytesField(fieldNo, s.toByteArray(Charsets.UTF_8))

    /**
     * `StringStringEntryProto { 1: key, 2: value }` dentro de
     * `ModelProto.metadata_props` (campo 14).
     */
    fun metadataProp(key: String, value: String): ByteArray {
        val inner = stringField(1, key) + stringField(2, value)
        return bytesField(14, inner)
    }

    fun encodeMetadataProps(meta: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((k, v) in meta) out.write(metadataProp(k, v))
        return out.toByteArray()
    }

    /**
     * Añade las metadata_props al final del `.onnx` (append, in-place).
     * Es seguro asumir que un piper .onnx exportado no trae metadata propia; si la
     * trajera, las repetidas no invalidan el protobuf (se usa el marcador `.meta`
     * para no inyectar dos veces).
     */
    fun appendMetadata(file: File, meta: List<Pair<String, String>>) {
        val blob = encodeMetadataProps(meta)
        FileOutputStream(file, true).use { it.write(blob); it.flush() }
    }

    /**
     * ¿El `.onnx` ya trae la metadata piper/sherpa incrustada?
     * Las `metadata_props` (protobuf) van **al final** del ModelProto, así que basta
     * con leer la cola del archivo (64 KB) y buscar las claves conocidas. Sirve para
     * no inyectar metadata duplicada en los paquetes oficiales de sherpa-onnx, que
     * ya vienen convertidos.
     */
    fun hasPiperMetadata(file: File): Boolean {
        if (!file.isFile || file.length() < 1024) return false
        val len = minOf(file.length(), 64L * 1024).toInt()
        val buf = ByteArray(len)
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(file.length() - len)
                raf.readFully(buf)
            }
        } catch (_: Throwable) {
            return false
        }
        val text = String(buf, Charsets.ISO_8859_1)
        return text.contains("comment") && text.contains("piper") &&
            (text.contains("sample_rate") || text.contains("has_espeak"))
    }

    /** tokens.txt estilo sherpa: una línea `<symbol> <id>` por entrada. */
    fun tokensFromPhonemeIdMap(entries: List<Pair<String, Int>>): String {
        val sb = StringBuilder()
        for ((symbol, id) in entries) sb.append(symbol).append(' ').append(id).append('\n')
        return sb.toString()
    }

    /** Metadata que espera sherpa-onnx para un modelo piper (mismos campos que el script oficial). */
    fun piperMetadata(
        languageEnglish: String,
        espeakVoice: String,
        numSpeakers: Int,
        sampleRate: Int
    ): List<Pair<String, String>> = listOf(
        "model_type" to "vits",
        "comment" to "piper",
        "language" to languageEnglish,
        "voice" to espeakVoice,
        "has_espeak" to "1",
        "n_speakers" to numSpeakers.toString(),
        "sample_rate" to sampleRate.toString()
    )
}
