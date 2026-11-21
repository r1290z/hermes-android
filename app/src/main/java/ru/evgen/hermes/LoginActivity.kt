package ru.evgen.hermes

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * The one screen the browser used to provide.
 *
 * The app talks to the API directly, so it needs the dashboard login once.
 * Cookies are kept afterwards; the password itself is never stored.
 */
class LoginActivity : Activity() {

    private lateinit var server: EditText
    private lateinit var user: EditText
    private lateinit var password: EditText
    private lateinit var button: Button
    private lateinit var progress: ProgressBar
    private lateinit var hint: TextView
    private lateinit var api: ApiClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        api = ApiClient.get(this)

        server = findViewById(R.id.server)
        user = findViewById(R.id.user)
        password = findViewById(R.id.password)
        button = findViewById(R.id.btnLogin)
        progress = findViewById(R.id.progress)
        hint = findViewById(R.id.hint)

        server.setText(api.base)
        button.setOnClickListener { attempt() }
    }

    private fun attempt() {
        val address = server.text.toString().trim()
        val login = user.text.toString().trim()
        val pass = password.text.toString()
        if (address.isEmpty() || login.isEmpty() || pass.isEmpty()) {
            hint.text = getString(R.string.login_failed)
            return
        }
        api.setBase(address)
        setBusy(true)

        thread {
            val error = api.login(login, pass)
            runOnUiThread {
                setBusy(false)
                if (error == null) {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                } else {
                    hint.text = error
                }
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        button.isEnabled = !busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        hint.text = if (busy) getString(R.string.logging_in) else getString(R.string.login_subtitle)
    }
}
