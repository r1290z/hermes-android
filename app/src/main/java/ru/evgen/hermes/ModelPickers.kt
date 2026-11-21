package ru.evgen.hermes

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The model picker sheet: a search field on top and one section per provider.
 *
 * The list is fully materialised (header rows + model rows) and filtered
 * client-side — `model.options` arrives in one reply and routerai alone brings
 * 500+ slugs, which a phone ListView handles fine but a nested dialog with
 * per-provider expansion does not.
 */
class ModelPickerDialog(
    private val activity: Activity,
    providers: List<ApiClient.ModelProvider>,
    private val current: String?,
    private val onPick: (String) -> Unit
) : AlertDialog.Builder(activity) {

    private class Row(val isHeader: Boolean, val text: String, val slug: String, val provider: String)

    private val allRows = buildList {
        for (p in providers) {
            if (!p.authenticated) continue
            add(Row(true, "${p.name} (${p.models.size})", "", p.slug))
            for (m in p.models.sorted()) add(Row(false, m, m, p.slug))
        }
    }
    private var shown = allRows.toList()
    private var dialogRef: AlertDialog? = null

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = shown[position]
            if (row.isHeader) {
                return (convertView as? TextView ?: TextView(activity).apply {
                    setTypeface(typeface, Typeface.BOLD)
                    textSize = 12f
                    setPadding(48, 24, 48, 8)
                }).apply { text = row.text }
            }
            val box = convertView as? CheckBox ?: CheckBox(activity).apply {
                setPadding(64, 4, 32, 4)
                textSize = 14f
            }
            box.text = row.text
            box.isChecked = row.slug == current
            box.setOnCheckedChangeListener(null)
            return box
        }
        override fun isEnabled(position: Int) = !shown[position].isHeader
        override fun areAllItemsEnabled() = false
    }

    init {
        setTitle(R.string.model_pick)
        val search = EditText(activity).apply {
            hint = activity.getString(R.string.model_search_hint)
            setSingleLine(true)
            setPadding(48, 24, 48, 24)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty().trim().lowercase())
                override fun beforeTextChanged(a: CharSequence?, x: Int, y: Int, z: Int) {}
                override fun onTextChanged(a: CharSequence?, x: Int, y: Int, z: Int) {}
            })
        }
        val list = ListView(activity).apply { this.adapter = this@ModelPickerDialog.adapter }
        val wrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setView(wrap)
        setNegativeButton(R.string.cancel, null)
        list.setOnItemClickListener { _, _, position, _ ->
            val row = shown[position]
            if (row.isHeader) return@setOnItemClickListener
            onPick(row.slug)
            dialogRef?.dismiss()
        }
    }

    override fun show(): AlertDialog {
        val dialog = create()
        dialogRef = dialog
        dialog.show()
        return dialog
    }

    private fun filter(q: String) {
        shown = if (q.isEmpty()) allRows else {
            // Search keeps a provider's header only when one of its models matches.
            val hits = allRows.filter { !it.isHeader && it.text.lowercase().contains(q) }
            val providersOf = hits.map { it.provider }.toSet()
            allRows.filter { !it.isHeader && it.provider in providersOf }
        }
        adapter.notifyDataSetChanged()
    }
}

/**
 * «Ещё» → «Режим»: per-session switches read through `config.get` and written
 * with `config.set`. The approvals row carries a warning because the server
 * persists it into the profile's config.yaml — it is NOT session-scoped.
 */
