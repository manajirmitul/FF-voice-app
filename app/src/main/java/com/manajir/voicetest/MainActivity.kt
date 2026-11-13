package com.manajir.voicetest

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private val reqProjection = 100
    private val reqPerms = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 120, 48, 48)
        }

        val title = TextView(this).apply {
            text = "অডিও ক্যাপচার টেস্ট"
            textSize = 24f
        }
        status = TextView(this).apply {
            text = "বন্ধ আছে"
            textSize = 16f
            setPadding(0, 32, 0, 32)
        }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        val startBtn = Button(this).apply {
            text = "শুরু করুন"
            setOnClickListener { begin() }
        }
        val stopBtn = Button(this).apply {
            text = "বন্ধ করুন"
            setOnClickListener {
                startService(Intent(this@MainActivity, CaptureService::class.java).apply {
                    action = CaptureService.ACTION_STOP
                })
            }
        }
        val hint = TextView(this).apply {
            text = "শুরু করার পর YouTube বা মিউজিক অ্যাপে গান চালান। বার নড়লে ফোনের ভেতরের অডিও ধরা পড়ছে।"
            textSize = 14f
            setPadding(0, 48, 0, 0)
        }

        root.addView(title)
        root.addView(status)
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 60))
        root.addView(startBtn)
        root.addView(stopBtn)
        root.addView(hint)
        setContentView(root)

        CaptureService.onLevel = { lvl -> runOnUiThread { bar.progress = lvl } }
        CaptureService.onStatus = { s -> runOnUiThread { status.text = s } }
    }

    private fun begin() {
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isEmpty()) askProjection() else requestPermissions(need.toTypedArray(), reqPerms)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            askProjection()
        } else {
            status.text = "মাইক্রোফোন পারমিশন দরকার"
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
                val i = Intent(this, CaptureService::class.java).apply {
                    action = CaptureService.ACTION_START
                    putExtra(CaptureService.EXTRA_CODE, resultCode)
                    putExtra(CaptureService.EXTRA_DATA, data)
                }
                startForegroundService(i)
            } else {
                status.text = "অনুমতি দেওয়া হয়নি"
            }
        }
    }

    override fun onDestroy() {
        CaptureService.onLevel = null
        CaptureService.onStatus = null
        super.onDestroy()
    }
}
