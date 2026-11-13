package com.manajir.voicetest

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private val reqProjection = 100
    private val reqPerms = 101
    private val reqShizuku = 200

    private lateinit var logView: TextView
    private lateinit var bar: ProgressBar
    private var svc: IMicService? = null

    private val permListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == reqShizuku) {
            if (result == PackageManager.PERMISSION_GRANTED) bindSvc()
            else log("Shizuku পারমিশন দেওয়া হয়নি")
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            svc = IMicService.Stub.asInterface(binder)
            log("Shizuku সার্ভিস সংযুক্ত হয়েছে")
            showServiceLog()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            svc = null
            log("Shizuku সার্ভিস বিচ্ছিন্ন")
        }
    }

    private fun userArgs() = Shizuku.UserServiceArgs(
        ComponentName(packageName, MicService::class.java.name)
    ).daemon(false).processNameSuffix("mic").debuggable(false).version(2)

    private fun showServiceLog() {
        val s = svc ?: return
        Thread {
            val t = try { s.lastLog() } catch (e: Throwable) { "লগ পড়া যায়নি: $e" }
            log("সার্ভিস লগ:\n" + t)
        }.start()
    }

    private fun log(s: String) {
        runOnUiThread { logView.append(s + "\n\n") }
    }

    private fun makeButton(label: String, action: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.setOnClickListener { action() }
        return b
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 100, 40, 40)
        }

        val title = TextView(this)
        title.text = "অডিও মাইক টেস্ট"
        title.textSize = 22f

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100

        logView = TextView(this)
        logView.textSize = 13f
        logView.setTextIsSelectable(true)
        logView.setPadding(0, 32, 0, 0)

        root.addView(title)
        root.addView(makeButton("১. Shizuku সংযোগ") { connectShizuku() })
        root.addView(makeButton("২. মাইক পাথ চালু করুন") { startPath() })
        root.addView(makeButton("৩. টেস্ট টোন (৩ সেকেন্ড)") { playTone() })
        root.addView(makeButton("৪. গান ধরা শুরু") { beginCapture() })
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 60))
        root.addView(makeButton("সব বন্ধ করুন") { stopAll() })
        root.addView(makeButton("সার্ভিস লগ দেখুন") { showServiceLog() })
        root.addView(makeButton("লগ কপি করুন") { copyLog() })
        root.addView(logView)
        scroll.addView(root)
        setContentView(scroll)

        Shizuku.addRequestPermissionResultListener(permListener)
        CaptureService.onLevel = { lvl -> runOnUiThread { bar.progress = lvl } }
        CaptureService.onStatus = { s -> log(s) }
        log("প্রথমে ১ নম্বর বাটন চাপুন")
    }

    private fun connectShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                log("Shizuku চালু নেই। Shizuku অ্যাপ খুলে চালু করুন।")
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bindSvc()
            } else {
                Shizuku.requestPermission(reqShizuku)
            }
        } catch (e: Throwable) {
            log("Shizuku ত্রুটি: $e")
        }
    }

    private fun bindSvc() {
        try {
            Shizuku.bindUserService(userArgs(), conn)
            log("সার্ভিস চালু করা হচ্ছে...")
        } catch (e: Throwable) {
            log("bind ত্রুটি: $e")
        }
    }

    private fun startPath() {
        val s = svc
        if (s == null) {
            log("আগে ১ নম্বর বাটন চাপুন এবং সংযোগের অপেক্ষা করুন")
            return
        }
        try {
            val pipe = ParcelFileDescriptor.createPipe()
            val readSide = pipe[0]
            val writeSide = pipe[1]
            CaptureService.sink = ParcelFileDescriptor.AutoCloseOutputStream(writeSide)
            Thread {
                val res = try {
                    s.start(readSide, CaptureService.SAMPLE_RATE)
                } catch (e: Throwable) {
                    "ত্রুটি: $e"
                }
                try { readSide.close() } catch (_: Throwable) {}
                log(res)
            }.start()
        } catch (e: Throwable) {
            log("পাথ ত্রুটি: $e")
        }
    }

    private fun playTone() {
        val s = svc
        if (s == null) {
            log("আগে ১ ও ২ নম্বর ধাপ করুন")
            return
        }
        Thread {
            val res = try { s.tone(3) } catch (e: Throwable) { "ত্রুটি: $e" }
            log(res)
        }.start()
    }

    private fun beginCapture() {
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
            log("মাইক্রোফোন পারমিশন দরকার")
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
                log("অনুমতি দেওয়া হয়নি")
            }
        }
    }

    private fun stopAll() {
        startService(Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_STOP
        })
        try { CaptureService.sink?.close() } catch (_: Throwable) {}
        CaptureService.sink = null
        val s = svc
        if (s != null) {
            Thread {
                try { s.stop() } catch (_: Throwable) {}
                log("মাইক পাথ বন্ধ")
            }.start()
        }
    }

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("log", logView.text.toString()))
        log("লগ কপি হয়েছে")
    }

    override fun onDestroy() {
        try { Shizuku.removeRequestPermissionResultListener(permListener) } catch (_: Throwable) {}
        CaptureService.onLevel = null
        CaptureService.onStatus = null
        super.onDestroy()
    }
}
