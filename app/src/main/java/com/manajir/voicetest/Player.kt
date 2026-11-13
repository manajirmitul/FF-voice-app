package com.manajir.voicetest

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

private fun MediaFormat.intOr(key: String, d: Int): Int =
    if (containsKey(key)) getInteger(key) else d

/** Streaming linear resampler for one channel. */
private class Resampler(private val ratio: Double) {
    private var pos = 0.0
    private var prev = 0

    fun process(src: ShortArray, n: Int): ShortArray {
        val cap = (((n - pos) / ratio).toInt() + 2).coerceAtLeast(0)
        val out = ShortArray(cap)
        var o = 0
        var p = pos
        while (o < out.size) {
            val i = Math.floor(p).toInt()
            if (i + 1 >= n) break
            val a = if (i < 0) prev else src[i].toInt()
            val b = src[i + 1].toInt()
            val frac = p - i
            out[o++] = (a + (b - a) * frac).toInt().toShort()
            p += ratio
        }
        pos = p - n
        if (n > 0) prev = src[n - 1].toInt()
        return out.copyOf(o)
    }
}

/**
 * Decodes an imported audio file and plays it:
 *  - into the virtual microphone (mono, with gain), and
 *  - to the user's own ears through a local AudioTrack (stereo).
 */
object Player {

    @Volatile var currentFile: File? = null
    @Volatile var playing = false
    @Volatile var paused = false
    @Volatile private var token = 0

    /** Starts the mic path first if needed, then plays. */
    fun request(f: File) {
        if (Engine.pathReady) {
            play(f)
        } else {
            if (Engine.busy) return
            Engine.log("মাইক পাথ চালু হচ্ছে...")
            Engine.prepare { ok -> if (ok) play(f) }
        }
    }

    @Synchronized
    fun play(f: File) {
        val my = ++token
        currentFile = f
        playing = true
        paused = false
        Engine.notifyChange()
        Thread {
            try {
                decode(f, my)
            } catch (e: Throwable) {
                Engine.log("প্লে ত্রুটি: $e")
            }
            synchronized(Player) {
                if (token == my) {
                    playing = false
                    paused = false
                    currentFile = null
                    Engine.notifyChange()
                }
            }
        }.start()
    }

    @Synchronized
    fun stop() {
        token++
        playing = false
        paused = false
        currentFile = null
        Engine.notifyChange()
    }

    fun togglePause() {
        if (playing) {
            paused = !paused
            Engine.notifyChange()
        }
    }

    private fun decode(f: File, my: Int) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        try {
            val ex = MediaExtractor()
            extractor = ex
            ex.setDataSource(f.absolutePath)

            var idx = -1
            var found: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val tf = ex.getTrackFormat(i)
                val m = tf.getString(MediaFormat.KEY_MIME) ?: ""
                if (m.startsWith("audio/")) {
                    idx = i
                    found = tf
                    break
                }
            }
            val fmt: MediaFormat = found ?: run {
                Engine.log("এই ফাইলে অডিও পাওয়া যায়নি")
                return
            }
            ex.selectTrack(idx)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: "audio/mpeg"

            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()

            val outRate = Engine.SAMPLE_RATE
            val minBuf = AudioTrack.getMinBufferSize(
                outRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            val at = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(outRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, outRate / 5 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = at
            at.play()

            var inRate = fmt.intOr(MediaFormat.KEY_SAMPLE_RATE, outRate)
            var inCh = fmt.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var resL = Resampler(inRate.toDouble() / outRate)
            var resR = Resampler(inRate.toDouble() / outRate)

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone && token == my) {
                while (paused && token == my) Thread.sleep(40)
                if (token != my) break

                if (!inputDone) {
                    val ii = c.dequeueInputBuffer(10000)
                    if (ii >= 0) {
                        val ib = c.getInputBuffer(ii)!!
                        val sz = ex.readSampleData(ib, 0)
                        if (sz < 0) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }

                val oi = c.dequeueOutputBuffer(info, 10000)
                if (oi >= 0) {
                    if (info.size > 0) {
                        val ob = c.getOutputBuffer(oi)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val total = sb.remaining()
                        val shorts = ShortArray(total)
                        sb.get(shorts)
                        feed(shorts, total, inCh, inRate, outRate, resL, resR, at)
                    }
                    c.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = c.outputFormat
                    inRate = nf.intOr(MediaFormat.KEY_SAMPLE_RATE, inRate)
                    inCh = nf.intOr(MediaFormat.KEY_CHANNEL_COUNT, inCh)
                    resL = Resampler(inRate.toDouble() / outRate)
                    resR = Resampler(inRate.toDouble() / outRate)
                    val enc = nf.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    if (enc != AudioFormat.ENCODING_PCM_16BIT) {
                        Engine.log("এই ফাইলের অডিও ফরম্যাট সমর্থিত নয়")
                        break
                    }
                }
            }
            if (token == my) Thread.sleep(250)
        } finally {
            try { track?.stop() } catch (_: Throwable) {}
            try { track?.release() } catch (_: Throwable) {}
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor?.release() } catch (_: Throwable) {}
        }
    }

    private fun feed(
        shorts: ShortArray, total: Int, inCh: Int, inRate: Int, outRate: Int,
        resL: Resampler, resR: Resampler, at: AudioTrack
    ) {
        val ch = if (inCh < 1) 1 else inCh
        val frames = total / ch
        if (frames <= 0) return
        val l = ShortArray(frames)
        val r = ShortArray(frames)
        for (i in 0 until frames) {
            if (ch == 1) {
                l[i] = shorts[i]
                r[i] = shorts[i]
            } else {
                l[i] = shorts[i * ch]
                r[i] = shorts[i * ch + 1]
            }
        }
        val lo: ShortArray
        val ro: ShortArray
        if (inRate == outRate) {
            lo = l
            ro = r
        } else {
            lo = resL.process(l, frames)
            ro = resR.process(r, frames)
        }

        val m = minOf(lo.size, ro.size)
        if (m == 0) return
        val g = Engine.gain
        val mono = ByteArray(m * 2)
        val stereo = ShortArray(m * 2)
        for (k in 0 until m) {
            val a = lo[k].toInt()
            val b = ro[k].toInt()
            stereo[2 * k] = a.toShort()
            stereo[2 * k + 1] = b.toShort()
            var v = (((a + b) / 2) * g).toInt()
            if (v > 32767) v = 32767
            if (v < -32768) v = -32768
            mono[2 * k] = (v and 0xff).toByte()
            mono[2 * k + 1] = (v shr 8).toByte()
        }

        if (Engine.musicOn) Engine.writeSink(mono, mono.size)
        at.write(stereo, 0, stereo.size)
    }
}