class ModesDialog(
    private val activity: Activity,
    private val api: ApiClient,
    private val sessionId: String,
    private val onSet: (key: String, value: String) -> Unit
) : AlertDialog.Builder(activity) {

    private val box = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 24, 48, 24)
    }
    private var dialogRef: AlertDialog? = null

    init {
        setTitle(R.string.modes)
        setView(box)
        setPositiveButton(R.string.close, null)
        addSwitch("yolo", "Yolo — выполнять без вопросов") { if (it) "true" else "false" }
        addSwitch("show_reasoning", "Показывать размышления") { if (it) "true" else "false" }
        addReasoning()
        addApprovals()
    }

    override fun show(): AlertDialog {
        val dialog = create()
        dialogRef = dialog
        dialog.show()
        return dialog
    }

    private fun addSwitch(key: String, label: String, wire: (Boolean) -> String) {
        val sw = Switch(activity).apply { text = label; textSize = 14f; isSaveEnabled = false }
        // The listener is wired only once the server's value has landed, so the
        // initial flip is never mistaken for a user tap.
        fun listen() {
            sw.setOnCheckedChangeListener { _, checked ->
                apply(key, sw, wire(checked))
            }
        }
        Thread {
            runCatching {
                val value = api.getMode(sessionId, key)
                activity.runOnUiThread {
                    sw.isChecked = value == "1" || value == "true"
                    listen()
                }
            }
        }.start()
        box.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Apply + rollback: on failure the switch snaps back with a toast. */
    private fun apply(key: String, sw: Switch, value: String) {
        sw.isEnabled = false
        Thread {
            runCatching {
                val ok = api.setMode(sessionId, key, value)
                activity.runOnUiThread {
                    sw.isEnabled = true
                    if (!ok) {
                        sw.setOnCheckedChangeListener(null)
                        sw.isChecked = !(value == "1" || value == "true")
                        listenBack(key, sw)
                        Toast.makeText(activity, api.error ?: "Не применилось", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    private fun listenBack(key: String, sw: Switch) {
        sw.setOnCheckedChangeListener { _, checked ->
            apply(key, sw, if (checked) "true" else "false")
        }
    }

    private fun addReasoning() {
        val label = TextView(activity).apply {
            text = "Размышления модели"
            textSize = 14f
            setPadding(0, 24, 0, 0)
        }
        val current = TextView(activity).apply {
            textSize = 12f
            setTextColor(activity.getColor(R.color.text_secondary))
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setOnClickListener {
                val options = arrayOf("off", "low", "medium", "high")
                AlertDialog.Builder(activity)
                    .setSingleChoiceItems(options, options.indexOf(current.tag)) { d, which ->
                        onSet("reasoning", options[which])
                        current.text = labelOf("reasoning", options[which])
                        current.tag = options[which]
                        d.dismiss()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
        box.addView(label)
        box.addView(current)
        box.addView(row)
        Thread {
            runCatching {
                val v = api.getMode(sessionId, "reasoning")
                activity.runOnUiThread {
                    current.text = labelOf("reasoning", v ?: "")
                    current.tag = v ?: ""
                }
            }
        }.start()
    }

    private fun addApprovals() {
        val label = TextView(activity).apply {
            text = "Подтверждения: …"
            textSize = 14f
            setPadding(0, 24, 0, 0)
        }
        val warn = TextView(activity).apply {
            text = "Внимание: пишется в config.yaml профиля и действует на ВСЕ сеансы профиля."
            textSize = 12f
            setTextColor(activity.getColor(R.color.error_text))
            setPadding(0, 4, 0, 0)
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label)
            addView(warn)
            setOnClickListener {
                val options = arrayOf("manual", "smart", "off")
                AlertDialog.Builder(activity)
                    .setSingleChoiceItems(options.map { labelOf("approvals", it) }.toTypedArray(),
                        options.indexOf(label.tag)) { d, which ->
                        onSet("approvals.mode", options[which])
                        label.text = "Подтверждения: " + labelOf("approvals", options[which])
                        label.tag = options[which]
                        d.dismiss()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
        box.addView(row)
        Thread {
            runCatching {
                val v = api.getMode(sessionId, "approvals.mode")
                activity.runOnUiThread {
                    label.text = "Подтверждения: " + labelOf("approvals", v ?: "")
                    label.tag = v ?: ""
                }
            }
        }.start()
    }

    private fun labelOf(kind: String, value: String): String = when (kind) {
        "reasoning" -> when (value) {
            "off" -> "выкл"
            "low" -> "низкие"
            "medium" -> "средние"
            "high" -> "глубокие"
            else -> value
        }
        else -> when (value) {
            "manual" -> "спрашивать всегда"
            "smart" -> "умные"
            "off" -> "не спрашивать"
            else -> value
        }
    }
}
