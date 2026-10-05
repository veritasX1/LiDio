@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.veritasx1.lidio

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** LiDio's native part (native/, built by native/build-native.sh): liblidionative.so with FFmpeg and Sonivox. */
object Native {
    val loaded: Boolean = runCatching { System.loadLibrary("lidionative") }.isSuccess
}

/** A native decoder that hands out 16-bit PCM: opened on a file descriptor, read in order, sought by sample. */
interface PcmDecoder {
    val scheme: String
    fun open(fd: Int): Long
    fun sampleRate(handle: Long): Int
    fun channels(handle: Long): Int
    fun durationUs(handle: Long): Long
    fun read(handle: Long, buffer: ByteArray, offset: Int, length: Int): Int
    fun seek(handle: Long, sample: Long): Boolean
    fun close(handle: Long)

    fun wrap(uri: String) = "$scheme:" + Uri.encode(uri)
    fun unwrap(uri: Uri): Uri = Uri.parse(Uri.decode(uri.encodedSchemeSpecificPart))   // decoded once – the inner address keeps its own %-codes
}

/** LiDio's own small FFmpeg: formats Android can't open (WMA, APE, WavPack, DSD, Musepack, TAK, TTA …). */
object Ffmpeg : PcmDecoder {
    val available: Boolean get() = Native.loaded
    override val scheme = "lidio-ffmpeg"
    const val SCHEME = "lidio-ffmpeg"

    external override fun open(fd: Int): Long
    external override fun sampleRate(handle: Long): Int
    external override fun channels(handle: Long): Int
    external override fun durationUs(handle: Long): Long
    external fun tags(handle: Long): Array<String?>
    external fun cover(handle: Long): ByteArray?
    external override fun read(handle: Long, buffer: ByteArray, offset: Int, length: Int): Int
    external override fun seek(handle: Long, sample: Long): Boolean
    external override fun close(handle: Long)

    /** Tags through FFmpeg, for files Android's reader doesn't understand (or leaves gaps in, like the year). */
    fun tags(context: Context, uri: Uri): Tags? {
        if (!available) return null
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val h = open(pfd.fd)
                if (h == 0L) return@use null
                try {
                    val t = tags(h)
                    fun n(i: Int) = t[i]?.substringBefore('/')?.trim()?.toIntOrNull()
                    Tags(t[0], t[1], t[2], t[3], n(4), n(5), t[6]?.take(4)?.toIntOrNull(), t[7], (durationUs(h) / 1_000_000).toInt(), cover(h))
                } finally { close(h) }
            }
        }.getOrNull()
    }
}

/** MIDI through Sonivox – Android's own General-MIDI synthesizer – as 44.1 kHz stereo. */
object Midi : PcmDecoder {
    val available: Boolean get() = Native.loaded
    override val scheme = "lidio-midi"
    val SUFFIXES = setOf("mid", "midi", "kar", "rmi", "smf")

    external override fun open(fd: Int): Long
    external override fun sampleRate(handle: Long): Int
    external override fun channels(handle: Long): Int
    external override fun durationUs(handle: Long): Long
    external override fun read(handle: Long, buffer: ByteArray, offset: Int, length: Int): Int
    external override fun seek(handle: Long, sample: Long): Boolean
    external override fun close(handle: Long)

    /** MIDI files rarely carry tags – the length is what matters for the list. */
    fun seconds(context: Context, uri: Uri): Int = if (!available) 0 else runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val h = open(pfd.fd); if (h == 0L) 0 else try { (durationUs(h) / 1_000_000).toInt() } finally { close(h) }
        } ?: 0
    }.getOrDefault(0)
}

/** A 44-byte WAV header for 16-bit PCM. */
object Wav {
    const val HEADER = 44
    fun header(rate: Int, channels: Int, samples: Long): ByteArray {
        val block = channels * 2
        val data = (samples * block).coerceAtMost(0xFFFFFFFFL - 36)
        return ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((36 + data).toInt()); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(channels.toShort()); putInt(rate); putInt(rate * block)
            putShort(block.toShort()); putShort(16)
            put("data".toByteArray()); putInt(data.toInt())
        }.array()
    }

    /** The output sample a byte position of the WAV stream stands for. */
    fun sampleAt(position: Long, channels: Int) = ((position - HEADER).coerceAtLeast(0)) / (channels * 2)
}

/** Plays a file through one of LiDio's decoders: the player sees a WAV stream; a jump to a byte position becomes a jump to
 *  the exact sample. */
class NativeDataSource(private val context: Context, private val decoder: PcmDecoder) : BaseDataSource(false) {
    private var handle = 0L
    private var pfd: android.os.ParcelFileDescriptor? = null
    private var header = ByteArray(0)
    private var position = 0L
    private var remaining = 0L
    private var uri: Uri? = null
    private var channels = 2

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val file = decoder.unwrap(dataSpec.uri)
        pfd = context.contentResolver.openFileDescriptor(file, "r") ?: throw IOException("Datei nicht lesbar")
        handle = decoder.open(pfd!!.fd)
        if (handle == 0L) throw IOException("Die Datei lässt sich nicht öffnen")
        val rate = decoder.sampleRate(handle); channels = decoder.channels(handle)
        val samples = decoder.durationUs(handle) * rate / 1_000_000
        header = Wav.header(rate, channels, samples)
        val total = Wav.HEADER + samples * channels * 2
        position = dataSpec.position
        if (position > Wav.HEADER) decoder.seek(handle, Wav.sampleAt(position, channels))
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else (total - position).coerceAtLeast(0)
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        var n: Int
        if (position < Wav.HEADER) {
            n = minOf(length, (Wav.HEADER - position).toInt())
            System.arraycopy(header, position.toInt(), buffer, offset, n)
        } else {
            n = decoder.read(handle, buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (n <= 0) return C.RESULT_END_OF_INPUT
        }
        position += n; remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (handle != 0L) { decoder.close(handle); handle = 0L }
        pfd?.close(); pfd = null
        if (uri != null) { uri = null; transferEnded() }
    }
}

/** Files on the phone go straight to their source – FFmpeg / Sonivox for lidio-ffmpeg: / lidio-midi:, Android for content: and file: – and never through
 *  the caches (copying what is already on the phone would only fill "Zuletzt gehört"); http(s) goes through the caches. */
class RoutingDataSource(private val context: Context, private val cached: DataSource, private val direct: DataSource) : DataSource {
    private var current: DataSource = cached
    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
        cached.addTransferListener(transferListener); direct.addTransferListener(transferListener)
    }
    override fun open(dataSpec: DataSpec): Long {
        current = when (dataSpec.uri.scheme) {
            Ffmpeg.scheme -> NativeDataSource(context, Ffmpeg)
            Midi.scheme -> NativeDataSource(context, Midi)
            NetStream.scheme -> NetDataSource(context)
            "content", "file" -> direct
            else -> cached
        }
        return current.open(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int) = current.read(buffer, offset, length)
    override fun getUri(): Uri? = current.uri
    override fun close() = current.close()
    override fun getResponseHeaders(): Map<String, List<String>> = current.responseHeaders
}
