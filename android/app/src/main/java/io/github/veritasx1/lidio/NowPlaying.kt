package io.github.veritasx1.lidio

import io.github.veritasx1.lidio.i18n.tr

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import kotlinx.coroutines.launch

/** Now Playing as on the iPhone: the artwork's colour behind everything, big art that shrinks when paused, the
 *  scrubber, the three big buttons, volume; the queue ("Als Nächstes") with shuffle and repeat behind the list button.
 *  Always light-on-dark, like Apple's. Swipe down (or the grabber) closes it. */
@Composable
fun NowPlaying(state: AppState, server: MusicServer, onClose: () -> Unit) {
    val playback = state.playback
    val track = playback.current
    var tint by remember { mutableStateOf(Color(0xFF3A3A3C)) }
    var showQueue by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val white = Color.White
    val soft = Color.White.copy(alpha = 0.55f)

    var video by remember { mutableStateOf(false) }
    // Lyrics (card 7ac89c11): looked for in the background as soon as a title plays; nothing found = nothing shown.
    var showLyrics by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var lyrics by remember(track?.id) { mutableStateOf<List<LyricLine>?>(null) }
    LaunchedEffect(track?.id) {
        val t = track ?: return@LaunchedEffect
        val from = when {
            t.path?.contains("/Aus dem Netz/") == true -> null
            t.path?.startsWith("https://") == true -> null
            else -> server
        }
        lyrics = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { Lyrics.load(context, from, t, state.playback.settings.lyricsOnline) }.getOrNull()
        }
        if (lyrics == null) showLyrics = false
    }
    // "Modern" (card d894cc42): behind everything the cover itself, huge and soft – like iOS 26.
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    if (state.modern && track != null) {
        Cover(server.cover(track, 300), Modifier.fillMaxSize().graphicsLayer { scaleX = 1.8f; scaleY = 1.8f }.blur(60.dp), 0.dp)
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.6f)))))
    }
    Column(Modifier.fillMaxSize().background(if (state.modern) Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)) else Brush.verticalGradient(listOf(tint, Color(0xFF111111))))
        .pointerInput(Unit) {
            detectVerticalDragGestures(onDragEnd = { if (drag > 220f) onClose(); drag = 0f }) { _, dy -> drag = (drag + dy).coerceAtLeast(0f) }
        }
        .windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 28.dp)) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp).clickable(onClickLabel = tr("Schließen"), onClick = onClose), contentAlignment = Alignment.Center) {
            Box(Modifier.size(36.dp, 5.dp).clip(CircleShape).background(soft))
        }
        if (track == null) { Spacer(Modifier.weight(1f)); return@Column }

        if (showLyrics && lyrics != null) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(server.cover(track, 200), Modifier.size(64.dp), 6.dp, onTint = { tint = it })
                Column(Modifier.weight(1f).padding(start = 12.dp)) { Label(track.title, 17f, 600, white); Label(track.artist, 15f, color = soft) }
            }
            LyricsView(lyrics!!, playback.position, Modifier.weight(1f)) { ms -> playback.seek(ms); if (!playback.playing) playback.toggle() }
        } else if (showQueue) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(server.cover(track, 200), Modifier.size(64.dp), 6.dp, onTint = { tint = it })
                Column(Modifier.weight(1f).padding(start = 12.dp)) { Label(track.title, 17f, 600, white); Label(track.artist, 15f, color = soft) }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Toggle(Symbol.Shuffle, tr("Zufall"), playback.shuffle, Modifier.weight(1f)) { playback.toggleShuffle() }
                Toggle(if (playback.repeat == Player.REPEAT_MODE_ONE) Symbol.RepeatOne else Symbol.Repeat, tr("Wiederholen"),
                    playback.repeat != Player.REPEAT_MODE_OFF, Modifier.weight(1f)) { playback.cycleRepeat() }
                // iOS: the third button – Autoplay ∞ goes on with similar music when the queue ends.
                Toggle(Symbol.Infinity, tr("Autoplay"), playback.autoplay, Modifier.weight(1f)) { playback.toggleAutoplay() }
            }
            QueueView(playback, server, Modifier.weight(1f))
        } else {
            // The art: full width while playing, a step smaller when paused (Apple's spring).
            val inset: Dp by animateDpAsState(if (playback.playing) 0.dp else 28.dp, spring(dampingRatio = 0.6f), label = "Cover")
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                // The spring overshoots below zero on the way back – padding must never be negative.
                Cover(server.cover(track, 900), Modifier.padding(inset.coerceAtLeast(0.dp)).fillMaxWidth().aspectRatio(1f).shadow(if (playback.playing) 24.dp else 10.dp, RoundedCornerShape(if (state.modern) 14.dp else 10.dp)),
                    if (state.modern) 14.dp else 10.dp, onTint = { tint = it })
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Label(track.title, 22f, 700, white); Label(track.artist, 20f, 400, soft) }
                // Like Music on iOS: "•••" opens the title's menu (share, go to the album, the music video).
                var more by remember { mutableStateOf(false) }
                // ★ like iOS: the favourite, kept on the server.
                val fav = playback.isFavorite(track)
                Box(Modifier.padding(end = 10.dp).size(32.dp).clip(CircleShape).background(white.copy(alpha = 0.15f))
                    .clickable(role = Role.Button) { playback.toggleFavorite(track) }
                    .semantics { contentDescription = if (fav) tr("Aus Favoriten entfernen") else "Favorit" }, contentAlignment = Alignment.Center) {
                    SymbolIcon(Symbol.Star, white, 16.dp, filled = fav)
                }
                Box {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(white.copy(alpha = 0.15f))
                        .clickable(role = Role.Button, onClickLabel = tr("Mehr")) { more = true }
                        .semantics { contentDescription = tr("Mehr") }, contentAlignment = Alignment.Center) { SymbolIcon(Symbol.Ellipsis, white, 18.dp) }
                    if (more) NowPlayingMenu(state, server, track, onVideo = { video = true }, onClose = onClose) { more = false }
                }
            }
        }

        // Scrubber with times – live while dragging, the jump happens on release.
        val length = playback.length.coerceAtLeast(1)
        val fraction = scrubbing ?: (playback.position.toFloat() / length).coerceIn(0f, 1f)
        IosSlider(fraction, { scrubbing = it }, tr("Position im Titel"), Modifier.padding(top = 18.dp), fill = white.copy(alpha = 0.9f),
            track = white.copy(alpha = 0.25f), thumb = false, onRelease = { scrubbing?.let { playback.seek((it * length).toLong()) }; scrubbing = null })
        Row(Modifier.fillMaxWidth()) {
            Label(duration(((fraction * length) / 1000).toInt()), 12f, 500, soft, tabular = true)
            Spacer(Modifier.weight(1f))
            Label("-" + duration(((length - fraction * length) / 1000).toInt().coerceAtLeast(0)), 12f, 500, soft, tabular = true)
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Big(Symbol.Backward, tr("Zurück"), 40.dp) { playback.previous() }
            Big(if (playback.playing) Symbol.Pause else Symbol.Play, if (playback.playing) tr("Pause") else tr("Wiedergabe"), 52.dp) { playback.toggle() }
            Big(Symbol.Forward, tr("Weiter"), 40.dp) { playback.next() }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            SymbolIcon(Symbol.SpeakerLow, soft, 16.dp)
            IosSlider(playback.volume, { playback.changeVolume(it) }, tr("Lautstärke"), Modifier.weight(1f).padding(horizontal = 10.dp),
                fill = white.copy(alpha = 0.9f), track = white.copy(alpha = 0.25f), thumb = false)
            SymbolIcon(Symbol.SpeakerHigh, soft, 18.dp)
        }
        // Three places like iOS's bottom row (lyrics · AirPlay · queue): lyrics · Winamp · queue. The music video is in "•••".
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            // No text found: the button stays dim and does nothing – the listener is never disturbed (card 7ac89c11).
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(if (showLyrics) white.copy(alpha = 0.25f) else Color.Transparent)
                .clickable(role = Role.Button, enabled = lyrics != null, onClickLabel = tr("Liedtext")) { showLyrics = !showLyrics; showQueue = false }
                .semantics { contentDescription = if (lyrics != null) tr("Liedtext") else tr("Kein Liedtext") }, contentAlignment = Alignment.Center) {
                SymbolIcon(Symbol.Quote, if (showLyrics) white else if (lyrics != null) soft else white.copy(alpha = 0.2f), 22.dp)
            }
            // The Winamp view – a gimmick, one tap away; LiDio remembers which view was last.
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClickLabel = "Winamp-Ansicht") { state.winamp.on = true; state.winamp.save() }
                .semantics { contentDescription = "Winamp-Ansicht" }, contentAlignment = Alignment.Center) { SymbolIcon(Symbol.Bolt, soft, 22.dp) }
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(if (showQueue) white.copy(alpha = 0.25f) else Color.Transparent)
                .clickable(role = Role.Button, onClickLabel = tr("Als Nächstes")) { showQueue = !showQueue; showLyrics = false }.semantics { contentDescription = tr("Als Nächstes") },
                contentAlignment = Alignment.Center) { SymbolIcon(Symbol.Queue, if (showQueue) white else soft, 22.dp) }
        }
    }
    // Card 5ef5e3c8: short hints the first time(s), per help level.
    var hint by remember { mutableStateOf(if (Help.hint(context, "jetztLaeuft")) tr("Nach unten wischen schließt „Jetzt läuft“.") else null) }
    LaunchedEffect(showQueue) { if (showQueue && Help.hint(context, "warteschlange")) hint = tr("Am Griff ≡ ziehen sortiert um, nach links wischen entfernt.") }
    LaunchedEffect(hint) { if (hint != null) { kotlinx.coroutines.delay(4000); hint = null } }
    hint?.let { HintPill(it, Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 24.dp)) }
    if (video && track != null) VideoScreen(state, track) { video = false }
    }
}

