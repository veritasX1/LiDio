package io.github.veritasx1.lidio

import io.github.veritasx1.lidio.i18n.tr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Card 5d1ab4c8 (Olaf 06.10.2026): an own playlist cover from the photos – "darf … nicht hässlich links und rechts Balken bei
 *  falschem Seitenverhältnis haben. Ich muss als Nutzer den Ausschnitt wählen können". The photo fills a square frame always
 *  (it can't be made smaller than the frame, so there are never bars); dragging moves it, two fingers zoom. "Übernehmen" cuts
 *  exactly that square out at 1000 × 1000 and puts it on the server as the playlist's own cover (it wins over every automatic one). */
@Composable
fun CoverCropScreen(state: AppState, server: MusicServer, route: Route.CoverCrop) {
    val ink = Ink
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var asked by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) { if (bitmap == null) state.back(); return@rememberLauncherForActivityResult }
        scope.launch { bitmap = withContext(Dispatchers.IO) { decode(context, uri) } ?: run { state.notice = tr("Das Foto ließ sich nicht öffnen."); null } }
    }
    LaunchedEffect(Unit) { if (!asked) { asked = true; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } }
    // The photo's place in the frame: scale (≥ the size that fills the square) and offset of its top-left corner.
    var frame by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val bmp = bitmap
    fun minScale(): Float = if (bmp == null || frame.width == 0) 1f else maxOf(frame.width.toFloat() / bmp.width, frame.height.toFloat() / bmp.height)
    fun clamp(s: Float, o: Offset): Pair<Float, Offset> {
        if (bmp == null) return s to o
        val sc = s.coerceIn(minScale(), minScale() * 6f)
        val w = bmp.width * sc; val h = bmp.height * sc
        return sc to Offset(o.x.coerceIn(frame.width - w, 0f), o.y.coerceIn(frame.height - h, 0f))
    }
    LaunchedEffect(bmp, frame) {
        // Start centred, just filling the square.
        if (bmp != null && frame.width > 0) {
            val s = minScale(); scale = s
            offset = Offset((frame.width - bmp.width * s) / 2f, (frame.height - bmp.height * s) / 2f)
        }
    }
    Column(Modifier.fillMaxSize()) {
        NavBar(state, tr("Cover"))
        Label(route.name, 22f, 700, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), lines = 2)
        Label(tr("Ziehen verschiebt, zwei Finger zoomen – das Quadrat wird das Cover."), 15f, color = ink.secondary, lines = 2,
            modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(12.dp))
        Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Color.Black)
            .border(1.dp, ink.separator, RoundedCornerShape(12.dp))
            .onSizeChanged { frame = it }
            .pointerInput(bmp) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    // Zoom about the fingers, then move – always kept so the photo covers the whole square.
                    val s = scale * zoom
                    val o = (offset - centroid) * zoom + centroid + pan
                    val (cs, co) = clamp(s, o); scale = cs; offset = co
                }
            }) {
            if (bmp != null) {
                val image = remember(bmp) { bmp.asImageBitmap() }
                Canvas(Modifier.fillMaxSize()) {
                    drawImage(image, srcOffset = IntOffset.Zero, srcSize = IntSize(bmp.width, bmp.height),
                        dstOffset = IntOffset(offset.x.toInt(), offset.y.toInt()),
                        dstSize = IntSize((bmp.width * scale).toInt(), (bmp.height * scale).toInt()))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        val ready = bmp != null && !busy
        Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(50.dp).clip(RoundedCornerShape(12.dp))
            .background(if (ready) ink.tint else ink.fill).clickable(enabled = ready, role = Role.Button) {
                val b = bmp ?: return@clickable
                busy = true
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching { server.setPlaylistCover(route.playlistId, cut(b, frame, scale, offset), own = true) }.getOrDefault(false)
                    }
                    busy = false
                    if (ok) { state.notice = tr("Cover von „{name}“ gesetzt.", "name" to route.name); state.generation++; state.back() }
                    else state.notice = tr("Das Cover ließ sich nicht setzen.")
                }
            }, contentAlignment = Alignment.Center) {
            Label(if (busy) tr("Wird gesetzt …") else tr("Übernehmen"), 17f, 600, if (ready) Color.White else ink.tertiary)
        }
        Label(tr("Anderes Foto …"), 17f, color = ink.tint, modifier = Modifier.padding(16.dp).clickable(role = Role.Button) {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
    }
}

/** The photo, rotated upright (EXIF) and at most ~2400 px on its long side – plenty for a 1000 px cover. */
private fun decode(context: android.content.Context, uri: Uri): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2400) sample *= 2
    val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        ?: return null
    val rotation = context.contentResolver.openInputStream(uri)?.use {
        when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } ?: 0f
    if (rotation == 0f) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, android.graphics.Matrix().apply { postRotate(rotation) }, true)
}.getOrNull()

/** Exactly the square the user sees, as a 1000 × 1000 JPEG. */
private fun cut(bitmap: Bitmap, frame: IntSize, scale: Float, offset: Offset): ByteArray {
    val left = (-offset.x / scale).coerceAtLeast(0f); val top = (-offset.y / scale).coerceAtLeast(0f)
    val side = (frame.width / scale).coerceAtMost(minOf(bitmap.width - left, bitmap.height - top))
    val out = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(out).drawBitmap(bitmap, android.graphics.Rect(left.toInt(), top.toInt(), (left + side).toInt(), (top + side).toInt()),
        android.graphics.Rect(0, 0, 1000, 1000), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
    return java.io.ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
}
