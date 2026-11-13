package com.manajir.voicetest

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

class LoginActivity : Activity() {

    private var registerMode = false
    private var proceeding = false

    private lateinit var tabLogin: TextView
    private lateinit var tabRegister: TextView
    private lateinit var etEmail: EditText
    private lateinit var etPass: EditText
    private lateinit var etConfirm: EditText
    private lateinit var tvMsg: TextView
    private lateinit var tvSub: TextView
    private lateinit var btnSubmit: Button

    // The login and registration screens are always English.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Lang.wrapEnglish(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val logged = Auth.isLoggedIn(this)
        if (logged && !Lang.pending()) {
            goMain()
            return
        }
        setContentView(R.layout.activity_login)

        tabLogin = findViewById(R.id.tabLogin)
        tabRegister = findViewById(R.id.tabRegister)
        etEmail = findViewById(R.id.etEmail)
        etPass = findViewById(R.id.etPass)
        etConfirm = findViewById(R.id.etConfirm)
        tvMsg = findViewById(R.id.tvMsg)
        tvSub = findViewById(R.id.tvSub)
        btnSubmit = findViewById(R.id.btnSubmit)

        tabLogin.setOnClickListener { setMode(false) }
        tabRegister.setOnClickListener { setMode(true) }
        btnSubmit.setOnClickListener { submit() }
        findViewById<TextView>(R.id.tvForgot).setOnClickListener { forgot() }

        // Logged in but the language was not picked yet (e.g. the screen was recreated)
        if (logged) pickLanguage()
    }

    /** After a successful login/registration: choose the app language, then open the app. */
    private fun pickLanguage() {
        Lang.setPending(true)
        Lang.showPicker(this, false) {
            Lang.setPending(false)
            goMain()
        }
    }

    private fun goMain() {
        if (proceeding) return
        proceeding = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun setMode(register: Boolean) {
        registerMode = register
        tabLogin.setBackgroundResource(if (register) R.drawable.bg_btn_ghost else R.drawable.bg_btn_primary)
        tabRegister.setBackgroundResource(if (register) R.drawable.bg_btn_primary else R.drawable.bg_btn_ghost)
        etConfirm.visibility = if (register) android.view.View.VISIBLE else android.view.View.GONE
        btnSubmit.text = getString(if (register) R.string.create_account else R.string.login)
        tvSub.text = getString(if (register) R.string.sub_register else R.string.sub_login)
        tvMsg.visibility = android.view.View.GONE
    }

    private fun showMsg(text: String, error: Boolean) {
        tvMsg.text = text
        tvMsg.setTextColor(getColor(if (error) R.color.danger else R.color.ok))
        tvMsg.visibility = android.view.View.VISIBLE
    }

    private fun setLoading(loading: Boolean) {
        btnSubmit.isEnabled = !loading
        btnSubmit.text = if (loading) getString(R.string.please_wait)
        else getString(if (registerMode) R.string.create_account else R.string.login)
    }

    private fun submit() {
        val email = etEmail.text.toString().trim()
        val pass = etPass.text.toString()

        if (Auth.keyMissing()) {
            showMsg(getString(R.string.key_missing), true)
            return
        }
        if (!email.contains("@") || !email.contains(".")) {
            showMsg(getString(R.string.enter_valid_email), true)
            return
        }
        if (pass.length < 6) {
            showMsg(getString(R.string.pass_min), true)
            return
        }
        if (registerMode && pass != etConfirm.text.toString()) {
            showMsg(getString(R.string.pass_mismatch), true)
            return
        }

        tvMsg.visibility = android.view.View.GONE
        setLoading(true)
        val done: (String?) -> Unit = { err ->
            setLoading(false)
            if (err == null) pickLanguage() else showMsg(err, true)
        }
        if (registerMode) Auth.signUp(this, email, pass, done)
        else Auth.signIn(this, email, pass, done)
    }

    private fun forgot() {
        val email = etEmail.text.toString().trim()
        if (Auth.keyMissing()) {
            showMsg(getString(R.string.key_missing), true)
            return
        }
        if (!email.contains("@")) {
            showMsg(getString(R.string.enter_email_first), true)
            return
        }
        Auth.resetPassword(email) { err ->
            if (err == null) showMsg(getString(R.string.reset_sent), false)
            else showMsg(err, true)
        }
    }
}
