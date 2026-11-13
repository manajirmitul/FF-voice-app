package com.manajir.voicetest

import android.app.Service
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs

/** Floating bubble + control panel drawn over other apps (e.g. the game). */
class OverlayService : Service() {

    companion object {
        @Volatile var running = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var container: FrameLayout
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var bubble: TextView
    private lateinit var panel: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var tabCtrl: TextView
    private lateinit var tabLib: TextView
    private lateinit var ctrlSection: LinearLayout
    private lateinit var libSection: LinearLayout
    private lateinit var libList: LinearLayout
    private lateinit var btnMusic: TextView
    private lateinit var btnPause: TextView
    private lateinit var tvGain: TextView
    private lateinit var sbGain: SeekBar
    private lateinit var level: ProgressBar
    private var libStamp = ""

    private val changeListener: () -> Unit = { handler.post { refresh() } }
    private val levelListener: (Int) -> Unit = { v ->
        handler.post { if (this::level.isInitialized) level.progress = v }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        Engine.init(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        build()
        running = true
        Engine.listeners.add(changeListener)
        Engine.levelListeners.add(levelListener)
        refresh()
        Engine.notifyChange()
    }

    private fun pill(label: String, bg: Int, size: Float): TextView {
        val t = TextView(this)
        t.text = label
        t.setTextColor(Color.WHITE)
        t.textSize = size
        t.gravity = Gravity.CENTER
        t.setPadding(dp(10), dp(9), dp(10), dp(9))
        t.setBackgroundResource(bg)
        return t
    }

    private fun lpFull(top: Int): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(top)
        return lp
    }

    private fun build() {
        container = FrameLayout(this)

        bubble = TextView(this)
        bubble.text = "♪"
        bubble.textSize = 24f
        bubble.setTextColor(Color.WHITE)
        bubble.gravity = Gravity.CENTER
        bubble.setBackgroundResource(R.drawable.bg_bubble_idle)
        container.addView(bubble, FrameLayout.LayoutParams(dp(54), dp(54)))

        panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(14), dp(12), dp(14), dp(14))
        panel.setBackgroundResource(R.drawable.bg_panel)
        panel.visibility = View.GONE

        // ---- header (drag handle) ----
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL

        val titleBox = LinearLayout(this)
        titleBox.orientation = LinearLayout.VERTICAL
        val title = TextView(this)
        title.text = "FF Voice"
        title.setTextColor(Color.WHITE)
        title.textSize = 16f
        title.typeface = Typeface.DEFAULT_BOLD
        tvStatus = TextView(this)
        tvStatus.textSize = 12f
        tvStatus.maxLines = 1
        tvStatus.ellipsize = TextUtils.TruncateAt.END
        tvStatus.setTextColor(0xFF8A94B2.toInt())
        titleBox.addView(title)
        titleBox.addView(tvStatus)

        val home = TextView(this)
        home.text = "⌂"
        home.textSize = 20f
        home.setTextColor(Color.WHITE)
        home.gravity = Gravity.CENTER
        home.setPadding(dp(10), 0, dp(6), 0)
        home.setOnClickListener { openMainApp() }

        val minimize = TextView(this)
        minimize.text = "–"
        minimize.textSize = 24f
        minimize.setTextColor(Color.WHITE)
        minimize.gravity = Gravity.CENTER
        minimize.setPadding(dp(10), 0, dp(2), 0)
        minimize.setOnClickListener { collapse() }

        header.addView(titleBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(home)
        header.addView(minimize)
        panel.addView(header)

        // ---- tabs ----
        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabCtrl = pill("কন্ট্রোল", R.drawable.bg_btn_primary, 14f)
        tabLib = pill("লাইব্রেরি", R.drawable.bg_btn_ghost, 14f)
        tabCtrl.setOnClickListener { selectTab(0) }
        tabLib.setOnClickListener { selectTab(1) }
        val lpT1 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lpT1.rightMargin = dp(4)
        val lpT2 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lpT2.leftMargin = dp(4)
        tabs.addView(tabCtrl, lpT1)
        tabs.addView(tabLib, lpT2)
        panel.addView(tabs, lpFull(10))

        // ---- controls section ----
        ctrlSection = LinearLayout(this)
        ctrlSection.orientation = LinearLayout.VERTICAL

        btnMusic = pill("", R.drawable.bg_btn_primary, 15f)
        btnMusic.setOnClickListener {
            Engine.musicOn = !Engine.musicOn
            Engine.notifyChange()
        }
        ctrlSection.addView(btnMusic, lpFull(10))

        val mediaRow = LinearLayout(this)
        mediaRow.orientation = LinearLayout.HORIZONTAL
        val keys = arrayOf("◀◀", "▶/❚❚", "▶▶")
        val codes = intArrayOf(
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT
        )
        for (i in keys.indices) {
            val b = pill(keys[i], R.drawable.bg_btn_ghost, 15f)
            val code = codes[i]
            b.setOnClickListener { Engine.mediaKey(code) }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (i == 1) {
                lp.leftMargin = dp(6)
                lp.rightMargin = dp(6)
            }
            mediaRow.addView(b, lp)
        }
        ctrlSection.addView(mediaRow, lpFull(8))

        val volRow = LinearLayout(this)
        volRow.orientation = LinearLayout.HORIZONTAL
        val volLabel = TextView(this)
        volLabel.text = "গানের ভলিউম"
        volLabel.setTextColor(Color.WHITE)
        volLabel.textSize = 13f
        tvGain = TextView(this)
        tvGain.setTextColor(0xFF22D3EE.toInt())
        tvGain.textSize = 13f
        volRow.addView(volLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        volRow.addView(tvGain)
        ctrlSection.addView(volRow, lpFull(12))

        sbGain = SeekBar(this)
        sbGain.max = 200
        sbGain.progress = (Engine.gain * 100).toInt()
        sbGain.progressTintList = ColorStateList.valueOf(0xFF22D3EE.toInt())
        sbGain.thumbTintList = ColorStateList.valueOf(0xFF7C5CFF.toInt())
        sbGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    Engine.gain = progress / 100f
                    tvGain.text = "$progress%"
                    Engine.notifyChange()
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        ctrlSection.addView(sbGain, lpFull(0))

        level = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        level.max = 100
        level.progressTintList = ColorStateList.valueOf(0xFF34D399.toInt())
        val lpLevel = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(5))
        lpLevel.topMargin = dp(6)
        ctrlSection.addView(level, lpLevel)

        panel.addView(ctrlSection)

        // ---- library section ----
        libSection = LinearLayout(this)
        libSection.orientation = LinearLayout.VERTICAL
        libSection.visibility = View.GONE

        val libTop = LinearLayout(this)
        libTop.orientation = LinearLayout.HORIZONTAL
        btnPause = pill("▶ / ❚❚", R.drawable.bg_btn_ghost, 14f)
        btnPause.setOnClickListener { Player.togglePause() }
        val btnStop = pill("■ বন্ধ", R.drawable.bg_btn_ghost, 14f)
        btnStop.setOnClickListener { Player.stop() }
        val lpA = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lpA.rightMargin = dp(4)
        val lpB = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lpB.leftMargin = dp(4)
        libTop.addView(btnPause, lpA)
        libTop.addView(btnStop, lpB)
        libSection.addView(libTop, lpFull(10))

        libList = LinearLayout(this)
        libList.orientation = LinearLayout.VERTICAL
        val sv = ScrollView(this)
        sv.addView(libList)
        val lpSv = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150))
        lpSv.topMargin = dp(8)
        libSection.addView(sv, lpSv)

