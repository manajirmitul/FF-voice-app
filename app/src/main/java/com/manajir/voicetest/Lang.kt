package com.manajir.voicetest

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** App language: choice storage, localized contexts, and the language picker dialog. */
object Lang {

    class Item(val code: String, val name: String)

    /** Names are written in their own language so everyone can find theirs. */
    val all = listOf(
        Item("en", "English"),
        Item("bn", "\u09AC\u09BE\u0982\u09B2\u09BE"),
        Item("hi", "\u0939\u093F\u0928\u094D\u0926\u0940"),
        Item("ur", "\u0627\u0631\u062F\u0648"),
        Item("ar", "\u0627\u0644\u0639\u0631\u0628\u064A\u0629"),
        Item("id", "Bahasa Indonesia"),
        Item("es", "Espa\u00F1ol"),
        Item("pt", "Portugu\u00EAs"),
        Item("tr", "T\u00FCrk\u00E7e"),
        Item("ru", "\u0420\u0443\u0441\u0441\u043A\u0438\u0439")
    )

    private const val PREFS = "lang"
    private lateinit var app: Context
    @Volatile private var cached: Context? = null
    @Volatile private var cachedCode = ""

    fun init(c: Context) {
        app = c.applicationContext ?: c
    }

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Saved choice, else the phone's language if we have it, else English. */
    fun current(): String {
        val saved = prefs().getString("code", null)
        if (saved != null && all.any { it.code == saved }) return saved
        var d = Locale.getDefault().language
        if (d == "in") d = "id"
        return if (all.any { it.code == d }) d else "en"
    }

    fun set(code: String) {
        prefs().edit().putString("code", code).apply()
        cached = null
    }

    /** True from a successful login/registration until the person has picked a language. */
    fun pending(): Boolean = prefs().getBoolean("pending", false)

    fun setPending(v: Boolean) {
        prefs().edit().putBoolean("pending", v).apply()
    }

    private fun make(base: Context, code: String): Context {
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(Locale.forLanguageTag(code))
        return base.createConfigurationContext(cfg)
    }

    /** For attachBaseContext of screens and services that follow the chosen language. */
    fun wrap(base: Context): Context = make(base, current())

    /** For the login screen, which is always English. */
    fun wrapEnglish(base: Context): Context = make(base, "en")

    /** Text in the chosen language, usable from anywhere (logs, notifications). */
    fun get(id: Int, vararg a: Any): String {
        val code = current()
        var c = cached
        if (c == null || cachedCode != code) {
            c = make(app, code)
            cached = c
            cachedCode = code
        }
        return c.getString(id, *a)
    }

    /** Text in English (login errors). */
    fun en(id: Int, vararg a: Any): String = make(app, "en").getString(id, *a)

    /** Shows the language list. [onChosen] runs after the person taps Continue. */
    fun showPicker(act: Activity, cancelable: Boolean, onChosen: () -> Unit) {
        val names = all.map { it.name }.toTypedArray()
        var sel = all.indexOfFirst { it.code == current() }
        if (sel < 0) sel = 0
        val b = AlertDialog.Builder(act)
            .setTitle(act.getString(R.string.choose_language))
            .setSingleChoiceItems(names, sel) { _, which -> sel = which }
            .setPositiveButton(act.getString(R.string.lang_continue)) { _, _ ->
                set(all[sel].code)
                onChosen()
            }
            .setCancelable(cancelable)
        if (cancelable) b.setNegativeButton(act.getString(R.string.cancel), null)
        b.show()
    }
}
