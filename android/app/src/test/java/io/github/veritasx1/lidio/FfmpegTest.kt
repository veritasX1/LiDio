package io.github.veritasx1.lidio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Card b697496c: the WAV wrapper around LiDio's FFmpeg (the native part needs a phone). */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class FfmpegTest {
    @Test
    fun wavHeader() {
        val h = ByteBuffer.wrap(Wav.header(44100, 2, 44100L * 10)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(h.array(), 0, 4)); assertEquals("WAVE", String(h.array(), 8, 4)); assertEquals("data", String(h.array(), 36, 4))
        assertEquals(36 + 1_764_000, h.getInt(4))
        assertEquals(1.toShort(), h.getShort(20)); assertEquals(2.toShort(), h.getShort(22))
        assertEquals(44100, h.getInt(24)); assertEquals(176400, h.getInt(28)); assertEquals(4.toShort(), h.getShort(32)); assertEquals(16.toShort(), h.getShort(34))
        assertEquals(1_764_000, h.getInt(40))
    }

    @Test
    fun bytePositionToSample() {
        assertEquals(0L, Wav.sampleAt(0, 2)); assertEquals(0L, Wav.sampleAt(44, 2))
        assertEquals(44100L, Wav.sampleAt(44 + 44100L * 4, 2))     // one second of stereo
        assertEquals(1000L, Wav.sampleAt(44 + 2000, 1))
    }

    @Test
    fun wrappedAddresses() {
        val local = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FA%2F01%20WMA.wma"
        assertEquals(local, Ffmpeg.unwrap(android.net.Uri.parse(Ffmpeg.wrap(local))).toString())
    }
}
