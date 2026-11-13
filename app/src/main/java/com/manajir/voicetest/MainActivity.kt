package com.manajir.voicetest

import android.Manifest
import android.app.Activity
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
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private val reqProjection = 100
    private val reqPerms = 101
    private var updating = false

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

    private val changeListener: () -> Unit = { runOnUiThread { refresh() } }
    private val levelListener: (Int) -> Unit = { v -> runOnUiThread { levelBar.progress = v } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                        Engine.log("'অন্য অ্যাপের ওপরে দেখান' অনুমতি দিয়ে ফিরে এসে আবার চালু করুন")
                        refresh()
                    }
                } else {
                    stopService(Intent(this, OverlayService::class.java))
                }
            }
        }

        findViewById<Button>(R.id.btnTone).setOnClickListener { Engine.tone() }
        findViewById<Button>(R.id.btnLog).setOnClickListener {
            logBox.visibility = if (logBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("log", Engine.logText()))
            Engine.log("লগ কপি হয়েছে")
        }
        findViewById<Button>(R.id.btnSvcLog).setOnClickListener { Engine.fetchServiceLog() }

        Engine.listeners.add(changeListener)
        Engine.levelListeners.add(levelListener)
        refresh()
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

    private fun refresh() {
        updating = true
        setRow(dotShizuku, tvShizuku, Engine.svc != null, "সংযুক্ত", "সংযুক্ত নয়")
        setRow(dotPath, tvPath, Engine.pathReady, "প্রস্তুত", "বন্ধ")
        setRow(dotCapture, tvCapture, Engine.captureOn, "গান ধরছে", "বন্ধ")

        val running = Engine.captureOn || Engine.pathReady
        if (Engine.busy) {
            btnMain.text = "অপেক্ষা করুন..."
            btnMain.isEnabled = false
        } else if (running) {
            btnMain.text = "বন্ধ করুন"
            btnMain.setBackgroundResource(R.drawable.bg_btn_danger)
            btnMain.isEnabled = true
        } else {
            btnMain.text = "শুরু করুন"
            btnMain.setBackgroundResource(R.drawable.bg_btn_primary)
            btnMain.isEnabled = true
        }

        musicSwitch.isChecked = Engine.musicOn
        overlaySwitch.isChecked = OverlayService.running
        val p = (Engine.gain * 100).toInt()
        if (sbGain.progress != p) sbGain.progress = p
        tvGain.text = "$p%"
        logView.text = Engine.logText()
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
            if (ok) runOnUiThread { askProjection() }
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startAll()
        } else {
            Engine.log("মাইক্রোফোন পারমিশন দরকার")
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
                Engine.log("গান ধরার অনুমতি দেওয়া হয়নি")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        Engine.listeners.remove(changeListener)
        Engine.levelListeners.remove(levelListener)
        super.onDestroy()
    }
}