@Composable
private fun Big(symbol: Symbol, label: String, size: Dp, onClick: () -> Unit) {
    Box(Modifier.size(size + 24.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) { SymbolIcon(symbol, Color.White, size) }
}

@Composable
private fun Toggle(symbol: Symbol, label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = if (on) 0.9f else 0.15f))
        .clickable(role = Role.Switch, onClick = onClick).semantics { contentDescription = "$label ${if (on) "an" else "aus"}" }.padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        SymbolIcon(symbol, if (on) Color.Black else Color.White, 20.dp)
    }
}

/** The "•••" menu of Now Playing, shaped like an iOS menu: rounded, rows with a symbol on the right, a thin line between. */
@Composable
private fun NowPlayingMenu(state: AppState, server: MusicServer, track: Track, onVideo: () -> Unit, onClose: () -> Unit, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val ink = Ink
    androidx.compose.ui.window.Popup(alignment = Alignment.BottomEnd, offset = androidx.compose.ui.unit.IntOffset(0, -120),
        onDismissRequest = onDismiss, properties = androidx.compose.ui.window.PopupProperties(focusable = true)) {
        Column(Modifier.width(250.dp).shadow(24.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
            .background(if (ink.dark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7))) {
            @Composable fun row(label: String, symbol: Symbol, last: Boolean = false, action: () -> Unit) {
                ListRow(label, onClick = { onDismiss(); action() }, separator = !last, trailing = { SymbolIcon(symbol, ink.label, 20.dp) })
            }
            // Titles from "Aus dem Netz" have no album on the server – there the entry would lead nowhere.
            val album = track.albumId?.takeIf { track.path?.contains("/Aus dem Netz/") != true }
            row(tr("Teilen …"), Symbol.Share) { scope.launch { shareTrack(context, state, server, track) } }
            if (album != null) row(tr("Zum Album"), Symbol.Albums) {
                onClose(); state.tab = Tab.Library; state.stacks[Tab.Library] = listOf(Route.Library, Route.AlbumPage(album, web = track.path?.startsWith("https://") == true))
            }
            // iOS: AirPlay. Android: its output picker (phone speaker, Bluetooth, cast devices).
            row(tr("Ausgabegerät …"), Symbol.SpeakerHigh) { showOutputSwitcher(context) }
            if (Milk.loaded) row("Milkdrop-Visualisierung", Symbol.Sparkles, last = !Variant.PRIVATE) { onClose(); state.milk = true }
            if (Variant.PRIVATE) row(tr("Musikvideo"), Symbol.Video, last = true, action = onVideo)
        }
    }
}

/** Lyrics like Music on iOS: big bold lines, the one being sung bright, the others dim; the list follows the song and a tap on a
 *  line jumps there. Unsynchronised texts simply scroll. */
@Composable
private fun LyricsView(lines: List<LyricLine>, position: Long, modifier: Modifier, onSeek: (Long) -> Unit) {
    val synced = lines.any { it.ms != null }
    val current = if (synced) Lyrics.current(lines, position) else -1
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(current) { if (current >= 0) list.animateScrollToItem((current - 1).coerceAtLeast(0)) }
    LazyColumn(modifier.fillMaxWidth(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 160.dp)) {
        itemsIndexed(lines) { i, line ->
            val now = i == current
            val alpha by androidx.compose.animation.core.animateFloatAsState(if (!synced || now) 1f else 0.35f, label = "Zeile")
            Label(line.text.ifEmpty { "♪" }, if (synced) 26f else 22f, 700, Color.White.copy(alpha = alpha),
                Modifier.fillMaxWidth().clickable(enabled = line.ms != null) { line.ms?.let(onSeek) }.padding(vertical = 8.dp), lines = 6)
        }
    }
}

/** The queue like Music on iOS (card 81b01b4a): "Verlauf" above (scroll up), "Als Nächstes", then what Autoplay added.
 *  ≡ on the right drags a title to another place; swiping a title to the left removes it. */
@Composable
private fun QueueView(playback: Playback, server: MusicServer, modifier: Modifier) {
    val white = Color.White
    val soft = Color.White.copy(alpha = 0.55f)
    val queue = playback.queue
    val now = playback.index
    val history = queue.withIndex().take(now).toList()
    val upcoming = queue.withIndex().drop(now + 1).toList()
    val next = upcoming.filter { it.value.id !in playback.autoplayed }
    val auto = upcoming.filter { it.value.id in playback.autoplayed }
    val list = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = if (history.isEmpty()) 0 else history.size + 1)
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val rowPx = with(androidx.compose.ui.platform.LocalDensity.current) { 60.dp.toPx() }
    @Composable fun row(i: Int, t: Track, dim: Boolean, movable: Boolean) {
        var swipe by remember(i, t.id) { mutableFloatStateOf(0f) }
        val y = if (dragging == i) dragY else 0f
        Box(Modifier.fillMaxWidth().height(60.dp).graphicsLayer { translationY = y }.zIndex(if (dragging == i) 1f else 0f)) {
            if (swipe < 0f) Box(Modifier.matchParentSize().background(Color(0xFFFF3B30)), contentAlignment = Alignment.CenterEnd) {
                Label(tr("Entfernen"), 15f, 600, white, Modifier.padding(end = 16.dp))
            }
            Row(Modifier.fillMaxSize().graphicsLayer { translationX = swipe }.background(if (swipe < 0f) Color(0xFF1C1C1E) else Color.Transparent)
                .pointerInput(i, t.id) {
                    detectHorizontalDragGestures(onDragEnd = { if (swipe < -size.width * 0.35f) playback.remove(i); swipe = 0f },
                        onDragCancel = { swipe = 0f }) { _, dx -> swipe = (swipe + dx).coerceIn(-size.width.toFloat(), 0f) }
                }
                .clickable { playback.jump(i) }.semantics { contentDescription = tr("{title} von {artist}", "title" to t.title, "artist" to t.artist) },
                verticalAlignment = Alignment.CenterVertically) {
                Cover(server.cover(t, 120), Modifier.size(44.dp).graphicsLayer { alpha = if (dim) 0.5f else 1f }, 5.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Label(t.title, 15f, 500, if (dim) soft else white); Label(t.artist, 13f, color = soft)
                }
                if (movable) Box(Modifier.size(44.dp).pointerInput(i) {
                        detectDragGestures(onDragStart = { dragging = i; dragY = 0f },
                            onDragEnd = {
                                val to = (i + Math.round(dragY / rowPx)).coerceIn(now + 1, queue.lastIndex)
                                dragging = null; dragY = 0f
                                if (to != i) playback.move(i, to)
                            }, onDragCancel = { dragging = null; dragY = 0f }) { _, d -> dragY += d.y }
                    }.semantics { contentDescription = "Verschieben" }, contentAlignment = Alignment.Center) {
                    SymbolIcon(Symbol.Grip, soft, 20.dp)
                }
            }
        }
    }
    LazyColumn(modifier.fillMaxWidth(), state = list) {
        if (history.isNotEmpty()) {
            item { Label("Verlauf", 17f, 700, white, Modifier.padding(top = 4.dp, bottom = 6.dp)) }
            items(history, key = { "h${it.index}-${it.value.id}" }) { (i, t) -> row(i, t, dim = true, movable = false) }
        }
        item { Label(tr("Als Nächstes"), 17f, 700, white, Modifier.padding(top = 8.dp, bottom = 6.dp)) }
        if (next.isEmpty()) item { Label(if (playback.autoplay) tr("Danach geht es mit Ähnlichem weiter.") else tr("Nichts mehr in der Warteschlange."), 13f, color = soft, modifier = Modifier.padding(bottom = 8.dp)) }
        items(next, key = { "n${it.index}-${it.value.id}" }) { (i, t) -> row(i, t, dim = false, movable = true) }
        if (auto.isNotEmpty()) {
            item {
                Column(Modifier.padding(top = 12.dp, bottom = 6.dp)) {
                    Label(tr("Autoplay"), 17f, 700, white)
                    Label(tr("Ähnliche Titel – läuft weiter, wenn die Warteschlange zu Ende ist"), 13f, color = soft, lines = 2)
                }
            }
            items(auto, key = { "a${it.index}-${it.value.id}" }) { (i, t) -> row(i, t, dim = false, movable = true) }
        }
    }
}

/** Android's output picker (Android 14+: MediaRouter2; before: System UI's media output dialog). */
fun showOutputSwitcher(context: android.content.Context) {
    if (android.os.Build.VERSION.SDK_INT >= 34 && runCatching { android.media.MediaRouter2.getInstance(context).showSystemOutputSwitcher() }.getOrDefault(false)) return
    runCatching {
        context.sendBroadcast(android.content.Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG").setPackage("com.android.systemui")
            .putExtra("package_name", context.packageName))
    }
}
