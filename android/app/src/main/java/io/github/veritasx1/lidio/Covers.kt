package io.github.veritasx1.lidio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Album art: memory first, then the app's cover folder (kept, so downloaded music has its art offline), then the server. */
object Covers {
    private val memory = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    /** Tests and previews hand in their pictures here instead of a server. */
    val fixed = mutableMapOf<String, Bitmap>()

    fun cached(url: String): Bitmap? = fixed[url] ?: memory.get(url)

    suspend fun load(context: Context, url: String): Bitmap? = withContext(Dispatchers.IO) {
        cached(url)?.let { return@withContext it }
        // Local folders: the cover is already a file of LiDio's own.
        if (url.startsWith("asset:")) return@withContext runCatching { context.assets.open(url.removePrefix("asset:")).use { BitmapFactory.decodeStream(it) } }
            .getOrNull()?.also { memory.put(url, it) }
        if (url.startsWith("file:")) return@withContext BitmapFactory.decodeFile(java.net.URI(url).path)?.also { memory.put(url, it) }
        val folder = File(context.filesDir, "cover").apply { mkdirs() }
        // name = picture (any size) + this size: offline any stored size of the same cover will do.
        val picture = SubsonicServer.md5(cacheKey(url, size = false))
        val file = File(folder, picture + "-" + SubsonicServer.md5(cacheKey(url)) + ".jpg")
        val bitmap = (if (file.exists()) BitmapFactory.decodeFile(file.path) else runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000; connection.readTimeout = 20_000
            try {
                if (connection.responseCode != 200) return@runCatching null
                val bytes = connection.inputStream.use { it.readBytes() }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { file.writeBytes(bytes) }
            } finally { connection.disconnect() }
        }.getOrNull()) ?: folder.listFiles { f -> f.name.startsWith("$picture-") }?.maxByOrNull { it.length() }?.let { BitmapFactory.decodeFile(it.path) }
        bitmap?.also { memory.put(url, it) }
    }

    private val alternatives = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Looks up (once per key) where a missing picture can be found instead. */
    fun alternative(key: String, find: () -> String?): String? = alternatives[key] ?: find()?.also { if (key.isNotEmpty()) alternatives[key] = it }

    /** The login (Subsonic's token and salt, Emby's api_key) changes per session; the picture does not. */
    fun cacheKey(url: String, size: Boolean = true): String =
        url.replace(Regex(if (size) "([?&])(t|s|api_key|ApiKey)=[^&]*" else "([?&])(t|s|api_key|ApiKey|size|maxHeight|maxWidth|quality)=[^&]*"), "$1")

    /** Apple Music tints Now Playing with the artwork's colour: the average of a small copy, a little darker. */
    fun tint(bitmap: Bitmap): Color {
        val small = Bitmap.createScaledBitmap(bitmap, 12, 12, true)
        var r = 0L; var g = 0L; var b = 0L
        for (x in 0 until 12) for (y in 0 until 12) { val p = small.getPixel(x, y); r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255 }
        val n = 144f
        return Color(r / n / 255f * 0.62f, g / n / 255f * 0.62f, b / n / 255f * 0.62f)
    }
}

/** A cover with rounded corners; while loading (or without art) Apple's grey square with a note. */
@Composable
fun Cover(url: String?, modifier: Modifier = Modifier, corner: Dp = 6.dp, onTint: ((Color) -> Unit)? = null,
          fallback: (() -> String?)? = null) {
    val context = LocalContext.current
    val preview = LocalInspectionMode.current
    var image by remember(url) { mutableStateOf<ImageBitmap?>(url?.let { Covers.cached(it) }?.asImageBitmap()) }
    LaunchedEffect(url) {
        if (preview) return@LaunchedEffect
        // No picture of its own (or it fails): a second address, e.g. a title's embedded cover – remembered per album.
        val bitmap = url?.let { Covers.load(context, it) }
            ?: fallback?.let { f -> withContext(kotlinx.coroutines.Dispatchers.IO) { Covers.alternative(url ?: "", f) } }?.let { Covers.load(context, it) }
            ?: return@LaunchedEffect
        image = bitmap.asImageBitmap()
        onTint?.invoke(Covers.tint(bitmap))
    }
    LaunchedEffect(image) { if (image != null && url != null) Covers.cached(url)?.let { onTint?.invoke(Covers.tint(it)) } }
    Box(modifier.clip(RoundedCornerShape(corner))) {
        val shown = image
        if (shown != null) Image(shown, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Placeholder()
    }
}

@Composable
private fun Placeholder() {
    val ink = Ink
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
        if (ink.dark) listOf(Color(0xFF3A3A3C), Color(0xFF2C2C2E)) else listOf(Color(0xFFE5E5EA), Color(0xFFD1D1D6)))),
        contentAlignment = Alignment.Center) {
        SymbolIcon(Symbol.Note, ink.secondary, 28.dp, weight = 2f)
    }
}

/** A cover id that is already a full address (titles from the internet) is used as it is – any screen may show them. */
fun MusicServer.cover(id: String?, size: Int = 300): String? = id?.let { if (it.startsWith("http")) it else coverUrl(it, size) }
fun MusicServer.cover(track: Track, size: Int = 300): String? = track.coverId?.let { if (it.startsWith("http")) it else coverUrl(it, size) } ?: track.artUrl
