package io.github.veritasx1.lidio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Card 6e42f66e: the equalizer, balance and analyzer in the audio path. */
class DspTest {
    private val rate = 44100

    /** A stereo sine of `hz` at half scale, through the processor; returns the output's peak per channel. */
    private fun run(hz: Float, seconds: Float = 0.5f): Pair<Int, Int> {
        val processor = WinampProcessor()
        processor.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        val frames = (rate * seconds).toInt()
        val input = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) { val v = (sin(2 * PI * hz * i / rate) * 16000).toInt().toShort(); input.putShort(v); input.putShort(v) }
        input.flip()
        processor.queueInput(input)
        val out = processor.output
        var l = 0; var r = 0
        var i = 0
        while (out.remaining() >= 4) {
            val a = abs(out.getShort().toInt()); val b = abs(out.getShort().toInt())
            if (i > frames / 2) { l = maxOf(l, a); r = maxOf(r, b) }   // after the filters have settled
            i++
        }
        return l to r
    }

    @Test
    fun flatIsUntouched() {
        Dsp.set(false, 0f, List(10) { 0f }, 0f)
        val (l, r) = run(1000f)
        assertTrue("$l", abs(l - 16000) <= 2); assertEquals(l, r)
    }

    @Test
    fun bandBoostsItsFrequencyOnly() {
        Dsp.set(true, 0f, List(10) { if (it == 4) 6f else 0f }, 0f)       // +6 dB at 1 kHz
        val (boosted, _) = run(1000f)
        assertTrue("1 kHz: $boosted", boosted in 30000..32768)          // ×2
        val (other, _) = run(60f, 1f)
        assertTrue("60 Hz: $other", abs(other - 16000) < 1600)
        Dsp.set(true, -6f, List(10) { 0f }, 0f)
        val (quieter, _) = run(1000f)
        assertTrue("Vorverstärkung −6 dB: $quieter", abs(quieter - 8019) < 60)
    }

    @Test
    fun balanceTurnsOneSideDown() {
        Dsp.set(false, 0f, List(10) { 0f }, 0.5f)
        val (l, r) = run(440f)
        assertTrue("$l / $r", abs(l - 8000) < 10 && abs(r - 16000) <= 2)
    }

    @Test
    fun analyzerShowsTheToneInItsBand() {
        Dsp.set(false, 0f, List(10) { 0f }, 0f)
        run(1000f, 1f)
        val a = Analyzer()
        repeat(3) { a.step(playing = true) }
        val top = a.bars.indices.maxBy { a.bars[it] }
        // 1 kHz on a log scale 40 Hz…16 kHz over 19 bars: bar 10.
        assertEquals(10, top)
        assertTrue(a.bars.joinToString(), a.bars[top] > 12f && a.bars[2] < 4f)
        // Thin bands: 75 bars of one pixel; 1 kHz lands on bar 40.
        val thin = Analyzer(75)
        repeat(3) { thin.step(playing = true) }
        assertEquals(40, thin.bars.indices.maxBy { thin.bars[it] })
        // Stopped: everything falls to the floor.
        repeat(120) { a.step(playing = false) }   // two seconds: peaks hold, then sink
        assertTrue(a.peaks.joinToString(), a.quiet)
        assertArrayEquals(FloatArray(76), a.wave, 0f)
    }
}
