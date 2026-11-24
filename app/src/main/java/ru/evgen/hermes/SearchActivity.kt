package ru.evgen.hermes

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Search across every session's history, not just the rows already loaded.
 *
 * The list's own search box filters what the phone has in memory — a session from
 * last week simply is not there, so a title that certainly exists returned nothing.
 * This screen asks the gateway (`/api/sessions/search`), which searches the stored
 * transcripts and returns a snippet with the matching line.
 *
 * Tapping a hit opens that session. Jumping to the exact message would need the
 * message id, which the endpoint does not return — so the chat opens at the end and
 * the in-chat search can take it from there.
 */
class SearchActivity : Activity() {

    private lateinit var api: ApiClient
    private lateinit var input: EditText
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var adapter: HitAdapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = ApiClient.get(this)
        setContentView(buildUi())
        val initial = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        if (initial.isNotEmpty()) {
            input.setText(initial)
            input.setSelection(initial.length)
            runSearch(initial)
        } else {
            input.requestFocus()
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.app_bg))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(6))
        }
        input = EditText(this).apply {
            hint = getString(R.string.search_history_hint)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
            setBackgroundResource(R.drawable.bg_search)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    hideKeyboard()
                    runSearch(text.toString().trim())
                    true
                } else false
            }
        }
        bar.addView(input)
        bar.addView(TextView(this).apply {
            text = getString(R.string.cancel)
            textSize = 14f
            setTextColor(getColor(R.color.accent))
            setPadding(dp(14), dp(6), 0, dp(6))
            setOnClickListener { finish() }
        })
        root.addView(bar)

        empty = TextView(this).apply {
            text = getString(R.string.search_history_empty)
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(dp(16), dp(16), dp(16), 0)
            visibility = View.GONE
        }
        root.addView(empty)

        adapter = HitAdapter { hit -> open(hit) }
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@SearchActivity)
            adapter = this@SearchActivity.adapter
            setPadding(0, dp(4), 0, dp(16))
            clipToPadding = false
        }
        val scroll = ScrollView(this)
        root.addView(list, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        return root
    }

    /** Debounced: a request per keystroke would hammer the gateway for nothing. */
    private fun runSearch(query: String) {
        if (query.length < 2) {
            adapter.submit(emptyList())
            empty.visibility = View.GONE
            return
        }
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(250)
            val hits = withContext(Dispatchers.IO) { api.searchHistory(query) }
            if (isFinishing) return@launch
            adapter.submit(hits)
            empty.visibility = if (hits.isEmpty()) View.VISIBLE else View.GONE
            empty.text = getString(R.string.search_history_nothing, query)
        }
    }

    private fun open(hit: SearchHit) {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_SESSION_ID, hit.sessionId)
            }
        )
        finish()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        searchJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_QUERY = "ru.evgen.hermes.SEARCH_QUERY"
    }
}

/** One line of a transcript that matched, with the session it belongs to. */
data class SearchHit(
    val sessionId: String,
    val title: String,
    val snippet: String,
    val role: String,
    val source: String
) {
    companion object {
        fun from(o: org.json.JSONObject): SearchHit? {
            val id = o.optString("session_id").ifEmpty { o.optString("id") }
            if (id.isEmpty()) return null
            val snippet = o.optString("snippet")
                .replace(Regex("\\s+"), " ")
                .trim()
            return SearchHit(
                sessionId = id,
                title = o.optString("title").ifEmpty { snippet.take(40) },
                snippet = snippet,
                role = o.optString("role"),
                source = o.optString("source")
            )
        }
    }
}

/** Rows: session title, the matching snippet, and where it came from. */
private class HitAdapter(
    private val onClick: (SearchHit) -> Unit
) : RecyclerView.Adapter<HitAdapter.Holder>() {

    private val items = ArrayList<SearchHit>()

    fun submit(fresh: List<SearchHit>) {
        items.clear()
        items.addAll(fresh)
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_row)
            val p = (12 * context.resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        return Holder(box)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position], onClick)

    class Holder(private val box: LinearLayout) : RecyclerView.ViewHolder(box) {
        fun bind(hit: SearchHit, onClick: (SearchHit) -> Unit) {
            val context = box.context
            val density = context.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()
            box.removeAllViews()
            box.addView(TextView(context).apply {
                text = hit.title
                textSize = 14f
                setTextColor(context.getColor(R.color.text_primary))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            box.addView(TextView(context).apply {
                text = hit.snippet
                textSize = 12f
                setTextColor(context.getColor(R.color.text_secondary))
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            })
            val channel = Channel.label(hit.source)
            box.addView(TextView(context).apply {
                text = listOfNotNull(channel.takeIf { it.isNotEmpty() }, hit.role.takeIf { it.isNotEmpty() })
                    .joinToString(" · ")
                textSize = 10f
                setTextColor(context.getColor(R.color.accent))
                setPadding(0, dp(3), 0, 0)
                visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            })
            box.setOnClickListener { onClick(hit) }
        }
    }
}