        panel.addView(libSection)

        container.addView(panel, FrameLayout.LayoutParams(dp(280), FrameLayout.LayoutParams.WRAP_CONTENT))

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = dp(10)
        params.y = dp(120)
        wm.addView(container, params)

        makeDraggable(bubble) { expand() }
        makeDraggable(titleBox, null)
    }

    private fun selectTab(i: Int) {
        ctrlSection.visibility = if (i == 0) View.VISIBLE else View.GONE
        libSection.visibility = if (i == 1) View.VISIBLE else View.GONE
        tabCtrl.setBackgroundResource(if (i == 0) R.drawable.bg_btn_primary else R.drawable.bg_btn_ghost)
        tabLib.setBackgroundResource(if (i == 1) R.drawable.bg_btn_primary else R.drawable.bg_btn_ghost)
        updateLayout()
    }

    private fun makeDraggable(handle: View, onClick: (() -> Unit)?) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = e.rawX
                    touchY = e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    updateLayout()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onClick?.invoke()
                    true
                }
                else -> false
            }
        }
    }

    private fun updateLayout() {
        try {
            wm.updateViewLayout(container, params)
        } catch (_: Throwable) {
        }
    }

    private fun expand() {
        bubble.visibility = View.GONE
        panel.visibility = View.VISIBLE
        val maxX = resources.displayMetrics.widthPixels - dp(290)
        if (params.x > maxX) params.x = maxX
        if (params.x < 0) params.x = 0
        val maxY = resources.displayMetrics.heightPixels - dp(330)
        if (params.y > maxY) params.y = maxY
        if (params.y < 0) params.y = 0
        updateLayout()
    }

    private fun collapse() {
        panel.visibility = View.GONE
        bubble.visibility = View.VISIBLE
        updateLayout()
    }

    private fun openMainApp() {
        val i = Intent(this, MainActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    private fun rebuildLibrary() {
        val stamp = "" + Library.version + "|" + Player.currentFile + "|" + Player.paused
        if (stamp == libStamp) return
        libStamp = stamp
        libList.removeAllViews()

        val files = Library.list(this)
        if (files.isEmpty()) {
            val empty = TextView(this)
            empty.text = "লাইব্রেরি খালি। অ্যাপ থেকে অডিও যোগ করুন"
            empty.setTextColor(0xFF8A94B2.toInt())
            empty.textSize = 13f
            empty.setPadding(dp(4), dp(10), dp(4), dp(10))
            libList.addView(empty)
            return
        }
        for (f in files) {
            val isCur = Player.playing && Player.currentFile == f
            val tv = TextView(this)
            val mark = if (isCur) (if (Player.paused) "❚❚  " else "▶  ") else "♪  "
            tv.text = mark + Library.displayName(f)
            tv.setTextColor(if (isCur) 0xFF22D3EE.toInt() else Color.WHITE)
            tv.textSize = 14f
            tv.maxLines = 1
            tv.ellipsize = TextUtils.TruncateAt.END
            tv.setPadding(dp(8), dp(10), dp(8), dp(10))
            tv.setOnClickListener {
                if (isCur) Player.togglePause() else Player.request(f)
            }
            libList.addView(
                tv,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun refresh() {
        if (!this::container.isInitialized) return
        val ready = Engine.pathReady
        val bg = if (!ready) R.drawable.bg_bubble_idle
        else if (Engine.musicOn) R.drawable.bg_bubble_on
        else R.drawable.bg_bubble_muted
        bubble.setBackgroundResource(bg)

        val cur = Player.currentFile
        tvStatus.text = if (Player.playing && cur != null) "চলছে: " + Library.displayName(cur)
        else if (!ready) "বন্ধ। লাইব্রেরি থেকে বাজালে নিজে চালু হবে"
        else if (Engine.musicOn) "গান মাইকে যাচ্ছে"
        else "গান বন্ধ। আপনার কথা যাচ্ছে"

        btnMusic.text = if (Engine.musicOn) "গান: চালু" else "গান: বন্ধ"
        btnMusic.setBackgroundResource(
            if (Engine.musicOn) R.drawable.bg_btn_primary else R.drawable.bg_btn_ghost
        )
        btnPause.text = if (Player.playing && !Player.paused) "❚❚ পজ" else "▶ চালু"

        val p = (Engine.gain * 100).toInt()
        if (sbGain.progress != p) sbGain.progress = p
        tvGain.text = "$p%"
        rebuildLibrary()
    }

    override fun onDestroy() {
        Engine.listeners.remove(changeListener)
        Engine.levelListeners.remove(levelListener)
        if (this::container.isInitialized) {
            try {
                wm.removeView(container)
            } catch (_: Throwable) {
            }
        }
        running = false
        Engine.notifyChange()
        super.onDestroy()
    }
}
