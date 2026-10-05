package io.github.veritasx1.lidio

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * Card 6e42f66e: Winamp's equalizer, balance and spectrum – in the player's own audio path, so the analyzer
 * needs no microphone permission (Android's Visualizer would) and the EQ works for every format and server.
 */

/** What the sound is set to, and what the analyzer last heard. Shared between the player and the screen (same process). */
object Dsp {
    val FREQUENCIES = floatArrayOf(60f, 170f, 310f, 600f, 1000f, 3000f, 6000f, 12000f, 14000f, 16000f)

    @Volatile var eqOn = false
    @Volatile var preamp = 0f
    @Volatile var bands = FloatArray(10)
    @Volatile var balance = 0f
    /** Bumped on every change, so the processor recomputes its filters once. */
    @Volatile var version = 0

    fun set(eqOn: Boolean, preamp: Float, bands: List<Float>, balance: Float) {
        this.eqOn = eqOn; this.preamp = preamp; this.bands = bands.toFloatArray(); this.balance = balance; version++
    }

    /** The player may start before any screen: it reads the stored settings itself. */
    fun load(context: Context) {
        val p = context.getSharedPreferences("winamp", Context.MODE_PRIVATE)
        set(p.getBoolean("eqAn", false), p.getFloat("pre", 0f), (0 until 10).map { p.getFloat("b$it", 0f) }, p.getFloat("balance", 0f))
    }

    // --- what was heard: a mono ring, written by the audio thread, read by the screen ---
    private const val RING = 1 shl 15
    private val ring = FloatArray(RING)
    @Volatile private var written = 0L
    @Volatile var sampleRate = 44100
        private set

    internal fun hear(sample: Float) { ring[(written and (RING - 1).toLong()).toInt()] = sample; written++ }
    internal fun rate(r: Int) { sampleRate = r }

    /** The audio is processed ahead of what's heard by about the sink's buffer (Media3: 250 ms for PCM). */
    private const val AHEAD_SECONDS = 0.25

    /** The last `n` samples that are being heard now (oldest first). */
    fun recent(n: Int, out: FloatArray = FloatArray(n)): FloatArray {
        val end = written - (AHEAD_SECONDS * sampleRate).toLong()
        for (i in 0 until n) {
            val at = end - n + i
            out[i] = if (at < 0 || written - at > RING) 0f else ring[(at and (RING - 1).toLong()).toInt()]
        }
        return out
    }
}

/** RBJ peaking filters (one per band and channel), preamp, balance – on 16-bit PCM, as Media3 hands it to processors. */
class WinampProcessor : BaseAudioProcessor() {
    private var channels = 2
    private var rate = 44100
    private var version = -1
    private var gain = 1f
    private var left = 1f; private var right = 1f
    private var active = 0
    // Coefficients per band: b0 b1 b2 a1 a2 (normalised); state per band and channel: x1 x2 y1 y2.
    private val coef = Array(10) { FloatArray(5) }
    private var state = Array(10) { Array(2) { FloatArray(4) } }

    override fun onConfigure(format: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (format.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(format)
        channels = format.channelCount; rate = format.sampleRate; version = -1
        Dsp.rate(rate)
        return format
    }

    override fun onFlush() { state = Array(10) { Array(max(2, channels)) { FloatArray(4) } } }

    private fun prepare() {
        version = Dsp.version
        val bands = Dsp.bands
        active = 0
        if (Dsp.eqOn) for (i in 0 until 10) {
            val f = Dsp.FREQUENCIES[i]
            if (f >= rate * 0.45f || bands[i] == 0f) { coef[i][0] = Float.NaN; continue }
            // Peaking EQ, Q 1.4 – Winamp's bands sit close at the top (12k, 14k, 16k).
            val a = 10.0.pow(bands[i] / 40.0); val w = 2 * PI * f / rate; val alpha = sin(w) / (2 * 1.4)
            val a0 = 1 + alpha / a
            coef[i][0] = ((1 + alpha * a) / a0).toFloat(); coef[i][1] = (-2 * cos(w) / a0).toFloat(); coef[i][2] = ((1 - alpha * a) / a0).toFloat()
            coef[i][3] = (-2 * cos(w) / a0).toFloat(); coef[i][4] = ((1 - alpha / a) / a0).toFloat()
            active++
        } else for (i in 0 until 10) coef[i][0] = Float.NaN
        gain = if (Dsp.eqOn) 10f.pow(Dsp.preamp / 20f) else 1f
        val b = Dsp.balance
        left = min(1f, 1f - b); right = min(1f, 1f + b)
        if (state[0].size < channels) state = Array(10) { Array(max(2, channels)) { FloatArray(4) } }
    }

    override fun queueInput(input: ByteBuffer) {
        if (version != Dsp.version) prepare()
        val size = input.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val src = input.order(ByteOrder.nativeOrder())
        val frames = size / 2 / channels
        val touch = active > 0 || gain != 1f || left != 1f || right != 1f
        for (frame in 0 until frames) {
            var mono = 0f
            for (ch in 0 until channels) {
                var x = src.getShort().toFloat() / 32768f
                if (touch) {
                    if (ch < 2) for (i in 0 until 10) {
                        val c = coef[i]; if (c[0].isNaN()) continue
                        val s = state[i][ch]
                        val y = c[0] * x + c[1] * s[0] + c[2] * s[1] - c[3] * s[2] - c[4] * s[3]
                        s[1] = s[0]; s[0] = x; s[3] = s[2]; s[2] = y; x = y
                    }
                    x *= gain * (if (channels >= 2 && ch == 0) left else if (channels >= 2 && ch == 1) right else 1f)
                }
                mono += x
                out.putShort((x.coerceIn(-1f, 0.99997f) * 32768f).toInt().toShort())
            }
            Dsp.hear(mono / channels)
        }
        out.flip()
    }
}

/** Winamp's analyzer over 76 × 16 – 19 thick or 75 thin bars, with falling peaks – and its oscilloscope. */
class Analyzer(private val count: Int = 19) {
    private val n = 2048   // 21.5 Hz per bin at 44.1 kHz: enough for 75 thin bars down low
    private val window = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / (n - 1))).toFloat() }
    private val re = FloatArray(n); private val im = FloatArray(n)
    private val samples = FloatArray(n)
    val bars = FloatArray(count)       // 0…16 pixels
    val peaks = FloatArray(count)
    private val peakHold = IntArray(count)
    val wave = FloatArray(76)          // −1…1

    /** One frame: `playing` false lets everything fall to the floor, as when Winamp stops. */
    fun step(playing: Boolean) {
        val target = FloatArray(count)
        if (playing) {
            Dsp.recent(n, samples)
            for (i in 0 until n) { re[i] = samples[i] * window[i]; im[i] = 0f }
            fft(re, im)
            val rate = Dsp.sampleRate.toFloat()
            // Bands spaced logarithmically from 40 Hz to 16 kHz, as Winamp's analyzer looks.
            for (b in 0 until count) {
                val lo = 40.0 * (16000.0 / 40.0).pow(b.toDouble() / count); val hi = 40.0 * (16000.0 / 40.0).pow((b + 1.0) / count)
                val k0 = max(1, (lo / rate * n).toInt()); val k1 = max(k0 + 1, min(n / 2, (hi / rate * n).toInt()))
                var m = 0f
                for (k in k0 until k1) m = max(m, hypot(re[k], im[k]))
                val db = 20 * ln(max(m, 1e-6f)) / ln(10f)          // full-scale sine ≈ +48 dB here
                target[b] = ((db + 12f) / 54f * 16f).coerceIn(0f, 16f)
            }
            val step = n / 76
            for (i in 0 until 76) wave[i] = samples[n - 76 * step + i * step].coerceIn(-1f, 1f)
        } else wave.fill(0f)
        for (b in 0 until count) {
            // Bars jump up and fall back gently; peaks hold, then drop.
            bars[b] = if (target[b] > bars[b]) target[b] else max(target[b], bars[b] - 0.9f)
            if (bars[b] >= peaks[b]) { peaks[b] = bars[b]; peakHold[b] = 18 }
            else if (peakHold[b] > 0) peakHold[b]--
            else peaks[b] = max(0f, peaks[b] - 0.35f)
        }
    }

    val quiet get() = bars.all { it < 0.05f } && peaks.all { it < 0.05f }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
