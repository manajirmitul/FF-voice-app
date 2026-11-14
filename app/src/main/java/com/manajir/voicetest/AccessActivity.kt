package com.manajir.voicetest

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/** Shown after login until the person has an active plan: plans, payment, key. */
class AccessActivity : Activity() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Lang.wrap(base))
    }

    private lateinit var box: LinearLayout
    private var selected: Access.Plan? = null
    private var method = "bkash"

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun col(id: Int): Int = resources.getColor(id, theme)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Auth.isLoggedIn(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(col(R.color.bg))
        scroll.isFillViewport = true
        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL
        outer.setPadding(dp(20), dp(36), dp(20), dp(24))
        scroll.addView(outer, ViewGroup.LayoutParams(-1, -2))

        outer.addView(label("FF Voice", 26, R.color.text, true))
        box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        outer.addView(box, LinearLayout.LayoutParams(-1, -2))

        val foot = label(getString(R.string.logout) + "  -  " + Auth.email(this), 12, R.color.muted, false)
        foot.gravity = Gravity.CENTER
        foot.setPadding(0, dp(28), 0, dp(8))
        foot.setOnClickListener {
            Auth.signOut(this)
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
        outer.addView(foot)
        setContentView(scroll)
        runCheck()
    }

    // ---------- small view helpers ----------

    private fun label(t: String, sp: Int, color: Int, bold: Boolean): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = sp.toFloat()
        v.setTextColor(col(color))
        if (bold) v.setTypeface(v.typeface, Typeface.BOLD)
        return v
    }

    private fun shape(fill: Int, stroke: Int, strokeDp: Int, radiusDp: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = dp(radiusDp).toFloat()
        if (strokeDp > 0) g.setStroke(dp(strokeDp), stroke)
        return g
    }

    private fun gap(h: Int): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(1, dp(h))
        return v
    }

    private fun primaryButton(t: String, onClick: () -> Unit): TextView {
        val b = label(t, 16, R.color.text, true)
        b.gravity = Gravity.CENTER
        b.setTextColor(Color.WHITE)
        b.background = resources.getDrawable(R.drawable.bg_btn_primary, theme)
        b.setPadding(dp(16), dp(15), dp(16), dp(15))
        b.setOnClickListener { onClick() }
        b.layoutParams = LinearLayout.LayoutParams(-1, -2)
        return b
    }

    private fun linkButton(t: String, onClick: () -> Unit): TextView {
        val b = label(t, 14, R.color.accent2, false)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(8), dp(14), dp(8), dp(6))
        b.setOnClickListener { onClick() }
        b.layoutParams = LinearLayout.LayoutParams(-1, -2)
        return b
    }

    private fun msgView(): TextView {
        val m = label("", 13, R.color.danger, false)
        m.setPadding(0, dp(10), 0, dp(2))
        m.visibility = View.GONE
        return m
    }

    private fun show(m: TextView, text: String, ok: Boolean = false) {
        m.setTextColor(col(if (ok) R.color.ok else R.color.danger))
        m.text = text
        m.visibility = View.VISIBLE
    }

    private fun begin(title: String, sub: String, subColor: Int = R.color.muted) {
        box.removeAllViews()
        val t = label(title, 20, R.color.text, true)
        t.setPadding(0, dp(18), 0, dp(4))
        box.addView(t)
        val s = label(sub, 13, subColor, false)
        s.setPadding(0, 0, 0, dp(16))
        box.addView(s)
    }

    private fun planName(p: Access.Plan): String = getString(
        when (p.months) {
            1 -> R.string.months_1
            3 -> R.string.months_3
            else -> R.string.months_12
        }
    )

    // ---------- screens ----------

    private fun runCheck() {
        box.removeAllViews()
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(0, dp(80), 0, 0)
        val pb = ProgressBar(this)
        row.addView(pb)
        val t = label(getString(R.string.access_checking), 14, R.color.muted, false)
        t.gravity = Gravity.CENTER
        t.setPadding(0, dp(14), 0, 0)
        row.addView(t)
        box.addView(row)

        Access.check(this) { st ->
            when (st.code) {
                Access.ACTIVE -> goMain()
                Access.NEED_PLAN -> showPlans(st.note)
                Access.NEED_KEY -> showKey(st.note)
                else -> showError(if (st.note == "offline") getString(R.string.no_internet)
                else getString(R.string.error_fmt, st.note))
            }
        }
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun showError(msg: String) {
        begin(getString(R.string.access_checking).trimEnd('.'), msg, R.color.danger)
        box.addView(primaryButton(getString(R.string.retry)) { runCheck() })
    }

    private val cards = ArrayList<Pair<Access.Plan, LinearLayout>>()

    private fun showPlans(note: String) {
        val sub = when (note) {
            "expired" -> R.string.plan_expired
            "rejected" -> R.string.plan_rejected
            else -> R.string.plans_sub
        }
        begin(getString(R.string.choose_plan), getString(sub), if (note == "rejected") R.color.danger else R.color.muted)
        cards.clear()
        val msg = msgView()
        for (p in Access.PLANS) {
            val card = planCard(p) { selected = p; paintCards(); msg.visibility = View.GONE }
            cards.add(Pair(p, card))
            box.addView(card)
        }
        box.addView(msg)
        box.addView(gap(10))
        box.addView(primaryButton(getString(R.string.lang_continue)) {
            if (selected == null) show(msg, getString(R.string.choose_plan)) else showPay()
        })
        paintCards()
    }

    private fun planCard(p: Access.Plan, onClick: () -> Unit): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(dp(16), dp(16), dp(16), dp(16))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = dp(12)
        card.layoutParams = lp
        card.setOnClickListener { onClick() }

        val dot = View(this)
        dot.tag = "dot"
        card.addView(dot, LinearLayout.LayoutParams(dp(22), dp(22)))

        val mid = LinearLayout(this)
        mid.orientation = LinearLayout.VERTICAL
        val mlp = LinearLayout.LayoutParams(0, -2, 1f)
        mlp.marginStart = dp(14)
        mlp.marginEnd = dp(8)
        card.addView(mid, mlp)

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(label(planName(p), 17, R.color.text, true))
        val badgeText = when (p.months) {
            12 -> getString(R.string.best_value)
            3 -> getString(R.string.save_fmt, 100)
            else -> ""
        }
        if (badgeText.isNotEmpty()) {
            val b = label(badgeText, 11, R.color.text, true)
            b.setTextColor(Color.WHITE)
            b.background = shape(col(if (p.months == 12) R.color.accent1 else R.color.ok), 0, 0, 8)
            b.setPadding(dp(8), dp(2), dp(8), dp(2))
            val blp = LinearLayout.LayoutParams(-2, -2)
            blp.marginStart = dp(8)
            top.addView(b, blp)
        }
        mid.addView(top)

        var perMonth = getString(R.string.per_month_fmt, Math.round(p.price.toDouble() / p.months).toInt())
        if (p.months == 12) perMonth += "  -  " + getString(R.string.save_fmt, 700)
        mid.addView(label(perMonth, 12, R.color.muted, false))

        val price = label("\u09F3 " + p.price, 22, R.color.text, true)
        card.addView(price)
        return card
    }

    private fun paintCards() {
        for ((p, card) in cards) {
            val on = selected?.id == p.id
            card.background = shape(
                if (on) 0xFF17213D.toInt() else col(R.color.card),
                if (on) col(R.color.accent2) else col(R.color.stroke), if (on) 2 else 1, 18
            )
            val dot = card.findViewWithTag<View>("dot")
            val g = GradientDrawable()
            g.shape = GradientDrawable.OVAL
            g.setStroke(dp(2), if (on) col(R.color.accent2) else col(R.color.stroke_strong))
            g.setColor(if (on) col(R.color.accent2) else Color.TRANSPARENT)
            dot.background = g
        }
    }

    private fun showPay() {
        val p = selected ?: return showPlans("")
        begin(getString(R.string.pay_title), getString(R.string.pay_amount_fmt, p.price, planName(p)))

        // method switch
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val bk = methodPill("bKash", 0xFFE2136E.toInt(), "bkash")
        val ng = methodPill("Nagad", 0xFFF6921E.toInt(), "nagad")
        row.addView(bk.first, LinearLayout.LayoutParams(0, -2, 1f).also { it.marginEnd = dp(6) })
        row.addView(ng.first, LinearLayout.LayoutParams(0, -2, 1f).also { it.marginStart = dp(6) })
        box.addView(row)
        box.addView(gap(14))

        val numCard = LinearLayout(this)
        numCard.orientation = LinearLayout.VERTICAL
        numCard.gravity = Gravity.CENTER_HORIZONTAL
        numCard.setPadding(dp(16), dp(18), dp(16), dp(14))
        val to = label(getString(R.string.pay_to), 13, R.color.muted, false)
        to.gravity = Gravity.CENTER
        val num = label("", 26, R.color.text, true)
        num.typeface = Typeface.MONOSPACE
        num.gravity = Gravity.CENTER
        num.setPadding(0, dp(8), 0, dp(4))
        val copy = linkButton(getString(R.string.copy_number)) {}
        numCard.addView(to)
        numCard.addView(num)
        numCard.addView(copy)
        box.addView(numCard)

        fun repaint() {
            val c = if (method == "bkash") 0xFFE2136E.toInt() else 0xFFF6921E.toInt()
            val number = if (method == "bkash") Config.BKASH_NUMBER else Config.NAGAD_NUMBER
            num.text = number
            numCard.background = shape(col(R.color.card), c, 2, 18)
            for ((v, mc, id) in listOf(
                Triple(bk.first, 0xFFE2136E.toInt(), "bkash"),
                Triple(ng.first, 0xFFF6921E.toInt(), "nagad")
            )) {
                val on = method == id
                v.background = shape(if (on) mc else col(R.color.card), mc, 2, 14)
                v.setTextColor(if (on) Color.WHITE else mc)
            }
            copy.setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("number", number))
                copy.text = getString(R.string.copied)
            }
            copy.text = getString(R.string.copy_number)
        }
        bk.first.setOnClickListener { method = "bkash"; repaint() }
        ng.first.setOnClickListener { method = "nagad"; repaint() }
        repaint()

        val note = label(getString(R.string.pay_note), 12, R.color.muted, false)
        note.setPadding(0, dp(12), 0, dp(10))
        box.addView(note)

        val trx = EditText(this)
        trx.hint = getString(R.string.trx_hint)
        trx.setHintTextColor(col(R.color.muted))
        trx.setTextColor(col(R.color.text))
        trx.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        trx.background = resources.getDrawable(R.drawable.bg_input, theme)
        trx.setPadding(dp(14), dp(14), dp(14), dp(14))
        box.addView(trx, LinearLayout.LayoutParams(-1, -2))

        val msg = msgView()
        box.addView(msg)
        box.addView(gap(12))
        lateinit var submit: TextView
        submit = primaryButton(getString(R.string.submit_payment)) {
            val id = trx.text.toString().trim().uppercase()
            if (id.length < 6) {
                show(msg, getString(R.string.trx_required))
            } else {
                submit.isEnabled = false
                submit.text = getString(R.string.wait)
                Access.submitRequest(this, p, method, id) { err ->
                    if (err == null) {
                        showKey("pending")
                    } else {
                        submit.isEnabled = true
                        submit.text = getString(R.string.submit_payment)
                        show(msg, err)
                    }
                }
            }
        }
        box.addView(submit)
        box.addView(linkButton(getString(R.string.back)) { showPlans("") })
    }

    private fun methodPill(name: String, color: Int, id: String): Pair<TextView, Int> {
        val v = label(name, 15, R.color.text, true)
        v.gravity = Gravity.CENTER
        v.setPadding(dp(8), dp(13), dp(8), dp(13))
        return Pair(v, color)
    }

    private fun showKey(note: String) {
        val sub = if (note == "ready") R.string.key_ready else R.string.key_pending
        begin(getString(R.string.key_title), getString(sub))
        val key = EditText(this)
        key.hint = getString(R.string.key_hint)
        key.setHintTextColor(col(R.color.muted))
        key.setTextColor(col(R.color.text))
        key.textSize = 20f
        key.typeface = Typeface.MONOSPACE
        key.gravity = Gravity.CENTER
        key.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        key.background = resources.getDrawable(R.drawable.bg_input, theme)
        key.setPadding(dp(14), dp(16), dp(14), dp(16))
        box.addView(key, LinearLayout.LayoutParams(-1, -2))
        val msg = msgView()
        box.addView(msg)
        box.addView(gap(12))
        lateinit var go: TextView
        go = primaryButton(getString(R.string.activate)) {
            val k = key.text.toString().trim()
            if (k.isEmpty()) {
                show(msg, getString(R.string.key_required))
            } else {
                go.isEnabled = false
                go.text = getString(R.string.wait)
                Access.activate(this, k) { err ->
                    if (err == null) {
                        goMain()
                    } else {
                        go.isEnabled = true
                        go.text = getString(R.string.activate)
                        show(msg, err)
                    }
                }
            }
        }
        box.addView(go)
        box.addView(linkButton(getString(R.string.retry)) { runCheck() })
        box.addView(linkButton(getString(R.string.buy_other)) { showPlans("") })
    }
}
