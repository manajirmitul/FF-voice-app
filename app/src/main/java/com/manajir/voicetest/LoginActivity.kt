package com.manajir.voicetest

import android.app.Activity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Auth.isLoggedIn(this)) {
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
        btnSubmit.text = if (register) "অ্যাকাউন্ট খুলুন" else "লগইন"
        tvSub.text = if (register) "নতুন অ্যাকাউন্ট খুলুন" else "লগইন করে শুরু করুন"
        tvMsg.visibility = android.view.View.GONE
    }

    private fun showMsg(text: String, error: Boolean) {
        tvMsg.text = text
        tvMsg.setTextColor(getColor(if (error) R.color.danger else R.color.ok))
        tvMsg.visibility = android.view.View.VISIBLE
    }

    private fun setLoading(loading: Boolean) {
        btnSubmit.isEnabled = !loading
        btnSubmit.text = if (loading) "অপেক্ষা করুন..."
        else if (registerMode) "অ্যাকাউন্ট খুলুন" else "লগইন"
    }

    private fun submit() {
        val email = etEmail.text.toString().trim()
        val pass = etPass.text.toString()

        if (Auth.keyMissing()) {
            showMsg("API Key বসানো হয়নি। Config.kt ফাইলে Firebase Web API Key বসান", true)
            return
        }
        if (!email.contains("@") || !email.contains(".")) {
            showMsg("সঠিক ইমেইল লিখুন", true)
            return
        }
        if (pass.length < 6) {
            showMsg("পাসওয়ার্ড কমপক্ষে ৬ অক্ষরের দিন", true)
            return
        }
        if (registerMode && pass != etConfirm.text.toString()) {
            showMsg("দুটো পাসওয়ার্ড মিলছে না", true)
            return
        }

        tvMsg.visibility = android.view.View.GONE
        setLoading(true)
        val done: (String?) -> Unit = { err ->
            setLoading(false)
            if (err == null) goMain() else showMsg(err, true)
        }
        if (registerMode) Auth.signUp(this, email, pass, done)
        else Auth.signIn(this, email, pass, done)
    }

    private fun forgot() {
        val email = etEmail.text.toString().trim()
        if (Auth.keyMissing()) {
            showMsg("API Key বসানো হয়নি। Config.kt ফাইলে Firebase Web API Key বসান", true)
            return
        }
        if (!email.contains("@")) {
            showMsg("আগে ওপরের ঘরে আপনার ইমেইল লিখুন", true)
            return
        }
        Auth.resetPassword(email) { err ->
            if (err == null) showMsg("পাসওয়ার্ড রিসেটের লিংক ইমেইলে পাঠানো হয়েছে। স্প্যাম ফোল্ডারও দেখুন", false)
            else showMsg(err, true)
        }
    }
}
