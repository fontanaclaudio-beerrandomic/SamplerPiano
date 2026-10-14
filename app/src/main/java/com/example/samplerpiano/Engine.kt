package com.example.samplerpiano

import android.content.Context
import android.media.*
import android.net.Uri
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.pow

class Voice(val step: Double, val loop: Boolean) {
    @Volatile var pos = 0.0
    @Volatile var released = false
    var env = 0f
}

class Engine {
    @Volatile var sample: FloatArray? = null
    @Volatile var sampleRate = 44100
    @Volatile var root = 60
    @Volatile var loop = false
    private val voices = CopyOnWriteArrayList<Voice>()
    private val outRate = 44100
    @Volatile private var running = true

    init { Thread { run() }.start() }

    fun release() { running = false }

    /** Suona la nota MIDI n pitchando il campione. */
    fun noteOn(n: Int): Voice? {
        if (sample == null) return null
        val v = Voice(2.0.pow((n - root) / 12.0) * sampleRate / outRate, loop)
        voices.add(v)
        return v
    }

    fun noteOff(v: Voice?) { if (v != null && v.loop) v.released = true }

    private fun run() {
        val min = AudioTrack.getMinBufferSize(outRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(outRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min, 1024))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build()
        track.play()
        val buf = ShortArray(256)
        val mix = FloatArray(256)
        while (running) {
            java.util.Arrays.fill(mix, 0f)
            val s = sample
            if (s != null && s.size > 2) for (v in voices) {
                for (i in mix.indices) {
                    if (v.released) v.env -= 1f / 6600f
                    else if (v.env < 1f) v.env = minOf(1f, v.env + 1f / 220f)
                    if (v.released && v.env <= 0f) { voices.remove(v); break }
                    var p = v.pos
                    if (p >= s.size - 1) {
                        if (v.loop) p %= (s.size - 1) else { voices.remove(v); break }
                    }
                    val i0 = p.toInt()
                    val f = (p - i0).toFloat()
                    mix[i] += (s[i0] * (1 - f) + s[i0 + 1] * f) * v.env * 0.5f
                    v.pos = p + v.step
                }
            }
            for (i in mix.indices) buf[i] = (mix[i].coerceIn(-1f, 1f) * 32767).toInt().toShort()
            track.write(buf, 0, buf.size)
        }
        track.release()
    }

    // ---------- registrazione dal microfono ----------
    private var rec: AudioRecord? = null
    private var recThread: Thread? = null
    private var chunks = ArrayList<ShortArray>()

    @Suppress("MissingPermission")
    fun startRecording() {
        val rate = 44100
        val bs = maxOf(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 4096)
        val r = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bs)
        val list = ArrayList<ShortArray>()
        chunks = list
        rec = r
        r.startRecording()
        recThread = Thread {
            val b = ShortArray(2048)
            while (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val n = r.read(b, 0, b.size)
                if (n > 0) list.add(b.copyOf(n))
            }
        }.also { it.start() }
    }

    /** Ferma la registrazione; ritorna la durata in secondi (0 se vuota). */
    fun stopRecording(): Double {
        val r = rec ?: return 0.0
        r.stop(); recThread?.join(); r.release(); rec = null
        val all = FloatArray(chunks.sumOf { it.size })
        var k = 0
        for (c in chunks) for (x in c) all[k++] = x / 32768f
        var a = 0; var b = all.size
        while (a < b && abs(all[a]) < 0.02f) a++
        while (b > a && abs(all[b - 1]) < 0.01f) b--
        if (b - a < 200) return 0.0
        sample = all.copyOfRange(maxOf(0, a - 50), b)
        sampleRate = 44100
        return sample!!.size / 44100.0
    }

    // ---------- caricamento da file ----------
    fun load(ctx: Context, uri: Uri): Double {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        var t = -1
        for (i in 0 until ex.trackCount)
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/")) { t = i; break }
        require(t >= 0) { "nessuna traccia audio" }
        ex.selectTrack(t)
        val fmt = ex.getTrackFormat(t)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val data = FloatArray(sr * 30) // massimo 30 secondi
        var n = 0
        val info = MediaCodec.BufferInfo()
        var eos = false; var done = false
        while (!done && n < data.size) {
            if (!eos) {
                val i = codec.dequeueInputBuffer(10000)
                if (i >= 0) {
                    val sz = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (sz < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true }
                    else { codec.queueInputBuffer(i, 0, sz, ex.sampleTime, 0); ex.advance() }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10000)
            if (o >= 0) {
                val b = codec.getOutputBuffer(o)!!
                b.position(info.offset); b.limit(info.offset + info.size)
                val sb = b.order(ByteOrder.nativeOrder()).asShortBuffer()
                while (sb.remaining() >= ch && n < data.size) {
                    var s = 0f
                    repeat(ch) { s += sb.get() }
                    data[n++] = s / ch / 32768f
                }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
            } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = codec.outputFormat
                ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            }
        }
        codec.stop(); codec.release(); ex.release()
        require(n > 200) { "file vuoto" }
        sample = data.copyOf(n)
        sampleRate = sr
        return n / sr.toDouble()
    }
}
