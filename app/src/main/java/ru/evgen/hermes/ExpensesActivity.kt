package ru.evgen.hermes

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * What the agent has cost: totals, days, and which model ate the money.
 *
 * The gateway reports tokens exactly and money not at all — `estimated_cost` is 0 for
 * every custom-provider row because it has no price table for PlusVibe. So the panel
 * prices the tokens here (see [Prices]), and says so instead of presenting a guess as
 * a bill. The rates are editable, because a provider reprices a lane without asking.
 */
class ExpensesActivity : Activity() {

    private lateinit var api: ApiClient
    private lateinit var total: TextView
    private lateinit var subtitle: TextView
    private lateinit var tokens: TextView
    private lateinit var cacheNote: TextView
    private lateinit var status: TextView
    private lateinit var chart: DailyChart
    private lateinit var models: LinearLayout
    private lateinit var periods: LinearLayout

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Days of history; the chips below are these three. */
    private var days = 7
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = ApiClient.get(this)
        if (!api.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_expenses)
        total = findViewById(R.id.total)
        subtitle = findViewById(R.id.subtitle)
        tokens = findViewById(R.id.tokens)
        cacheNote = findViewById(R.id.cacheNote)
        status = findViewById(R.id.status)
        chart = findViewById(R.id.chart)
        models = findViewById(R.id.models)
        periods = findViewById(R.id.periods)
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnPrices).setOnClickListener { showRates() }
        renderPeriods()
        load()
    }

    private fun renderPeriods() {
        periods.removeAllViews()
        val options = listOf(1 to "Сегодня", 7 to "7 дней", 30 to "30 дней")
        for ((value, label) in options) {
            val chip = TextView(this)
            chip.text = label
            chip.textSize = 13f
            chip.gravity = Gravity.CENTER
            chip.setPadding(dp(14), dp(7), dp(14), dp(7))
            chip.background = getDrawable(if (value == days) R.drawable.bg_chip_active else R.drawable.bg_chip)
            chip.setTextColor(
                getColor(if (value == days) R.color.accent else R.color.text_secondary)
            )
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            chip.layoutParams = lp
            chip.setOnClickListener {
                if (days == value) return@setOnClickListener
                days = value
                renderPeriods()
                load()
            }
            periods.addView(chip)
        }
    }

    private fun load() {
        if (loading) return
        loading = true
        status.text = getString(R.string.loading)
        scope.launch {
            val daily = withContext(Dispatchers.IO) { api.usage(days) }
            val byModel = withContext(Dispatchers.IO) { api.usageByModel(days) }
            loading = false
            if (daily.isEmpty() && byModel.isEmpty()) {
                status.text = "Не удалось получить статистику. Проверь связь и войди заново."
                return@launch
            }
            render(daily, byModel)
        }
    }

    /**
     * The analytics endpoint reports one row per (model, provider), so the same model
     * name arrives several times — which on screen looks like a duplicate row with a
     * fraction of the money. Grouping by model name fixes that, and is correct for
     * pricing too: the rate comes from the lane in the model string, not the provider
     * column.
     */
    private fun groupByModel(rows: List<ModelUsage>): List<ModelUsage> =
        rows.groupBy { it.model }.map { (name, list) ->
            ModelUsage(
                model = name,
                inputTokens = list.sumOf { it.inputTokens },
                outputTokens = list.sumOf { it.outputTokens },
                cacheReadTokens = list.sumOf { it.cacheReadTokens },
                sessions = list.sumOf { it.sessions },
                apiCalls = list.sumOf { it.apiCalls },
                toolCalls = list.sumOf { it.toolCalls },
                lastUsedAt = list.maxOfOrNull { it.lastUsedAt } ?: 0L
            )
        }

    private fun render(daily: List<UsageDay>, byModelRaw: List<ModelUsage>) {
        val byModel = groupByModel(byModelRaw)
        // Cost per model, each at its own lane's rate; the period total is the sum
        // of those, not a separate calculation that could disagree with the rows.
        val priced = byModel
            .map { it to Prices.cost(this, it) }
            .filter { it.first.inputTokens + it.first.outputTokens + it.first.cacheReadTokens > 0 }
            .sortedByDescending { it.second }
        val periodCost = priced.sumOf { it.second }

        total.text = Prices.money(periodCost)
        val calls = daily.sumOf { it.apiCalls }
        val sessions = daily.maxOfOrNull { it.sessions } ?: 0
        val messages = priced.sumOf { it.first.apiCalls }
        subtitle.text = buildString {
            append("$calls запросов к модели")
            if (sessions > 0) append(" · до $sessions сеансов в день")
            if (messages > 0) append(" · ${priced.sumOf { it.first.toolCalls }} вызовов инструментов")
        }

        val inTokens = daily.sumOf { it.inputTokens }
        val outTokens = daily.sumOf { it.outputTokens }
        val cacheTokens = daily.sumOf { it.cacheReadTokens }
        val reasoning = daily.sumOf { it.reasoningTokens }
        tokens.text = buildString {
            append("Вход: ${Prices.tokens(inTokens)}\n")
            append("Выход: ${Prices.tokens(outTokens)}")
            if (reasoning > 0) append(" (из них размышления ${Prices.tokens(reasoning)})")
            append("\n")
            append("Из кэша: ${Prices.tokens(cacheTokens)}")
        }
        val cacheShare = if (inTokens + cacheTokens == 0L) 0.0
        else cacheTokens.toDouble() / (inTokens + cacheTokens)
        cacheNote.text = String.format(
            Locale("ru"),
            "Кэш — %.0f%% промпта. Он и делает день дешёвым: чтение из кэша стоит "
                + "в десятки раз меньше входа.",
            cacheShare * 100
        )

        val periodTokens = byModel.sumOf { it.inputTokens + it.outputTokens + it.cacheReadTokens }
        chart.submit(daily.map { it.day to costOfDay(it, periodCost, periodTokens) })

        models.removeAllViews()
        if (priced.isEmpty()) {
            status.text = "За этот период расходов нет."
            return
        }
        status.text = getString(R.string.expenses_note)
        val worst = priced.first().second.coerceAtLeast(0.001)
        for ((usage, cost) in priced) {
            models.addView(modelRow(usage, cost, cost / worst))
        }
    }

    /**
     * A day's cost. The daily endpoint has no model breakdown, so the day is priced
     * by spreading each model's period cost over its share of period tokens — good
     * enough for the shape of the bars, and explicitly not used for the headline
     * number (that one comes from the model totals).
     */
    private fun costOfDay(day: UsageDay, periodCost: Double, periodTokens: Long): Double {
        if (periodTokens <= 0L) return 0.0
        return periodCost * day.totalTokens.toDouble() / periodTokens
    }

    private fun modelRow(usage: ModelUsage, cost: Double, fraction: Double): View {
        val row = LayoutInflater.from(this).inflate(R.layout.item_expense_model, models, false)
        row.findViewById<TextView>(R.id.name).text = usage.model
        row.findViewById<TextView>(R.id.money).text = Prices.money(cost)
        val detail = row.findViewById<TextView>(R.id.detail)
        val resolved = Prices.resolve(this, usage.model)
        detail.text = buildString {
            append("вход ${Prices.tokens(usage.inputTokens)}")
            append(" · выход ${Prices.tokens(usage.outputTokens)}")
            append(" · кэш ${Prices.tokens(usage.cacheReadTokens)}")
            append(" · ${usage.sessions} сеансов")
            append(" · тариф ${resolved.label}")
            if (!resolved.exact) append(" — оценка")
        }
        val fill = row.findViewById<View>(R.id.barFill)
        val rest = row.findViewById<View>(R.id.barRest)
        val w = fraction.coerceIn(0.02, 1.0).toFloat()
        (fill.layoutParams as LinearLayout.LayoutParams).weight = w
        (rest.layoutParams as LinearLayout.LayoutParams).weight = 1f - w
        fill.requestLayout()
        // Tapping a model opens its rate: this is the number that decides the bill,
        // so it has to be reachable from the row that shows it.
        row.setOnClickListener { editRate(usage.model) }
        return row
    }

    /** Current rates for every model in the period, plus the ones it is likely to
     *  fall back to — a rate you cannot see is a rate you cannot trust. */
    private fun showRates() {
        scope.launch {
            val byModel = withContext(Dispatchers.IO) { api.usageByModel(days) }
            if (byModel.isEmpty()) {
                Toast.makeText(this@ExpensesActivity, "Нет данных о моделях", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val names = byModel.map { it.model }
            AlertDialog.Builder(this@ExpensesActivity)
                .setTitle(R.string.expenses_prices)
                .setItems(names.toTypedArray()) { _, which -> editRate(names[which]) }
                .setNeutralButton("Сбросить все", { _, _ ->
                    names.forEach { Prices.clearOverride(this@ExpensesActivity, it) }
                    load()
                    Toast.makeText(this@ExpensesActivity, "Тарифы сброшены к встроенным", Toast.LENGTH_SHORT).show()
                })
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /** Three numbers per 1M tokens: in, out, cache read. Decimal comma is fine. */
    private fun editRate(model: String) {
        val current = Prices.resolve(this, model).rates
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun field(label: String, value: Double): EditText {
            val caption = TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, dp(8), 0, 0)
            }
            val input = EditText(this).apply {
                setText(if (value == 0.0) "0" else value.toString())
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                textSize = 16f
            }
            container.addView(caption)
            container.addView(input)
            return input
        }
        val inField = field("Вход, ₽ за 1 млн", current.input)
        val outField = field("Выход, ₽ за 1 млн", current.output)
        val cacheField = field("Чтение из кэша, ₽ за 1 млн", current.cacheRead)

        AlertDialog.Builder(this)
            .setTitle(model)
            .setView(container)
            .setPositiveButton("Сохранить") { _, _ ->
                fun parse(text: CharSequence?, fallback: Double): Double =
                    text?.toString()?.replace(',', '.')?.trim()?.toDoubleOrNull() ?: fallback
                Prices.setOverride(
                    this, model,
                    Prices.Rates(
                        parse(inField.text, current.input),
                        parse(outField.text, current.output),
                        parse(cacheField.text, current.cacheRead)
                    )
                )
                load()
            }
            .setNeutralButton("Сбросить") { _, _ ->
                Prices.clearOverride(this, model)
                load()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
