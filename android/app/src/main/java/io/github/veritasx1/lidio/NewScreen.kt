package io.github.veritasx1.lidio

import io.github.veritasx1.lidio.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * "Neu" like Apple Music on iOS 26 (Olaf 05.10.2026: "viel mehr Action … Neuerscheinungen und Trends"): big cards with the
 * newest releases to swipe through, the newest titles in pages of four, new albums, the charts and playlists of the moment,
 * and the categories. A variant with a music source fills it from there; the public LiDio from the own server's newest music.
 */
@Composable
fun NewScreen(state: AppState, server: MusicServer) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val source = remember(server) { if (server.kind == ServerKind.Web) server else Variant.webServer(context) ?: server }
    val web = source.kind == ServerKind.Web
    val (albums, retryAlbums) = rememberCachedLoad("neu:alben:${source.kind}", source, state.generation) { source.albums(AlbumOrder.Newest, 30) }
    val (songs, _) = rememberCachedLoad("neu:titel:${source.kind}", source, state.generation) { if (web) source.tracks(40) else emptyList() }
    val (lists, _) = rememberCachedLoad("neu:listen:${source.kind}", source, state.generation) { if (web) source.playlists() else emptyList() }
    val (genres, _) = rememberCachedLoad("neu:kategorien:${source.kind}", source) { if (web) source.genres() else emptyList() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = chromePadding()) {
        largeTitle(tr("Neu"))
        loading(albums, retryAlbums) { all ->
            val heroes = all.take(6)
            if (heroes.isNotEmpty()) item { HeroCards(state, source, heroes) }
            if (songs.value?.isNotEmpty() == true) {
                item { SectionHeader(if (web) tr("Top-Titel Deutschland") else tr("Neueste Titel")) }
                item { TrackPages(state, source, songs.value!!) }
            }
            if (all.size > heroes.size) {
                item { SectionHeader(tr("Neue Alben")) }
                item { AlbumStrip(state, source, all.drop(heroes.size)) }
            }
        }
        lists.value?.takeIf { it.isNotEmpty() }?.let { item { SectionHeader(tr("Charts & Playlists")) }; item { PlaylistRow(state, source, it) } }
        genres.value?.takeIf { it.isNotEmpty() }?.let { searchStart(state, source, emptyList(), it, onRecent = {}, onClear = {}) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** The big cards at the top of "Neu": a small line above (what it is), the name, the artist, then the picture, wide. */
@Composable
private fun HeroCards(state: AppState, server: MusicServer, albums: List<Album>) {
    val ink = Ink
    val width = (LocalConfiguration.current.screenWidthDp * 0.86f).dp
    val list = rememberLazyListState()
    LazyRow(state = list, flingBehavior = rememberSnapFlingBehavior(list), contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)) {
        items(albums, key = { "hero" + it.id }) { album ->
            Column(Modifier.width(width).clickable(role = Role.Button) { state.open(Route.AlbumPage(album.id, web = server.kind == ServerKind.Web)) }) {
                Label(tr("NEUERSCHEINUNG"), 12f, 600, ink.secondary)
                Label(album.title, 22f, 600, lines = 1)
                Label(album.artist, 20f, 400, ink.secondary, lines = 1)
                Spacer(Modifier.height(8.dp))
                Cover(server.cover(album.coverId, 900), Modifier.fillMaxWidth().aspectRatio(1.4f), 12.dp)
            }
        }
    }
}

/** Titles in pages of four, side by side to swipe – Apple's "Neueste Titel". */
@Composable
fun TrackPages(state: AppState, server: MusicServer, tracks: List<Track>) {
    val width = (LocalConfiguration.current.screenWidthDp * 0.86f).dp
    val pages = tracks.chunked(4)
    val list = rememberLazyListState()
    // The rows bring their own inset – so the first page starts at the edge and lines up with the heading.
    LazyRow(state = list, flingBehavior = rememberSnapFlingBehavior(list), contentPadding = PaddingValues(end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(pages.size, key = { "seite$it" }) { page ->
            Column(Modifier.width(width)) {
                // The usual title row: where the title is, ↓ to load it (Olaf 05.10.2026: "Hold the Line" on the artist page).
                pages[page].forEachIndexed { i, track ->
                    TrackRow(state, server, track, swipeable = false) { state.playback.play(server, tracks, page * 4 + i) }
                }
            }
        }
    }
}

/** Albums as a row of squares (the "Neue Alben" strip). */
@Composable
fun AlbumStrip(state: AppState, server: MusicServer, albums: List<Album>) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(albums, key = { "neu" + it.id }) { album ->
            AlbumTile(server, album, 160.dp, state) { state.open(Route.AlbumPage(album.id, web = server.kind == ServerKind.Web)) }
        }
    }
}

