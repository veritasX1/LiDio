@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.veritasx1.lidio

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener

/** Listening before loading (card 6c9ba022), LiDio privat: a title from the internet is "lidionetz:<page address>". Only when the
 *  player opens it – on its own loading thread, a little before the title's turn – the loader finds the sound's real address, which
 *  is kept for a while (such addresses hold some hours). */
object NetStream {
    const val scheme = "lidionetz"
    fun wrap(page: String): String = "$scheme:" + Uri.encode(page)
    fun page(uri: Uri): String = Uri.decode(uri.schemeSpecificPart)

    private val known = object : LinkedHashMap<String, Pair<StreamPart, Long>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<StreamPart, Long>>?) = size > 64
    }

    @Synchronized private fun cached(page: String) = known[page]?.takeIf { System.currentTimeMillis() - it.second < 4 * 3600_000L }?.first
    @Synchronized private fun keep(page: String, part: StreamPart) { known[page] = part to System.currentTimeMillis() }
    @Synchronized fun forget(page: String) { known.remove(page) }

    fun resolve(context: Context, page: String): StreamPart =
        cached(page) ?: (Variant.engine(context) ?: throw java.io.IOException("Keine Quelle im Netz")).audio(page).also { keep(page, it) }
}

/** Opens a "lidionetz:" title: finds its address, then reads it like any web address (with the headers the site wants). */
class NetDataSource(private val context: Context) : DataSource {
    private var inner: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()
    override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener }

    override fun open(dataSpec: DataSpec): Long {
        val page = NetStream.page(dataSpec.uri)
        fun attempt(part: StreamPart): Long {
            val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(part.headers)
                .apply { part.headers["User-Agent"]?.let { setUserAgent(it) } }.createDataSource()
            listeners.forEach(http::addTransferListener)
            inner = http
            return http.open(dataSpec.buildUpon().setUri(Uri.parse(part.url)).build())
        }
        return try { attempt(NetStream.resolve(context, page)) }
        catch (e: Exception) {
            // An address that has run out: ask once more.
            NetStream.forget(page)
            attempt(NetStream.resolve(context, page))
        }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int) = inner?.read(buffer, offset, length) ?: -1
    override fun getUri(): Uri? = inner?.uri
    override fun close() { inner?.close(); inner = null }
    override fun getResponseHeaders(): Map<String, List<String>> = inner?.responseHeaders ?: emptyMap()
}
