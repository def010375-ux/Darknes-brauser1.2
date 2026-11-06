package com.darknes.tor

import android.app.Activity
import android.app.assist.AssistStructure
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.autofill.AutofillManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class AutofillAuthActivity : Activity() {
    private lateinit var passwordInput: EditText
    private lateinit var message: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
            setBackgroundColor(Color.rgb(7, 17, 31))
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply { text = "DARKNES VAULT"; textSize = 22f; setTextColor(Color.rgb(226, 246, 255)); setPadding(0, 0, 0, dp(8)) }
        val subtitle = TextView(this).apply { text = "Разблокируй хранилище, чтобы заполнить форму"; textSize = 14f; setTextColor(Color.rgb(151, 180, 201)); setPadding(0, 0, 0, dp(20)) }
        passwordInput = EditText(this).apply {
            hint = "Мастер-пароль Vault"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(126, 151, 171))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        message = TextView(this).apply { textSize = 12f; setTextColor(Color.rgb(255, 137, 137)); setPadding(0, dp(10), 0, dp(10)) }
        val unlock = Button(this).apply { text = "Разблокировать и заполнить"; setOnClickListener { unlockAndFill() } }
        val cancel = Button(this).apply { text = "Отмена"; setOnClickListener { cancelAuth() } }
        root.addView(title)
        root.addView(subtitle)
        root.addView(passwordInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(message)
        root.addView(unlock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
    }

    private fun unlockAndFill() {
        val typed = passwordInput.text?.toString().orEmpty()
        if (typed.isBlank()) { message.text = "Введи мастер-пароль."; return }
        val json = DarknesVaultCrypto.unlock(this, typed.toCharArray())
        passwordInput.text?.clear()
        if (json == null) { message.text = "Неверный мастер-пароль или хранилище повреждено."; return }
        val structure = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE, AssistStructure::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE) as? AssistStructure
        }
        val fields = structure?.let { DarknesAutofillResponses.findFields(it) }
        val response = if (fields != null) DarknesAutofillResponses.buildResponse(this, fields, decodeVaultEntries(json)) else null
        if (response == null) {
            message.text = "Для этого сайта не найден подходящий сохранённый логин."
            return
        }
        val result = Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, response)
        setResult(RESULT_OK, result)
        finish()
    }

    private fun cancelAuth() {
        setResult(RESULT_CANCELED, Intent().putExtras(Bundle.EMPTY))
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}