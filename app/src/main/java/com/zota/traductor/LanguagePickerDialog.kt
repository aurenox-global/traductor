package com.zota.traductor

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Desplegable de idiomas estilo Google Translate: bandera + nombre, con buscador
 * y marca de seleccion.
 */
object LanguagePickerDialog {

    fun show(
        ctx: Context,
        langs: List<Lang>,
        selectedCode: String,
        onPick: (Lang) -> Unit
    ) {
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_language, null)
        val search = view.findViewById<EditText>(R.id.editLanguageSearch)
        val list = view.findViewById<ListView>(R.id.listLanguages)

        var filtered: List<Lang> = langs

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = filtered.size
            override fun getItem(position: Int): Any = filtered[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: LayoutInflater.from(ctx)
                    .inflate(R.layout.item_language, parent, false)
                val lang = filtered[position]
                v.findViewById<TextView>(R.id.langLabel).text = lang.label
                v.findViewById<ImageView>(R.id.langCheck).visibility =
                    if (lang.code == selectedCode) View.VISIBLE else View.GONE
                return v
            }
        }
        list.adapter = adapter

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.lang_picker_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        list.setOnItemClickListener { _, _, position, _ ->
            onPick(filtered[position])
            dialog.dismiss()
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                filtered = if (q.isEmpty()) langs else langs.filter {
                    it.name.contains(q, ignoreCase = true) || it.code.contains(q, ignoreCase = true)
                }
                adapter.notifyDataSetChanged()
            }
        })

        dialog.show()
    }

    /** Atajo para usar dentro de un LinearLayout ya inflado (no lo usamos, reservado). */
    @Suppress("unused")
    fun attach(ll: LinearLayout) { /* no-op */ }
}