/**
 * "Top-Auswahl für dich" on Start (iOS 26): tall cards, the cover on top over the whole width and below it a colour from the
 * cover, the picture fading into it (Olaf 06.10.2026: "eines der top alben aus dieser liste … mit einer art verlauf in die
 * hintergrundfarbe"). The mixes show one of their albums – another one each day; then what was played lately.
 */
@Composable
fun TopPicks(state: AppState, server: MusicServer, recent: List<Album>) {
    val width = (LocalConfiguration.current.screenWidthDp * 0.62f).dp
    val day = (System.currentTimeMillis() / 86_400_000L).toInt()
    // A cover for each mix, from its own titles (favourites; titles not heard yet) – cached like the other Start rows.
    val (favCover, _) = rememberCachedLoad("${state.account?.id}:${server.kind}:mixbild:fav:$day", server, day) {
        runCatching { server.favorites().filter { it.coverId != null }.distinctBy { it.albumId ?: it.coverId } }.getOrDefault(emptyList())
            .let { l -> l.getOrNull(Math.floorMod(day, l.size.coerceAtLeast(1)))?.let { server.cover(it, 600) } } ?: ""
    }
    val (newCover, _) = rememberCachedLoad("${state.account?.id}:${server.kind}:mixbild:neu:$day", server, day) {
        runCatching { server.discover(12).filter { it.coverId != null } }.getOrDefault(emptyList()).firstOrNull()?.let { server.cover(it, 600) } ?: ""
    }
    data class Pick(val eyebrow: String, val title: String, val subtitle: String, val cover: String?, val colors: List<Color>,
                    val fallback: (() -> String?)? = null, val open: () -> Unit)
    val picks = buildList {
        add(Pick(tr("FÜR DICH GEMACHT"), tr("Lieblings-Mix"), tr("Deine Favoriten und Ähnliches"), favCover.value?.ifEmpty { null },
            listOf(Color(0xFFFF375F), Color(0xFFBF5AF2))) { state.open(Route.MixPage("favoriten")) })
        add(Pick(tr("FÜR DICH GEMACHT"), tr("Neu entdecken"), tr("Noch nie gehört"), newCover.value?.ifEmpty { null },
            listOf(Color(0xFF0A84FF), Color(0xFF30D158))) { state.open(Route.MixPage("entdecken")) })
        recent.take(4).forEach { a ->
            // Albums without a picture of their own (Emby): the first title's cover, like the album tiles (Olaf: "lädt keine cover").
            add(Pick(tr("WIEDER HÖREN"), a.title, a.artist, server.cover(a.coverId, 600) ?: "album:${a.id}", listOf(Color(0xFF3A3A3C), Color(0xFF1C1C1E)),
                fallback = if (a.coverId == null) ({ server.albumCoverFallback(a.id, 600) }) else null) {
                state.open(Route.AlbumPage(a.id, web = server.kind == ServerKind.Web)) })
        }
    }
    val list = rememberLazyListState()
    LazyRow(state = list, flingBehavior = rememberSnapFlingBehavior(list), contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(picks.size) { i ->
            val p = picks[i]
            // The ground: the cover's own colour once it is known (darkened a little so white text reads), else the mix's colours.
            var tint by remember(p.cover) { mutableStateOf<Color?>(null) }
            val ground = tint?.let { androidx.compose.ui.graphics.lerp(it, Color.Black, 0.35f) }
            Box(Modifier.width(width).aspectRatio(0.78f).clip(RoundedCornerShape(16.dp))
                .background(if (ground != null) Brush.verticalGradient(listOf(ground, ground)) else Brush.verticalGradient(p.colors))
                .clickable(role = Role.Button, onClick = p.open)) {
                if (p.cover != null) {
                    // Square cover at the top, fading into the ground below it.
                    Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                        Cover(p.cover, Modifier.fillMaxSize(), 0.dp, onTint = { tint = it }, fallback = p.fallback)
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to (ground ?: p.colors.last()))))
                        // A soft shade at the top keeps the small line readable on any cover.
                        Box(Modifier.fillMaxWidth().fillMaxHeight(0.22f).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))))
                    }
                } else Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)))))
                // Olaf 06.10.2026: on a white cover the small line got lost – white text with a dark shadow reads on anything.
                val shade = OnArt
                Label(p.eyebrow, 12f, 700, Color.White, modifier = Modifier.align(Alignment.TopStart).padding(14.dp), shadow = shade)
                Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                    Label(p.title, 22f, 700, Color.White, lines = 2, shadow = shade)
                    Label(p.subtitle, 14f, 400, Color.White.copy(alpha = 0.85f), lines = 2, shadow = shade)
                }
            }
        }
    }
}
