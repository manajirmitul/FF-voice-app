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
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
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
    private lateinit var btnMusic: TextView
    private lateinit var tvGain: TextView
    private lateinit var sbGain: SeekBar
    private lateinit var level: ProgressBar

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
        t.setPadding(dp(10), dp(10), dp(10), dp(10))
        t.setBackgroundResource(bg)
        return t
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

        // header (drag handle)
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
        tvStatus.setTextColor(0xFF8A94B2.toInt())
        titleBox.addView(title)
        titleBox.addView(tvStatus)

        val minimize = TextView(this)
        minimize.text = "–"
        minimize.textSize = 24f
        minimize.setTextColor(Color.WHITE)
        minimize.gravity = Gravity.CENTER
        minimize.setPadding(dp(14), 0, dp(4), 0)
        minimize.setOnClickListener { collapse() }

        header.addView(titleBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(minimize)
        panel.addView(header)

        // music on/off
        btnMusic = pill("", R.drawable.bg_btn_primary, 15f)
        btnMusic.setOnClickListener {
            Engine.musicOn = !Engine.musicOn
            Engine.notifyChange()
        }
        val lpMusic = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lpMusic.topMargin = dp(12)
        panel.addView(btnMusic, lpMusic)

        // media keys
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
        val lpMedia = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lpMedia.topMargin = dp(10)
        panel.addView(mediaRow, lpMedia)

        // volume
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
        val lpVol = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lpVol.topMargin = dp(14)
        panel.addView(volRow, lpVol)

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
        panel.addView(
            sbGain,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        level = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        level.max = 100
        level.progressTintList = ColorStateList.valueOf(0xFF34D399.toInt())
        val lpLevel = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(5))
        lpLevel.topMargin = dp(6)
        panel.addView(level, lpLevel)

        val openApp = pill("অ্যাপ খুলুন", R.drawable.bg_btn_ghost, 13f)
        openApp.setOnClickListener { openMainApp() }
        val lpOpen = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lpOpen.topMargin = dp(12)
        panel.addView(openApp, lpOpen)

        container.addView(panel, FrameLayout.LayoutParams(dp(260), FrameLayout.LayoutParams.WRAP_CONTENT))

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = dp(10)
        params.y = dp(180)
        wm.addView(container, params)

        makeDraggable(bubble) { expand() }
        makeDraggable(titleBox, null)
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
        val maxX = resources.displayMetrics.widthPixels - dp(270)
        if (params.x > maxX) params.x = maxX
        if (params.x < 0) params.x = 0
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

    private fun refresh() {
        if (!this::container.isInitialized) return
        val ready = Engine.pathReady
        val bg = if (!ready) R.drawable.bg_bubble_idle
        else if (Engine.musicOn) R.drawable.bg_bubble_on
        else R.drawable.bg_bubble_muted
        bubble.setBackgroundResource(bg)

        tvStatus.text = if (!ready) "বন্ধ। অ্যাপ থেকে শুরু করুন"
        else if (Engine.musicOn) "গান মাইকে যাচ্ছে"
        else "গান বন্ধ। আপনার কথা যাচ্ছে"

        btnMusic.text = if (Engine.musicOn) "গান: চালু" else "গান: বন্ধ"
        btnMusic.setBackgroundResource(
            if (Engine.musicOn) R.drawable.bg_btn_primary else R.drawable.bg_btn_ghost
        )

        val p = (Engine.gain * 100).toInt()
        if (sbGain.progress != p) sbGain.progress = p
        tvGain.text = "$p%"
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
