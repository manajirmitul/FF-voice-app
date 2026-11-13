package com.manajir.voicetest

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.lang.reflect.InvocationTargetException
import kotlin.math.sin

/**
 * Runs in a separate process with shell (adb) privileges, started by Shizuku.
 * Builds an Android audio policy "recorder mix" and injects PCM audio into
 * apps that record from the microphone.
 */
class MicService(private var ctx: Context?) : IMicService.Stub() {

    constructor() : this(null)

    private val log = StringBuilder()
    private val logFile = java.io.File("/data/local/tmp/micservice.log")
    private val writeLock = Any()

    private fun crumb(msg: String) {
        try {
            logFile.appendText((System.currentTimeMillis() % 1000000).toString() + " " + msg + "\n")
        } catch (_: Throwable) {
        }
    }

    init {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, e ->
            crumb("CRASH in " + th.name + ": " + err(e))
            prev?.uncaughtException(th, e)
        }
        crumb("service created pid=" + android.os.Process.myPid())
    }
    private var policy: Any? = null
    private var audioManager: AudioManager? = null
    private var track: AudioTrack? = null
    private var pipeIn: ParcelFileDescriptor? = null
    private var handlerThread: HandlerThread? = null
    @Volatile private var running = false
    private var reader: Thread? = null

    private fun l(s: String) {
        log.append(s).append('\n')
    }

    private fun err(e: Throwable): String {
        val c = if (e is InvocationTargetException) (e.targetException ?: e) else e
        val top = c.stackTrace.take(3).joinToString(" | ") { it.toString() }
        return c.toString() + " @ " + top
    }

    private fun obtainContext(): Context? {
        return try {
            val at = Class.forName("android.app.ActivityThread")
            val sys = at.getMethod("systemMain").invoke(null)
            at.getMethod("getSystemContext").invoke(sys) as Context
        } catch (e: Throwable) {
            l("context fallback failed: " + err(e))
            null
        }
    }

    private fun presetAttributes(preset: Int): AudioAttributes {
        val b = AudioAttributes.Builder()
        try {
            AudioAttributes.Builder::class.java
                .getMethod("setCapturePreset", Int::class.javaPrimitiveType)
                .invoke(b, preset)
        } catch (e: Throwable) {
            AudioAttributes.Builder::class.java
                .getMethod("setInternalCapturePreset", Int::class.javaPrimitiveType)
                .invoke(b, preset)
        }
        return b.build()
    }

    override fun lastLog(): String {
        return try {
            val t = logFile.readText()
            if (t.length > 3000) t.takeLast(3000) else t
        } catch (e: Throwable) {
            "no service log: $e"
        }
    }

    override fun destroy() {
        crumb("destroy() called by Shizuku")
        stop()
        System.exit(0)
    }

    override fun start(input: ParcelFileDescriptor, sampleRate: Int): String {
        // Inside a binder call the "calling uid" is our app, which lacks
        // MODIFY_AUDIO_ROUTING. Clear it so permission checks use the shell uid.
        val token = android.os.Binder.clearCallingIdentity()
        try {
            return doStart(input, sampleRate)
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }
    }

    private fun doStart(input: ParcelFileDescriptor, sampleRate: Int): String {
        log.setLength(0)
        try {
            logFile.writeText("")
        } catch (_: Throwable) {
        }
        crumb("start called")
        try {
            stop()
            l("uid=" + android.os.Process.myUid() + " sdk=" + android.os.Build.VERSION.SDK_INT +
                " device=" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)

            val context = ctx ?: obtainContext()
            l("context=" + context)
            if (context == null) {
                l("RESULT=FAIL (no context)")
                return log.toString()
            }

            // 1. mixing rule
            val ruleCls = Class.forName("android.media.audiopolicy.AudioMixingRule")
            val ruleBuilderCls = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            val rb = ruleBuilderCls.getConstructor().newInstance()

            try {
                val injector = ruleCls.getField("MIX_ROLE_INJECTOR").getInt(null)
                ruleBuilderCls.getMethod("setTargetMixRole", Int::class.javaPrimitiveType).invoke(rb, injector)
                l("mix role INJECTOR set (Android 13+ path)")
            } catch (e: Throwable) {
                l("mix role api not used: " + e.javaClass.simpleName)
            }

            val presetRule = ruleCls.getField("RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET").getInt(null)
            val addRule = ruleBuilderCls.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
            val presets = intArrayOf(
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.CAMCORDER,
                MediaRecorder.AudioSource.VOICE_RECOGNITION
            )
            for (p in presets) {
                try {
                    addRule.invoke(rb, presetRule, presetAttributes(p))
                    l("rule added for capture preset $p")
                } catch (e: Throwable) {
                    l("rule FAILED for preset $p: " + err(e))
                }
            }
            val rule = ruleBuilderCls.getMethod("build").invoke(rb)
            l("rule built")

            // 2. mix
            val mixCls = Class.forName("android.media.audiopolicy.AudioMix")
            val mixBuilderCls = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
            val mb = mixBuilderCls.getConstructor(ruleCls).newInstance(rule)
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            mixBuilderCls.getMethod("setFormat", AudioFormat::class.java).invoke(mb, format)
            val loopBack = mixCls.getField("ROUTE_FLAG_LOOP_BACK").getInt(null)
            mixBuilderCls.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mb, loopBack)
            val mix = mixBuilderCls.getMethod("build").invoke(mb)
            l("mix built")

            // 3. policy
            val policyCls = Class.forName("android.media.audiopolicy.AudioPolicy")
            val policyBuilderCls = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
            val pb = policyBuilderCls.getConstructor(Context::class.java).newInstance(context)
            val ht = HandlerThread("mic-policy")
            ht.start()
            handlerThread = ht
            policyBuilderCls.getMethod("setLooper", Looper::class.java).invoke(pb, ht.looper)
            policyBuilderCls.getMethod("addMix", mixCls).invoke(pb, mix)
            val pol = policyBuilderCls.getMethod("build").invoke(pb)
            l("policy built")

            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager = am
            val reg = AudioManager::class.java.getMethod("registerAudioPolicy", policyCls)
                .invoke(am, pol) as Int
            l("registerAudioPolicy result=$reg (0 means success)")
            if (reg != 0) {
                l("RESULT=FAIL (register)")
                return log.toString()
            }
            policy = pol

            // 4. injection track
            val t = policyCls.getMethod("createAudioTrackSource", mixCls).invoke(pol, mix) as AudioTrack?
            if (t == null) {
                l("createAudioTrackSource returned null")
                try {
                    val m = policyCls.getDeclaredMethod("policyReadyToUse")
                    m.isAccessible = true
                    l("policyReadyToUse=" + m.invoke(pol))
                } catch (e: Throwable) {
                    l("policyReadyToUse check failed: " + err(e))
                }
                l("RESULT=FAIL (no track)")
                return log.toString()
            }
            t.play()
            track = t
            l("track rate=" + t.sampleRate + " channels=" + t.channelCount + " state=" + t.playState)

            // 5. pump audio from the pipe into the track
            pipeIn = input
            running = true
            val ins = FileInputStream(input.fileDescriptor)
            reader = Thread {
                val buf = ByteArray(4096)
                var carry = 0
                var chunks = 0L
                var lastBeat = System.currentTimeMillis()
                crumb("reader started")
                try {
                    while (running) {
                        val n = ins.read(buf, carry, buf.size - carry)
                        if (n < 0) {
                            crumb("pipe closed (EOF)")
                            break
                        }
                        val total = carry + n
                        val even = total - (total % 2)
                        if (even > 0) {
                            val w = synchronized(writeLock) { t.write(buf, 0, even) }
                            if (w < 0) crumb("track.write returned $w")
                        }
                        carry = total - even
                        if (carry == 1) buf[0] = buf[even]
                        chunks++
                        if (chunks == 1L) crumb("first data received")
                        val now = System.currentTimeMillis()
                        if (now - lastBeat > 2000) {
                            crumb("alive chunks=$chunks")
                            lastBeat = now
                        }
                    }
                } catch (e: Throwable) {
                    crumb("reader error: " + err(e))
                }
                crumb("reader exit")
            }.also { it.start() }

            l("RESULT=OK")
        } catch (e: Throwable) {
            l("EXCEPTION: " + err(e))
            l("RESULT=FAIL")
        }
        return log.toString()
    }

    override fun tone(seconds: Int): String {
        val t = track ?: return "আগে মাইক পাথ চালু করুন"
        crumb("tone requested")
        Thread {
            try {
                val rate = t.sampleRate
                val ch = t.channelCount
                val total = rate * seconds
                val chunk = rate / 50
                var i = 0
                while (i < total && track === t) {
                    val end = minOf(i + chunk, total)
                    val data = ShortArray((end - i) * ch)
                    for (k in i until end) {
                        val v = (sin(2.0 * Math.PI * 440.0 * k / rate) * 12000).toInt().toShort()
                        for (c in 0 until ch) data[(k - i) * ch + c] = v
                    }
                    synchronized(writeLock) { t.write(data, 0, data.size) }
                    i = end
                }
                crumb("tone finished")
            } catch (e: Throwable) {
                crumb("tone error: " + err(e))
            }
        }.start()
        return "টোন পাঠানো হচ্ছে ($seconds সেকেন্ড)"
    }

    override fun stop() {
        crumb("stop called")
        running = false
        try { track?.stop() } catch (_: Throwable) {}
        try { track?.release() } catch (_: Throwable) {}
        track = null
        try { pipeIn?.close() } catch (_: Throwable) {}
        pipeIn = null
        try {
            val pol = policy
            val am = audioManager
            if (pol != null && am != null) {
                val policyCls = Class.forName("android.media.audiopolicy.AudioPolicy")
                AudioManager::class.java.getMethod("unregisterAudioPolicy", policyCls).invoke(am, pol)
            }
        } catch (_: Throwable) {}
        policy = null
        try { handlerThread?.quitSafely() } catch (_: Throwable) {}
        handlerThread = null
    }
}
