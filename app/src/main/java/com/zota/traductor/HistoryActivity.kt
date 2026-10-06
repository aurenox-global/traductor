package com.zota.traductor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.zota.traductor.databinding.ActivityHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Historial local de traducciones. Permite escuchar, copiar y reutilizar una entrada.
 */
class HistoryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ORIGINAL = "extra_original"
        const val EXTRA_TRANSLATED = "extra_translated"
        const val EXTRA_SOURCE = "extra_source"
        const val EXTRA_TARGET = "extra_target"
    }

    private lateinit var b: ActivityHistoryBinding
    private val tts = TtsHelper(this)
    private var entries: List<HistEntry> = emptyList()

    private val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }
        b.btnClearHistory.setOnClickListener {
            HistoryStore.clear(this)
            load()
        }

        tts.init { }

        b.listHistory.adapter = adapter
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        entries = HistoryStore.list(this)
        b.txtEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        (b.listHistory.adapter as? BaseAdapter)?.notifyDataSetChanged()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount(): Int = entries.size
        override fun getItem(position: Int): Any = entries[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(this@HistoryActivity)
                .inflate(R.layout.item_history, parent, false)

            val e = entries[position]
            v.findViewById<TextView>(R.id.histOriginal).text = e.original
            v.findViewById<TextView>(R.id.histTranslated).text = e.translated
            val src = Languages.byCode(e.sourceCode).name
            val dst = Languages.byCode(e.targetCode).name
            v.findViewById<TextView>(R.id.histMeta).text =
                "$src → $dst · ${fmt.format(Date(e.ts))}"

            v.findViewById<ImageButton>(R.id.histSpeak).setOnClickListener {
                tts.speak(e.translated, Languages.localeTag(e.targetCode))
            }
            v.findViewById<ImageButton>(R.id.histCopy).setOnClickListener {
                copyToClipboard(e.translated)
            }
            v.findViewById<ImageButton>(R.id.histUse).setOnClickListener {
                val data = android.content.Intent().apply {
                    putExtra(EXTRA_ORIGINAL, e.original)
                    putExtra(EXTRA_TRANSLATED, e.translated)
                    putExtra(EXTRA_SOURCE, e.sourceCode)
                    putExtra(EXTRA_TARGET, e.targetCode)
                }
                setResult(RESULT_OK, data)
                finish()
            }
            return v
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("traduccion", text))
        Toast.makeText(this, R.string.status_copied, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.shutdown()
    }
}
