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
import java.io.IOException
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
                log(Lang.get(R.string.shizuku_perm_denied))
                finish(false)
            }
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            svc = IMicService.Stub.asInterface(binder)
            log(Lang.get(R.string.shizuku_service_connected))
            if (pending != null) startPath() else notifyChange()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            svc = null
            pathReady = false
            log(Lang.get(R.string.shizuku_service_disconnected))
        }
    }

    private fun args(): Shizuku.UserServiceArgs {
        val pkg = appCtx!!.packageName
        return Shizuku.UserServiceArgs(ComponentName(pkg, MicService::class.java.name))
            .daemon(false)
            .processNameSuffix("mic")
            .debuggable(false)
            .version(4)
    }

    /** Connects Shizuku (if needed) and builds the virtual mic path. */
    fun prepare(done: (Boolean) -> Unit) {
        busy = true
        pending = done
        notifyChange()
        Handler(Looper.getMainLooper()).postDelayed({
            if (pending != null) {
                log(Lang.get(R.string.timeout_connect))
                finish(false)
            }
        }, 25000)

        if (svc != null) {
            startPath()
            return
        }
        try {
            if (!Shizuku.pingBinder()) {
                log(Lang.get(R.string.shizuku_not_running))
                finish(false)
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bind()
            } else {
                Shizuku.requestPermission(REQ_SHIZUKU)
            }
        } catch (e: Throwable) {
            log(Lang.get(R.string.shizuku_error, e.toString()))
            finish(false)
        }
    }

    private fun bind() {
        try {
            Shizuku.bindUserService(args(), conn)
            log(Lang.get(R.string.starting_service))
        } catch (e: Throwable) {
            log(Lang.get(R.string.bind_error, e.toString()))
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
                log(Lang.get(R.string.path_error, e.toString()))
            }
            finish(pathReady)
        }.start()
    }

    private fun finish(ok: Boolean) {
        val cb = pending
        pending = null
        busy = false
        cb?.invoke(ok)
        notifyChange()
    }

    private val sinkLock = Any()

    /** Thread-safe write of 16-bit mono PCM (even length) into the mic pipe. */
    fun writeSink(b: ByteArray, len: Int): Boolean {
        val out = sink ?: return false
        synchronized(sinkLock) {
            try {
                out.write(b, 0, len)
                return true
            } catch (e: IOException) {
                if (sink === out) sink = null
                log(Lang.get(R.string.pipe_broken))
                return false
            }
        }
    }

    fun stopPath() {
        try { sink?.close() } catch (_: Throwable) {}
        sink = null
        pathReady = false
        val s = svc
        if (s != null) {
            Thread {
                try { s.stop() } catch (_: Throwable) {}
                log(Lang.get(R.string.mic_path_closed))
            }.start()
        }
        notifyChange()
    }

    fun stopAll(c: Context) {
        val i = Intent(c, CaptureService::class.java)
        i.action = CaptureService.ACTION_STOP
        c.startService(i)
        busy = false
        Player.stop()
        stopPath()
    }

    fun tone() {
        val s = svc
        if (s == null) {
            log(Lang.get(R.string.start_first))
            return
        }
        Thread {
            val r = try { s.tone(3) } catch (e: Throwable) { Lang.get(R.string.error_fmt, e.toString()) }
            log(
                when {
                    r == "ERR_NO_PATH" -> Lang.get(R.string.start_mic_path_first)
                    r.startsWith("TONE_SENDING:") ->
                        Lang.get(R.string.tone_sending, r.substringAfter(':').toIntOrNull() ?: 3)
                    else -> r
                }
            )
        }.start()
    }

    fun fetchServiceLog() {
        val s = svc
        if (s == null) {
            log(Lang.get(R.string.service_not_connected))
            return
        }
        Thread {
            val t = try { s.lastLog() } catch (e: Throwable) { Lang.get(R.string.log_read_failed, e.toString()) }
            log(Lang.get(R.string.service_log) + ":\n" + t)
        }.start()
    }

    fun mediaKey(code: Int) {
        val c = appCtx ?: return
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
}
