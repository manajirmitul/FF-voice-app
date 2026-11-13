package com.manajir.voicetest

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import rikka.shizuku.Shizuku
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList

/** Shared state + Shizuku plumbing used by the main screen and the floating overlay. */
object Engine {

    const val SAMPLE_RATE = 44100
    private const val REQ_SHIZUKU = 200

    private var appCtx: Context? = null

    @Volatile var svc: IMicService? = null
    @Volatile var pathReady = false
    @Volatile var captureOn = false
    @Volatile var busy = false
    @Volatile var musicOn = true
    @Volatile var gain = 1.0f
    @Volatile var sink: OutputStream? = null

    val listeners = CopyOnWriteArrayList<() -> Unit>()
    val levelListeners = CopyOnWriteArrayList<(Int) -> Unit>()

    private val logBuf = StringBuilder()
    private var pending: ((Boolean) -> Unit)? = null

    fun init(c: Context) {
        if (appCtx == null) {
            appCtx = c.applicationContext
            Shizuku.addRequestPermissionResultListener(permListener)
        }
    }

    fun notifyChange() {
        for (l in listeners) {
            try { l() } catch (_: Throwable) {}
        }
    }

    fun emitLevel(v: Int) {
        for (l in levelListeners) {
            try { l(v) } catch (_: Throwable) {}
        }
    }

    fun log(s: String) {
        synchronized(logBuf) {
            logBuf.append(s).append("\n\n")
            if (logBuf.length > 8000) logBuf.delete(0, logBuf.length - 6000)
        }
        notifyChange()
    }

    fun logText(): String = synchronized(logBuf) { logBuf.toString() }

    private val permListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == REQ_SHIZUKU) {
            if (result == PackageManager.PERMISSION_GRANTED) {
                bind()
            } else {
                log("Shizuku পারমিশন দেওয়া হয়নি")
                finish(false)
            }
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            svc = IMicService.Stub.asInterface(binder)
            log("Shizuku সার্ভিস সংযুক্ত")
            if (pending != null) startPath() else notifyChange()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            svc = null
            pathReady = false
            log("Shizuku সার্ভিস বিচ্ছিন্ন হয়েছে। বন্ধ করে আবার শুরু করুন")
        }
    }

    private fun args(): Shizuku.UserServiceArgs {
        val pkg = appCtx!!.packageName
        return Shizuku.UserServiceArgs(ComponentName(pkg, MicService::class.java.name))
            .daemon(false)
            .processNameSuffix("mic")
            .debuggable(false)
            .version(3)
    }

    /** Connects Shizuku (if needed) and builds the virtual mic path. */
    fun prepare(done: (Boolean) -> Unit) {
        busy = true
        pending = done
        notifyChange()
        Handler(Looper.getMainLooper()).postDelayed({
            if (pending != null) {
                log("সময় শেষ: সংযোগ হয়নি। Shizuku চালু আছে কিনা দেখুন")
                finish(false)
            }
        }, 25000)

        if (svc != null) {
            startPath()
            return
        }
        try {
            if (!Shizuku.pingBinder()) {
                log("Shizuku চালু নেই। Shizuku অ্যাপ খুলে চালু করুন")
                finish(false)
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bind()
            } else {
                Shizuku.requestPermission(REQ_SHIZUKU)
            }
        } catch (e: Throwable) {
            log("Shizuku ত্রুটি: $e")
            finish(false)
        }
    }

    private fun bind() {
        try {
            Shizuku.bindUserService(args(), conn)
            log("সার্ভিস চালু করা হচ্ছে...")
        } catch (e: Throwable) {
            log("bind ত্রুটি: $e")
            finish(false)
        }
    }

    private fun startPath() {
        val s = svc
        if (s == null) {
            finish(false)
            return
        }
        Thread {
            try {
                val pipe = ParcelFileDescriptor.createPipe()
                val readSide = pipe[0]
                val writeSide = pipe[1]
                try { sink?.close() } catch (_: Throwable) {}
                sink = ParcelFileDescriptor.AutoCloseOutputStream(writeSide)
                val res = s.start(readSide, SAMPLE_RATE)
                try { readSide.close() } catch (_: Throwable) {}
                pathReady = res.contains("RESULT=OK")
                log(res)
            } catch (e: Throwable) {
                pathReady = false
                log("পাথ ত্রুটি: $e")
            }
            finish(pathReady)
        }.start()
    }

    private fun finish(ok: Boolean) {
        val cb = pending
        pending = null
        if (!ok) busy = false
        cb?.invoke(ok)
        notifyChange()
    }

    fun stopPath() {
        try { sink?.close() } catch (_: Throwable) {}
        sink = null
        pathReady = false
        val s = svc
        if (s != null) {
            Thread {
                try { s.stop() } catch (_: Throwable) {}
                log("মাইক পাথ বন্ধ")
            }.start()
        }
        notifyChange()
    }

    fun stopAll(c: Context) {
        val i = Intent(c, CaptureService::class.java)
        i.action = CaptureService.ACTION_STOP
        c.startService(i)
        busy = false
        stopPath()
    }

    fun tone() {
        val s = svc
        if (s == null) {
            log("আগে শুরু করুন")
            return
        }
        Thread {
            val r = try { s.tone(3) } catch (e: Throwable) { "ত্রুটি: $e" }
            log(r)
        }.start()
    }

    fun fetchServiceLog() {
        val s = svc
        if (s == null) {
            log("সার্ভিস সংযুক্ত নেই")
            return
        }
        Thread {
            val t = try { s.lastLog() } catch (e: Throwable) { "লগ পড়া যায়নি: $e" }
            log("সার্ভিস লগ:\n" + t)
        }.start()
    }

    fun mediaKey(code: Int) {
        val c = appCtx ?: return
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
}
