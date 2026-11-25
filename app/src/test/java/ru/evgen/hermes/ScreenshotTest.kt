package ru.evgen.hermes

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the real layouts to PNG, headlessly, so the repository can ship
 * screenshots without a device and without anyone's transcripts in them.
 *
 * Not a UI assertion: this test exists to produce the PNGs in `docs/screenshots`
 * (`-Dscreens.dir=<path>`, default `build/screenshots`). It fails only when a
 * screen cannot be inflated or drawn — which is itself worth knowing.
 *
 * Data below is invented. Never put a real session title, path or message here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {

    private val width = 1080
    private val height = 2280

    private val outDir: File by lazy {
        File(System.getProperty("screens.dir") ?: "build/screenshots").apply { mkdirs() }
    }

    private fun activity(): Activity =
        Robolectric.buildActivity(Activity::class.java).create().get()

    private fun render(name: String, view: View, w: Int = width, h: Int = height) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, w, h)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        FileOutputStream(File(outDir, "$name.png")).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun fakeSessions(): List<Session> = listOf(
        Session(
            id = "s1", title = "Расписание дашборда", preview = "Добавь переключатель дня и обнови события",
            lastActive = System.currentTimeMillis() - 4 * 60_000, pinned = true, archived = false,
            messageCount = 24, model = "sonnet", source = "telegram", unread = true
        ),
        Session(
            id = "s2", title = "Разбор логов кассы", preview = "Второй Z-отчёт не уходит в ОФД",
            lastActive = System.currentTimeMillis() - 42 * 60_000, pinned = false, archived = false,
            messageCount = 61, model = "sonnet", source = "desktop",
            running = true, activity = "выполняется команда"
        ),
        Session(
            id = "s3", title = "Смета на кабель", preview = "Посчитай метраж на два этажа и заложи запас",
            lastActive = System.currentTimeMillis() - 5 * 3600_000, pinned = false, archived = false,
            messageCount = 12, model = "haiku", source = "telegram"
        ),
        Session(
            id = "s4", title = "Проверка бэкапов", preview = "Сверь дампы за неделю и пришли отчёт",
            lastActive = System.currentTimeMillis() - 26 * 3600_000, pinned = false, archived = false,
            messageCount = 8, model = "sonnet", source = "desktop"
        ),
        Session(
            id = "s5", title = "Заметки по встрече", preview = "Разложи по задачам, что обсудили",
            lastActive = System.currentTimeMillis() - 3 * 24 * 3600_000, pinned = false, archived = false,
            messageCount = 17, model = "haiku", source = "telegram"
        )
    )

    private fun attach(view: View, adapter: RecyclerView.Adapter<*>) {
        val list = view.findViewById<RecyclerView>(R.id.list)
            ?: view.findViewById(R.id.messages)
        list.layoutManager = LinearLayoutManager(view.context)
        list.adapter = adapter
        list.itemAnimator = null
    }

    @Test
    fun sessions() {
        val host = FrameLayout(activity())
        val view = host.inflate(R.layout.fragment_sessions)
        val adapter = SessionAdapter({}, { _, _ -> })
        adapter.submit(fakeSessions())
        attach(view, adapter)
        render("01-sessions", view)
    }

    @Test
    fun chat() {
        val host = FrameLayout(activity())
        val view = host.inflate(R.layout.fragment_chat)
        view.findViewById<android.widget.TextView>(R.id.title).text = "Расписание дашборда"
        val adapter = ChatAdapter(view.context, {})
        adapter.submit(
            listOf(
                ChatMessage(
                    role = "user",
                    content = "Добавь в дашборд переключатель дня и обнови события",
                    toolName = null, reasoning = null, timestamp = 1_700_000_000.0
                ),
                ChatMessage(
                    role = "assistant",
                    content = "Готово. День переключается чипами над списком, а блок «События дня»\n" +
                        "перерисовывается при выборе — без перезагрузки страницы.\n\n" +
                        "Что изменилось:\n" +
                        "• запрос идёт с параметром `date`;\n" +
                        "• пустой день показывает подсказку, а не пустую таблицу;\n" +
                        "• заголовок блока следует за выбранной датой.",
                    toolName = null, reasoning = null, timestamp = 1_700_000_060.0
                ),
                ChatMessage(
                    role = "user",
                    content = "А если данных за день нет?",
                    toolName = null, reasoning = null, timestamp = 1_700_000_120.0
                ),
                ChatMessage(
                    role = "assistant",
                    content = "Тогда блок показывает «Событий нет» и кнопку добавить —\n" +
                        "пустое состояние вместо пустоты.",
                    toolName = null, reasoning = null, timestamp = 1_700_000_180.0
                )
            )
        )
        attach(view, adapter)
        render("02-chat", view)
    }

    @Test
    fun login() {
        val host = FrameLayout(activity())
        val view = host.inflate(R.layout.activity_login)
        view.findViewById<android.widget.EditText>(R.id.server).setText("https://hermes.example.com")
        view.findViewById<android.widget.EditText>(R.id.user).setText("evgen")
        render("03-login", view)
    }

    private fun ViewGroup.inflate(id: Int): View =
        android.view.LayoutInflater.from(context).inflate(id, this, false).also { addView(it) }
}
