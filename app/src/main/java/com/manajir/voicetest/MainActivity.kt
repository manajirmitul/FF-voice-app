package com.manajir.voicetest

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Lang.wrap(base))
    }

    private val reqProjection = 100
    private val reqPerms = 101
    private val reqPick = 102
    private var updating = false
    private var libStamp = ""
    private var ready = false

    private lateinit var dotShizuku: View
    private lateinit var dotPath: View
    private lateinit var dotCapture: View
    private lateinit var tvShizuku: TextView
    private lateinit var tvPath: TextView
    private lateinit var tvCapture: TextView
    private lateinit var btnMain: Button
    private lateinit var musicSwitch: Switch
    private lateinit var overlaySwitch: Switch
    private lateinit var sbGain: SeekBar
    private lateinit var tvGain: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var logBox: LinearLayout
    private lateinit var logView: TextView
    private lateinit var libList: LinearLayout

    private val changeListener: () -> Unit = { runOnUiThread { refresh() } }
    private val levelListener: (Int) -> Unit = { v -> runOnUiThread { levelBar.progress = v } }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Auth.isLoggedIn(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        if (!Access.cachedActive(this)) {
            startActivity(Intent(this, AccessActivity::class.java))
            finish()
            return
        }
        ready = true
        setContentView(R.layout.activity_main)
        Engine.init(this)

        dotShizuku = findViewById(R.id.dotShizuku)
        dotPath = findViewById(R.id.dotPath)
        dotCapture = findViewById(R.id.dotCapture)
        tvShizuku = findViewById(R.id.tvShizuku)
        tvPath = findViewById(R.id.tvPath)
        tvCapture = findViewById(R.id.tvCapture)
        btnMain = findViewById(R.id.btnMain)
        musicSwitch = findViewById(R.id.musicSwitch)
        overlaySwitch = findViewById(R.id.overlaySwitch)
        sbGain = findViewById(R.id.sbGain)
        tvGain = findViewById(R.id.tvGain)
        levelBar = findViewById(R.id.levelBar)
        logBox = findViewById(R.id.logBox)
        logView = findViewById(R.id.logView)
        libList = findViewById(R.id.libList)

        btnMain.setOnClickListener {
            if (Engine.captureOn || Engine.pathReady) Engine.stopAll(this) else startAll()
        }

        musicSwitch.setOnCheckedChangeListener { _, checked ->
            if (!updating) {
                Engine.musicOn = checked
                Engine.notifyChange()
            }
        }

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

        overlaySwitch.setOnCheckedChangeListener { _, checked ->
            if (!updating) {
                if (checked) {
                    if (Settings.canDrawOverlays(this)) {
                        startService(Intent(this, OverlayService::class.java))
                    } else {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                        Engine.log(getString(R.string.overlay_perm_log))
                        refresh()
                    }
                } else {
                    stopService(Intent(this, OverlayService::class.java))
                }
            }
        }

        findViewById<Button>(R.id.btnImport).setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "audio/*"
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            startActivityForResult(i, reqPick)
        }
        findViewById<Button>(R.id.btnTone).setOnClickListener { Engine.tone() }
        findViewById<Button>(R.id.btnLog).setOnClickListener {
            logBox.visibility = if (logBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("log", Engine.logText()))
            Engine.log(getString(R.string.log_copied))
        }
        findViewById<Button>(R.id.btnSvcLog).setOnClickListener { Engine.fetchServiceLog() }

        findViewById<TextView>(R.id.btnLang).setOnClickListener {
            Lang.showPicker(this, true) {
                if (OverlayService.running) {
                    stopService(Intent(this, OverlayService::class.java))
                    startService(Intent(this, OverlayService::class.java))
                }
                recreate()
            }
        }
        findViewById<TextView>(R.id.tvUser).text = Auth.email(this)
        findViewById<TextView>(R.id.btnLogout).setOnClickListener { doLogout() }
        if (!Auth.keyMissing()) {
            Auth.validate(this) { valid ->
                if (!valid) doLogout()
            }
        }

        // Re-check the plan against the server; leave if it is no longer active.
        Access.check(this) { st ->
            if (st.code == Access.NEED_PLAN || st.code == Access.NEED_KEY) {
                Engine.stopAll(this)
                stopService(Intent(this, OverlayService::class.java))
                startActivity(Intent(this, AccessActivity::class.java))
                finish()
            }
        }

        Engine.listeners.add(changeListener)
        Engine.levelListeners.add(levelListener)
        refresh()
    }

    private fun doLogout() {
        Engine.stopAll(this)
        stopService(Intent(this, OverlayService::class.java))
        Auth.signOut(this)
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun dot(v: View, color: Int) {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(color)
        v.background = d
    }

    private fun setRow(d: View, t: TextView, on: Boolean, onText: String, offText: String) {
        dot(d, getColor(if (on) R.color.ok else R.color.dot_off))
        t.text = if (on) onText else offText
        t.setTextColor(getColor(if (on) R.color.ok else R.color.muted))
    }

    private fun smallBtn(label: String, action: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.setTextColor(getColor(R.color.text))
        t.textSize = 14f
        t.gravity = Gravity.CENTER
        t.setPadding(dp(14), dp(8), dp(14), dp(8))
        t.setBackgroundResource(R.drawable.bg_btn_ghost)
        t.setOnClickListener { action() }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.marginStart = dp(8)
        t.layoutParams = lp
        return t
    }

    private fun rebuildLibrary() {
        val stamp = "" + Library.version + "|" + Player.currentFile + "|" + Player.paused
        if (stamp == libStamp) return
        libStamp = stamp
        libList.removeAllViews()

        val files = Library.list(this)
        if (files.isEmpty()) {
            val empty = TextView(this)
            empty.text = getString(R.string.library_empty)
            empty.setTextColor(getColor(R.color.muted))
            empty.textSize = 13f
            empty.setPadding(0, dp(6), 0, dp(6))
            libList.addView(empty)
            return
        }

        for (f in files) {
            val isCur = Player.playing && Player.currentFile == f
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(6), 0, dp(6))

            val tv = TextView(this)
            tv.text = Library.displayName(f)
            tv.setTextColor(getColor(if (isCur) R.color.accent2 else R.color.text))
            tv.textSize = 14f
            tv.maxLines = 1
            tv.ellipsize = TextUtils.TruncateAt.END
            row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            row.addView(smallBtn(if (isCur && !Player.paused) "❚❚" else "▶") {
                if (isCur) Player.togglePause() else Player.request(f)
            })
            if (isCur) row.addView(smallBtn("■") { Player.stop() })
            row.addView(smallBtn("✕") {
                AlertDialog.Builder(this)
                    .setMessage(getString(R.string.delete_audio_q))
                    .setPositiveButton(getString(R.string.yes)) { _, _ -> Library.delete(f) }
                    .setNegativeButton(getString(R.string.no), null)
                    .show()
            })
            libList.addView(row)
        }
    }

    private fun refresh() {
        updating = true
        setRow(dotShizuku, tvShizuku, Engine.svc != null, getString(R.string.connected), getString(R.string.not_connected))
        setRow(dotPath, tvPath, Engine.pathReady, getString(R.string.ready), getString(R.string.off))
        setRow(dotCapture, tvCapture, Engine.captureOn, getString(R.string.capturing_music), getString(R.string.off))

        val running = Engine.captureOn || Engine.pathReady
        if (Engine.busy) {
            btnMain.text = getString(R.string.wait)
            btnMain.isEnabled = false
        } else if (running) {
            btnMain.text = getString(R.string.stop)
            btnMain.setBackgroundResource(R.drawable.bg_btn_danger)
            btnMain.isEnabled = true
        } else {
            btnMain.text = getString(R.string.start)
            btnMain.setBackgroundResource(R.drawable.bg_btn_primary)
            btnMain.isEnabled = true
        }

        musicSwitch.isChecked = Engine.musicOn
        overlaySwitch.isChecked = OverlayService.running
        val p = (Engine.gain * 100).toInt()
        if (sbGain.progress != p) sbGain.progress = p
        tvGain.text = "$p%"
        logView.text = Engine.logText()
        rebuildLibrary()
        updating = false
    }

    private fun startAll() {
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), reqPerms)
            return
        }
        Engine.prepare { ok ->
            if (ok) {
                runOnUiThread {
                    Engine.busy = true
                    Engine.notifyChange()
                    askProjection()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startAll()
        } else {
            Engine.log(getString(R.string.mic_perm_needed))
        }
    }

    private fun askProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), reqProjection)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqProjection) {
            if (resultCode == RESULT_OK && data != null) {
                val i = Intent(this, CaptureService::class.java)
                i.action = CaptureService.ACTION_START
                i.putExtra(CaptureService.EXTRA_CODE, resultCode)
                i.putExtra(CaptureService.EXTRA_DATA, data)
                startForegroundService(i)
            } else {
                Engine.busy = false
                Engine.log(getString(R.string.capture_perm_denied))
            }
        } else if (requestCode == reqPick) {
            if (resultCode == RESULT_OK && data != null) {
                val uris = ArrayList<Uri>()
                val cd = data.clipData
                if (cd != null) {
                    for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri)
                } else {
                    val single = data.data
                    if (single != null) uris.add(single)
                }
                Thread {
                    var count = 0
                    for (u in uris) {
                        if (Library.import(this@MainActivity, u)) count++
                    }
                    Engine.log(getString(R.string.audios_added, count))
                    Engine.notifyChange()
                }.start()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (ready) refresh()
    }

    override fun onDestroy() {
        Engine.listeners.remove(changeListener)
        Engine.levelListeners.remove(levelListener)
        super.onDestroy()
    }
}
