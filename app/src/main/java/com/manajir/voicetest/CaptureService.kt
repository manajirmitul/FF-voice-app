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
import kotlin.math.abs

class CaptureService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "capture"

        @Volatile var running = false
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
            .setContentTitle("Audio Capture Test")
            .setContentText("ফোনের ভেতরের অডিও ধরা হচ্ছে")
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

            val config = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(44100)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val minBuf = AudioRecord.getMinBufferSize(
                44100, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )

            val rec = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            record = rec
            rec.startRecording()
            running = true
            onStatus?.invoke("চলছে। এখন অন্য অ্যাপে গান চালান")

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
        onStatus?.invoke("বন্ধ আছে")
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
