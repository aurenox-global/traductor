package com.zota.traductor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Entrada del historial local de traducciones. */
data class HistEntry(
    val original: String,
    val translated: String,
    val sourceCode: String,
    val targetCode: String,
    val ts: Long
)

/**
 * Historial local (SharedPreferences + JSON). Guarda las ultimas N traducciones.
 * No sale del dispositivo.
 */
object HistoryStore {

    private const val PREF = "traductor_history"
    private const val KEY = "entries"
    private const val MAX = 200

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun add(ctx: Context, e: HistEntry) {
        if (e.original.isBlank() || e.translated.isBlank()) return
        val list = ArrayList<HistEntry>(list(ctx))
        // evita duplicados consecutivos exactos
        if (list.firstOrNull()?.let { it.original == e.original && it.targetCode == e.targetCode } == true) {
            list.removeAt(0)
        }
        list.add(0, e)
        while (list.size > MAX) list.removeAt(list.size - 1)
        save(ctx, list)
    }

    fun list(ctx: Context): List<HistEntry> {
        val raw = p(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<HistEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    HistEntry(
                        original = o.optString("o"),
                        translated = o.optString("t"),
                        sourceCode = o.optString("s", "auto"),
                        targetCode = o.optString("d", "es"),
                        ts = o.optLong("ts", 0L)
                    )
                )
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun clear(ctx: Context) {
        p(ctx).edit().remove(KEY).apply()
    }

    private fun save(ctx: Context, list: List<HistEntry>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject()
                    .put("o", e.original)
                    .put("t", e.translated)
                    .put("s", e.sourceCode)
                    .put("d", e.targetCode)
                    .put("ts", e.ts)
            )
        }
        p(ctx).edit().putString(KEY, arr.toString()).apply()
    }
}
