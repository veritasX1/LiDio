@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.veritasx1.lidio

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The music video to the playing title (LiDio privat, card d894cc42) – like Apple Music's video: found on the internet, streamed,
 *  the music pauses meanwhile. Black, the picture as large as it goes, simple controls; a tap shows or hides them. */
@Composable
fun VideoScreen(state: AppState, track: Track, onClose: () -> Unit) {
    val context = LocalContext.current
    val engine = remember { Variant.engine(context) } ?: return
    var status by remember { mutableStateOf<String?>("Sucht das Video …") }
    var title by remember { mutableStateOf("") }
    var ratio by remember { mutableFloatStateOf(16f / 9f) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableFloatStateOf(0f) }
    var controls by remember { mutableStateOf(true) }
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) {
        val wasPlaying = state.playback.playing
        if (wasPlaying) state.playback.toggle()
        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) { if (size.height > 0) ratio = size.width * size.pixelWidthHeightRatio / size.height }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { status = "Das Video ließ sich nicht abspielen." }
        })
        onDispose { player.release() }
    }
    LaunchedEffect(track.id) {
        try {
            val (hit, parts) = withContext(Dispatchers.IO) {
                val hit = engine.musicVideo(track.title, track.artist) ?: throw ServerError("Kein Video zu diesem Titel gefunden.")
                status = "Lädt „${hit.title}“ …"
                hit to engine.stream(hit)
            }
            title = hit.title
            val sources = parts.map { p ->
                val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(p.headers)
                p.headers["User-Agent"]?.let { http.setUserAgent(it) }
                ProgressiveMediaSource.Factory(http).createMediaSource(MediaItem.fromUri(p.url))
            }
            player.setMediaSource(if (sources.size == 1) sources[0] else MergingMediaSource(*sources.toTypedArray()))
            player.prepare(); player.play()
            status = null
        } catch (e: ServerError) { status = e.message } catch (e: Exception) { status = "Das Video ließ sich nicht laden." }
    }
    LaunchedEffect(playing) { while (playing) { position = if (player.duration > 0) player.currentPosition.toFloat() / player.duration else 0f; delay(500) } }
    LaunchedEffect(controls, playing) { if (controls && playing) { delay(3500); controls = false } }

    // Like Apple's video player: tap shows/hides the controls, a double tap on the left or right half jumps 10 s.
    fun skip(ms: Long) { player.seekTo((player.currentPosition + ms).coerceIn(0, player.duration.coerceAtLeast(0))); controls = true }
    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
        detectTapGestures(onTap = { controls = !controls }, onDoubleTap = { o -> skip(if (o.x < size.width / 2) -10_000 else 10_000) })
    }) {
        AndroidView({ SurfaceView(it).also { v -> player.setVideoSurfaceView(v) } },
            Modifier.align(Alignment.Center).fillMaxWidth().aspectRatio(ratio.coerceIn(0.4f, 3f)))
        status?.let { Label(it, 15f, 500, Color.White, Modifier.align(Alignment.Center).padding(32.dp), lines = 3, align = androidx.compose.ui.text.style.TextAlign.Center) }
        androidx.compose.animation.AnimatedVisibility(controls || status != null, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)).clickable(role = Role.Button, onClick = onClose)
                    .semantics { contentDescription = "Video schließen" }, contentAlignment = Alignment.Center) { SymbolIcon(Symbol.Close, Color.White, 16.dp, weight = 2.6f) }
                if (status == null) {
                    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                        SkipButton(forward = false) { skip(-10_000) }
                        Box(Modifier.size(72.dp).clip(CircleShape).clickable(role = Role.Button) { if (player.isPlaying) player.pause() else player.play(); controls = true }
                            .semantics { contentDescription = if (playing) "Pause" else "Wiedergabe" }, contentAlignment = Alignment.Center) {
                            SymbolIcon(if (playing) Symbol.Pause else Symbol.Play, Color.White, 44.dp, filled = true)
                        }
                        SkipButton(forward = true) { skip(10_000) }
                    }
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                        Label(track.title, 17f, 600, Color.White)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)) {
                            SourceBadge(WebSource.Video)
                            if (title.isNotEmpty()) Label(title, 13f, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(start = 6.dp))
                        }
                        IosSlider(position, { position = it; controls = true }, "Position im Video", fill = Color.White, track = Color.White.copy(alpha = 0.3f), thumb = false,
                            onRelease = { if (player.duration > 0) player.seekTo((position * player.duration).toLong()) })
                        val total = (player.duration.coerceAtLeast(0) / 1000).toInt()
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            Label(duration((position * total).toInt()), 12f, 500, Color.White.copy(alpha = 0.7f), tabular = true)
                            Spacer(Modifier.weight(1f))
                            Label("-" + duration((total - position * total).toInt().coerceAtLeast(0)), 12f, 500, Color.White.copy(alpha = 0.7f), tabular = true)
                        }
                    }
                }
            }
        }
    }
}

/** "10 s back/forward" like SF Symbols' gobackward.10 / goforward.10: an open circle with an arrow head and the 10 inside. */
@Composable
private fun SkipButton(forward: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(52.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = if (forward) "10 Sekunden vor" else "10 Sekunden zurück" }, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(40.dp)) {
            val w = 2.4.dp.toPx(); val r = size.minDimension / 2 - w
            val c = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            val start = if (forward) -60f else -120f
            drawArc(Color.White, if (forward) -60f else -120f + 0f, if (forward) -300f + 0f else 300f, false,
                androidx.compose.ui.geometry.Offset(c.x - r, c.y - r), androidx.compose.ui.geometry.Size(r * 2, r * 2), style = stroke)
            // Arrow head at the gap, pointing along the circle.
            val a = Math.toRadians(start.toDouble()); val tip = androidx.compose.ui.geometry.Offset(c.x + r * Math.cos(a).toFloat(), c.y + r * Math.sin(a).toFloat())
            val d = 6.dp.toPx(); val dir = if (forward) 1f else -1f
            drawLine(Color.White, tip, androidx.compose.ui.geometry.Offset(tip.x - dir * d, tip.y - d * 0.9f), w, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(Color.White, tip, androidx.compose.ui.geometry.Offset(tip.x - dir * d * 0.2f, tip.y + d), w, androidx.compose.ui.graphics.StrokeCap.Round)
        }
        Label("10", 12f, 700, Color.White)
    }
}
