package com.rasp.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class LoginActivity : Activity() {
    private lateinit var nickF: EditText
    private lateinit var passF: EditText
    private lateinit var msg: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.parseColor("#FFF1F6"))
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(18), dp(60), dp(18), dp(30))
        sv.addView(root)
        setContentView(sv)

        val box = card(root, "")
        box.addView(text("Мои пары 🎀", 28f, ACC, true))
        box.addView(text("Войди или зарегистрируйся: ник и пароль", 14f, MUTED))
        nickF = EditText(this)
        nickF.hint = "Ник (латиница, цифры, _)"
        nickF.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        box.addView(nickF)
        passF = EditText(this)
        passF.hint = "Пароль (от 6 символов)"
        passF.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        box.addView(passF)
        msg = text("", 13f, ACC)
        box.addView(msg)
        box.addView(button("Войти") { go(false) })
        box.addView(button("Зарегистрироваться", false) { go(true) })
        if (!Api.configured()) msg.text = "В приложении не вставлены данные сервера (файл Config.kt)"
    }

    private fun go(register: Boolean) {
        val nick = nickF.text.toString().trim().lowercase()
        val pass = passF.text.toString()
        if (!Regex("^[a-z0-9_]{3,20}$").matches(nick)) {
            msg.text = "Ник: 3–20 символов, только латиница, цифры и _"
            return
        }
        if (pass.length < 6) {
            msg.text = "Пароль должен быть не короче 6 символов"
            return
        }
        msg.text = "Подожди..."
        Thread {
            val err = if (register) Api.signUp(this, nick, pass) else Api.signIn(this, nick, pass)
            if (err == null) {
                try {
                    Sync.pull(this, true)
                } catch (e: Exception) {
                }
            }
            runOnUiThread {
                if (err != null) {
                    msg.text = err
                } else {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
            }
        }.start()
    }
}
