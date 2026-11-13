package com.manajir.voicetest

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.IOException
import java.io.OutputStream
import kotlin.math.abs

class CaptureService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "capture"
        const val SAMPLE_RATE = 44100

        @Volatile var running = false
        @Volatile var sink: OutputStream? = null
        var onLevel: ((Int) -> Unit)? = null
        var onStatus: ((String) -> Unit)? = null
    }

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START) {
            startForegroundNow()
            val code = intent.getIntExtra(EXTRA_CODE, 0)
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_DATA)
            }
            if (data == null) {
                onStatus?.invoke("অনুমতির তথ্য পাওয়া যায়নি")
                stopSelf()
            } else {
                startCapture(code, data)
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNow() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Audio capture", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Mic Test")
            .setContentText("ফোনের গান ধরা হচ্ছে")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    @SuppressLint("MissingPermission")
    private fun startCapture(code: Int, data: Intent) {
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val mp = mpm.getMediaProjection(code, data)
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCapture()
                }
            }, Handler(Looper.getMainLooper()))
            projection = mp

            // Only music/media. Game sound and voice chat are NOT captured,
            // so they don't get fed back into the mic.
            val config = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )

            val rec = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            record = rec
            rec.startRecording()
            running = true
            onStatus?.invoke("গান ধরা চলছে। এখন মিউজিক অ্যাপে গান চালান")

            worker = Thread {
                val buf = ShortArray(2048)
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        var peak = 0
                        for (i in 0 until n) {
                            val v = abs(buf[i].toInt())
                            if (v > peak) peak = v
                        }
                        onLevel?.invoke((peak * 100 / 32767).coerceAtMost(100))

                        val out = sink
                        if (out != null) {
                            val bytes = ByteArray(n * 2)
                            for (i in 0 until n) {
                                val s = buf[i].toInt()
                                bytes[2 * i] = (s and 0xff).toByte()
                                bytes[2 * i + 1] = (s shr 8).toByte()
                            }
                            try {
                                out.write(bytes)
                            } catch (e: IOException) {
                                sink = null
                                onStatus?.invoke("পাইপ ভেঙে গেছে: মাইক সার্ভিস আর পড়ছে না")
                            }
                        }
                    }
                }
            }.also { it.start() }
        } catch (e: Exception) {
            onStatus?.invoke("ত্রুটি: " + e.javaClass.simpleName + " " + (e.message ?: ""))
            stopCapture()
            stopSelf()
        }
    }

    private fun stopCapture() {
        running = false
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        onLevel?.invoke(0)
        onStatus?.invoke("গান ধরা বন্ধ")
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
