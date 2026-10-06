package io.github.veritasx1.lidio

import io.github.veritasx1.lidio.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

// ---------- building blocks ----------

/** The navigation bar of a pushed page: "‹ Mediathek" in tint on the left, the title small in the middle. */
@Composable
fun NavBar(state: AppState, title: String = "", clear: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    val ink = Ink
    val stack = state.stack()
    val previous = stack.getOrNull(stack.size - 2)?.let(::titleOf) ?: state.tab.label
    // iOS 26: back is a round glass button (no text), toolbar buttons sit in glass too.
    if (LocalModern.current) {
        Box(Modifier.fillMaxWidth().background(if (clear) Color.Transparent else ink.background).windowInsetsPadding(WindowInsets.statusBars).height(56.dp)) {
            Box(Modifier.align(Alignment.CenterStart).padding(start = 16.dp)) { GlassCircle(Symbol.ChevronLeft, tr("Zurück zu {previous}", "previous" to previous)) { state.back() } }
            Label(title, 17f, 600, modifier = Modifier.align(Alignment.Center).width(180.dp), align = TextAlign.Center)
            trailing?.let { Box(Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).height(44.dp).glass(RoundedCornerShape(22.dp), 6.dp)
                .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { it() } }
        }
        return
    }
    Box(Modifier.fillMaxWidth().background(ink.background).windowInsetsPadding(WindowInsets.statusBars).height(44.dp)) {
        Row(Modifier.align(Alignment.CenterStart).clickable(role = Role.Button, onClickLabel = tr("Zurück")) { state.back() }
            .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            SymbolIcon(Symbol.ChevronLeft, ink.tint, 22.dp, weight = 2.4f)
            Label(previous, 17f, 400, ink.tint, Modifier.padding(start = 2.dp).width(120.dp))
        }
        Label(title, 17f, 600, modifier = Modifier.align(Alignment.Center).width(180.dp), align = TextAlign.Center)
        trailing?.let { Box(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) { it() } }
    }
}

fun titleOf(route: Route): String = when (route) {
    Route.Library -> tr("Mediathek"); Route.Artists -> tr("Interpreten"); Route.Albums -> tr("Alben"); Route.Tracks -> tr("Titel")
    Route.Playlists -> tr("Playlists"); Route.Servers -> tr("Einstellungen"); Route.Downloaded -> tr("Geladen"); Route.Import -> tr("Importieren"); Route.Upload -> tr("Hochladen"); Route.Mixtapes -> tr("Mixtapes"); is Route.AddMusic -> route.name; is Route.CoverCrop -> tr("Cover"); Route.Skins -> tr("Skins"); Route.Web -> tr("Aus dem Netz"); Route.Favorites -> tr("Lieblingstitel"); is Route.GenrePage -> GenreNames.local(route.genre.name); is Route.MixPage -> if (route.kind == "favoriten") tr("Lieblings-Mix") else tr("Neu entdecken"); Route.Guide -> tr("Anleitung")
    is Route.RemotePage -> route.list.name; is Route.ImportWith -> tr("Importieren")
    is Route.ArtistPage -> route.name; is Route.AlbumPage -> tr("Album"); is Route.PlaylistPage -> route.name
}

fun LazyListScope.largeTitle(text: String, trailing: (@Composable () -> Unit)? = null, topInset: Boolean = true) {
    item {
        Row(Modifier.fillMaxWidth().then(if (topInset) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = if (topInset) 12.dp else 0.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
            Label(text, 34f, 700, modifier = Modifier.weight(1f).semantics { heading() })
            trailing?.invoke()
        }
    }
}

@Composable
fun Waiting() = Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { Label(tr("Wird geladen …"), 15f, color = Ink.secondary) }

@Composable
fun Failed(text: String, retry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SymbolIcon(Symbol.Missing, Ink.secondary, 40.dp)
        Label(text, 17f, 500, modifier = Modifier.padding(top = 12.dp), lines = 3, align = TextAlign.Center)
        Label(tr("Erneut versuchen"), 17f, 400, Ink.tint, Modifier.padding(top = 12.dp).clickable(role = Role.Button, onClick = retry))
    }
}

fun <T> LazyListScope.loading(load: Load<T>, retry: () -> Unit, content: LazyListScope.(T) -> Unit) {
    when {
        load.value != null -> content(load.value)
        load.error != null -> item { Failed(load.error, retry) }
        else -> item { Waiting() }
    }
}

/** An album tile: square art, title, artist (Apple Music's grid). */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun AlbumTile(server: MusicServer, album: Album, width: Dp?, state: AppState? = null, onClick: () -> Unit) {
    // Long press: iOS's context menu (play, next, add to a playlist, pin).
    var menu by remember { mutableStateOf(false) }
    if (menu && state != null) CollectionMenu(state, server, Pin("album", album.id, album.title, album.coverId, server.kind == ServerKind.Web), album.artist) { menu = false }
    Column((if (width != null) Modifier.width(width) else Modifier)
        .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = if (state != null) ({ menu = true }) else null, onLongClickLabel = tr("Mehr"))
        .semantics { contentDescription = tr("{title} von {artist}", "title" to album.title, "artist" to album.artist) }) {
        Cover(server.cover(album.coverId, 400), Modifier.fillMaxWidth().aspectRatio(1f), 8.dp,
            fallback = if (album.coverId == null) ({ server.albumCoverFallback(album.id, 400) }) else null)
        Label(album.title, 13f, 500, modifier = Modifier.padding(top = 6.dp))
        Label(album.artist, 13f, color = Ink.secondary)
    }
}

fun LazyListScope.albumGrid(state: AppState, server: MusicServer, albums: List<Album>) {
    val shown = if (state.playback.settings.hideDuplicates) Duplicates.albums(albums) else albums
    items(shown.chunked(2)) { row ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            row.forEach { album -> Box(Modifier.weight(1f)) { AlbumTile(server, album, null, state) { state.open(Route.AlbumPage(album.id, web = server.kind == ServerKind.Web)) } } }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** A title row: art (or the number on album pages), title, artist; the playing one marked in tint. */
@Composable
fun TrackRow(state: AppState, server: MusicServer, track: Track, number: Int? = null, showArt: Boolean = true,
             subtitle: String? = if (number == null) track.artist else null,
             /** Off in rows that are swiped through sideways (pages of titles): there a sideways swipe turns the page. */
             swipeable: Boolean = true, onClick: () -> Unit) {
    val ink = Ink
    val playing = state.playback.current?.id == track.id
    // Card 2c35cd98 / Olaf 05.10.2026: what the source cannot deliver is grey and does not pretend to play or load.
    val unavailable = state.playback.isUnavailable(track)
    var menu by remember { mutableStateOf(false) }
    if (menu) TrackMenu(state, server, track) { menu = false }
    // iOS: swipe a title to the right → "Als Nächstes", to the left → "Zuletzt spielen".
    var swipe by remember(track.id) { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val limit = with(androidx.compose.ui.platform.LocalDensity.current) { 96.dp.toPx() }
    Box(Modifier.fillMaxWidth().pointerInput(track.id, swipeable) {
        if (!swipeable) return@pointerInput
        detectHorizontalDragGestures(onDragEnd = {
            if (swipe > limit) { state.playback.playNext(server, track); state.notice = tr("„{title}“ kommt als Nächstes.", "title" to track.title) }
            else if (swipe < -limit) { state.playback.addToQueue(server, track); state.notice = tr("„{title}“ kommt zuletzt.", "title" to track.title) }
            swipe = 0f
        }, onDragCancel = { swipe = 0f }) { _, dx -> swipe = (swipe + dx).coerceIn(-limit * 1.4f, limit * 1.4f) }
    }) {
        if (swipe != 0f) Row(Modifier.matchParentSize().background(if (swipe > 0) Color(0xFF5856D6) else Color(0xFFFF9500)).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (swipe > 0) Arrangement.Start else Arrangement.End) {
            SymbolIcon(if (swipe > 0) Symbol.PlayNext else Symbol.PlayLast, Color.White, 22.dp)
        }
        Box(Modifier.graphicsLayer { translationX = swipe }.background(if (swipe != 0f) ink.background else Color.Transparent)) {
    ListRow(track.title, if (unavailable) tr("Nicht verfügbar") else subtitle,
        onClick = if (unavailable) ({ state.notice = tr("„{title}“ ist zurzeit nicht verfügbar.", "title" to track.title) }) else onClick,
        onLongClick = { if (!unavailable) menu = true },
        titleColor = if (unavailable) ink.tertiary else if (playing) ink.tint else ink.label, height = if (showArt) 60.dp else 48.dp,
        leading = {
            if (showArt) Cover(server.cover(track, 120), Modifier.size(48.dp).graphicsLayer { alpha = if (unavailable) 0.35f else 1f }, 5.dp)
            else Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                if (playing) SymbolIcon(Symbol.SpeakerHigh, ink.tint, 16.dp)
                else Label("${number ?: ""}", 15f, color = ink.secondary, tabular = true)
            }
        },
        trailing = {
            val context = androidx.compose.ui.platform.LocalContext.current
            // Card 2aaf09ce: loading → a filling ring, waiting → a still dotted ring, loaded → the small arrow.
            val key = state.account?.let { Offline.downloadKey(it.id, track) }
            when {
                unavailable -> {}
                // From the internet but loaded onto this phone: the phone, not the cloud (card 66bd0f1b).
                server.kind == ServerKind.Web && WebDownloads.fileFor(track) != null ->
                    SymbolIcon(Symbol.Phone, ink.secondary, 14.dp, modifier = Modifier.padding(end = 8.dp).semantics { contentDescription = tr("Auf diesem Telefon") })
                // Neither on the server nor on the phone – out in the internet: a cloud (Olaf 05.10.2026).
                server.kind == ServerKind.Web ->
                    SymbolIcon(Symbol.Cloud, ink.tertiary, 15.dp, modifier = Modifier.padding(end = 8.dp).semantics { contentDescription = tr("Im Internet") })
                key != null && key in Offline.live -> Box(Modifier.padding(end = 8.dp)) {
                    val p = Offline.live[key]
                    if (p == null) WaitingRing(18.dp) else ProgressRing(p, ink.tint, 18.dp)
                }
                // Olaf 05.10.2026: where the title is – a small phone (on this phone: loaded, heard, own folders) or a small server.
                server.kind == ServerKind.Local || state.account?.let { Offline.stored(context, it.id, track) } != null || WebDownloads.copyOf(track) != null ->
                    SymbolIcon(Symbol.Phone, ink.secondary, 14.dp, modifier = Modifier.padding(end = 8.dp).semantics { contentDescription = tr("Auf diesem Telefon") })
                // On the server: tap loads this one title onto the phone (Olaf 05.10.2026: single songs, not only whole playlists).
                else -> Box(Modifier.size(32.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = tr("Auf dieses Telefon laden")) {
                        state.account?.let { a -> Offline.download(context, a.id, server, listOf(track)) } }
                    .semantics { contentDescription = tr("Auf deinem Server – antippen lädt aufs Telefon") }, contentAlignment = Alignment.Center) {
                    SymbolIcon(Symbol.Server, ink.tertiary, 14.dp)
                }
            }
            if (track.duration > 0) Label(duration(track.duration), 13f, color = ink.secondary, tabular = true)
            // From the internet: tap plays (streamed), ↓ loads it into "Aus dem Netz" (card 6c9ba022).
            if (server.kind == ServerKind.Web && !unavailable) Box(Modifier.padding(start = 4.dp)) { WebLoadButton(track.asHit(server), state) }
        })
        }
    }
}

// ---------- tabs ----------

@Composable
fun StartScreen(state: AppState, server: MusicServer) {
    val (recent, retryRecent) = rememberLoad(server, state.generation) { server.albums(AlbumOrder.Recent, 20) }
    val (newest, retryNewest) = rememberLoad(server, state.generation) { server.albums(AlbumOrder.Newest, 20) }
    val (frequent, _) = rememberLoad(server, state.generation) { server.albums(AlbumOrder.Frequent, 20) }
    val (lists, retryLists) = rememberLoad(server, state.generation) { if (server.kind == ServerKind.Web) server.playlists() else emptyList() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = chromePadding()) {
        largeTitle(tr("Start"))
        item { SectionHeader(tr("Top-Auswahl für dich")) }
        item { TopPicks(state, server, recent.value.orEmpty()) }
        if (recent.value?.isNotEmpty() == true) { item { SectionHeader(tr("Zuletzt gespielt")) }; item { AlbumRow(state, server, recent.value) } }
        if (server.kind == ServerKind.Web) {
            // LiDio privat without server (card d1f83bf9): what is current – the charts and the web source's playlists first.
            item { SectionHeader(tr("Charts & Playlists")) }
            loading(lists, retryLists) { item { PlaylistRow(state, server, it) } }
        }
        // iOS 26 "Top-Auswahl für dich": tall cards – the mixes made from the own library, then what was played lately.
        item { SectionHeader(if (server.kind == ServerKind.Web) "Neuerscheinungen" else tr("Neu hinzugefügt")) }
        if (newest.error == null || recent.error == null) loading(newest, retryNewest) { item { AlbumRow(state, server, it) } }
        if (frequent.value?.isNotEmpty() == true && frequent.value != recent.value) { item { SectionHeader(tr("Oft gehört")) }; item { AlbumRow(state, server, frequent.value) } }
        if (recent.error != null && newest.error != null) item {
            Failed(recent.error, retryRecent)
            Label(tr("Geladene Musik ansehen"), 17f, 400, Ink.tint, Modifier.fillMaxWidth().clickable(role = Role.Button) {
                state.tab = Tab.Library; state.stacks[Tab.Library] = listOf(Route.Library, Route.Downloaded) }.padding(8.dp), align = TextAlign.Center)
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun AlbumRow(state: AppState, server: MusicServer, all: List<Album>) {
    val albums = if (state.playback.settings.hideDuplicates) Duplicates.albums(all) else all
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(albums, key = { it.id }) { album -> AlbumTile(server, album, 160.dp, state) { state.open(Route.AlbumPage(album.id, web = server.kind == ServerKind.Web)) } }
    }
}

/** Playlists as tiles, two per row down the page (a category with nothing else on it). */
fun LazyListScope.playlistGrid(state: AppState, server: MusicServer, lists: List<Playlist>) {
    items(lists.chunked(2), key = { row -> "pg" + row.first().id }) { row ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { p ->
                Column(Modifier.weight(1f).clickable(role = Role.Button) { state.open(Route.PlaylistPage(p.id, p.name, web = server.kind == ServerKind.Web)) }) {
                    Cover(server.playlistCover(p, 400), Modifier.fillMaxWidth().aspectRatio(1f), 8.dp)
                    Label(p.name, 15f, 500, modifier = Modifier.padding(top = 6.dp), lines = 2)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                    if (p.mixtape) { SymbolIcon(Symbol.Cassette, Ink.secondary, 14.dp); Box(Modifier.width(4.dp)) }
                    Label(listOfNotNull(if (p.mixtape) tr("Mixtape") else null, if (p.trackCount > 0) tr("{trackCount} Titel", "trackCount" to p.trackCount) else null).joinToString(" · "),
                        13f, color = Ink.secondary)
                }
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** Playlists as large tiles in a row. */
@Composable
fun PlaylistRow(state: AppState, server: MusicServer, lists: List<Playlist>) {
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(lists, key = { it.id }) { p ->
            Column(Modifier.width(160.dp).clickable(role = Role.Button) { state.open(Route.PlaylistPage(p.id, p.name, web = server.kind == ServerKind.Web)) }) {
                Cover(server.playlistCover(p, 320), Modifier.size(160.dp), 8.dp)
                Label(p.name, 15f, 500, modifier = Modifier.padding(top = 6.dp), lines = 2)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (p.mixtape) { SymbolIcon(Symbol.Cassette, Ink.secondary, 14.dp); Box(Modifier.width(4.dp)) }
                    Label(listOfNotNull(if (p.mixtape) tr("Mixtape") else null, if (p.trackCount > 0) tr("{trackCount} Titel", "trackCount" to p.trackCount) else null).joinToString(" · "),
                        13f, color = Ink.secondary)
                }
            }
        }
    }
}

@Composable
fun LibraryScreen(state: AppState, server: MusicServer) {
    val ink = Ink
    val (newest, retry) = rememberLoad(server, state.generation) { server.albums(AlbumOrder.Newest, 24) }
    // iOS: "Bearbeiten" – which sections the library shows (kept on the phone).
    val prefs = remember { state.accounts.context.getSharedPreferences("einstellungen", android.content.Context.MODE_PRIVATE) }
    var hidden by remember { mutableStateOf(prefs.getStringSet("mediathekAus", emptySet()).orEmpty()) }
    var editing by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = chromePadding()) {
        largeTitle(tr("Mediathek"), trailing = {
            Label(if (editing) tr("Fertig") else tr("Bearbeiten"), 17f, if (editing) 600 else 400, ink.tint,
                Modifier.padding(end = 12.dp).clickable(role = Role.Button) { editing = !editing })
            // Olaf 05.10.2026: the settings behind a gear, not a server symbol.
            Box(Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = tr("Einstellungen")) { state.open(Route.Servers) }
                .semantics { contentDescription = tr("Einstellungen") }, contentAlignment = Alignment.Center) { SymbolIcon(Symbol.Gear, ink.tint, 24.dp) }
        })
        // iOS 26: what is pinned stands above everything (long press on an album or a playlist → Anheften).
        item { PinnedGrid(state, server) }
        val entries = listOf(Triple(Symbol.Playlists, tr("Playlists"), Route.Playlists), Triple(Symbol.Cassette, tr("Mixtapes"), Route.Mixtapes), Triple(Symbol.Artists, tr("Interpreten"), Route.Artists),
            Triple(Symbol.Albums, tr("Alben"), Route.Albums), Triple(Symbol.Note, tr("Titel"), Route.Tracks), Triple(Symbol.Star, tr("Lieblingstitel"), Route.Favorites), Triple(Symbol.Downloaded, tr("Geladen"), Route.Downloaded)) +
            (if (Variant.PRIVATE) listOf(Triple(Symbol.Globe, tr("Aus dem Netz"), Route.Web)) else emptyList()) +
            // Card e1f44cfb: own music (ripped CDs) onto the server – where the LiDio-Lader is there (Emby/Jellyfin).
            (if (Variant.PRIVATE && server.kind != ServerKind.Local && server.kind != ServerKind.Web) listOf(Triple(Symbol.PhoneUpload, tr("Musik hochladen"), Route.Upload)) else emptyList())
        items(if (editing) entries else entries.filter { it.second !in hidden }) { (symbol, label, route) ->
            if (editing) ListRow(label, height = 48.dp,
                leading = { Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) { SymbolIcon(symbol, ink.tint, 24.dp) } },
                trailing = { IosSwitch(label !in hidden, label) { on -> hidden = if (on) hidden - label else hidden + label; prefs.edit().putStringSet("mediathekAus", hidden).apply() } })
            else ListRow(label, onClick = { state.open(route) }, height = 48.dp,
                leading = { Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) { SymbolIcon(symbol, ink.tint, 24.dp) } },
                trailing = { Chevron() }, titleColor = ink.label)
        }
        item { SectionHeader(tr("Zuletzt hinzugefügt")) }
        loading(newest, retry) { albumGrid(state, server, it) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun ArtistsScreen(state: AppState, server: MusicServer) {
    val (artists, retry) = rememberLoad(server, state.generation) { server.artists() }
    Column(Modifier.fillMaxSize()) {
        NavBar(state)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = chromePadding()) {
            largeTitle(tr("Interpreten"), topInset = false)
            loading(artists, retry) { list ->
                items(list, key = { it.id }) { artist ->
                    ListRow(artist.name, onClick = { state.open(Route.ArtistPage(artist.id, artist.name, web = server.kind == ServerKind.Web)) }, height = 60.dp,
                        leading = { Cover(server.cover(artist.coverId, 120), Modifier.size(48.dp), 24.dp) }, trailing = { Chevron() })
                }
            }
        }
    }
}

@Composable
fun AlbumsScreen(state: AppState, server: MusicServer) {
    val prefs = remember { state.accounts.context.getSharedPreferences("einstellungen", android.content.Context.MODE_PRIVATE) }
    var order by remember { mutableStateOf(runCatching { AlbumOrder.valueOf(prefs.getString("albenSortierung", "")!!) }.getOrDefault(AlbumOrder.Newest)) }
    var sortMenu by remember { mutableStateOf(false) }
    val pages = rememberPages(server, state.generation, order) { offset, size -> server.albums(order, size, offset) }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    // Near the end: the next page.
    LaunchedEffect(list, pages) {
        androidx.compose.runtime.snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= list.layoutInfo.totalItemsCount - 6) pages.more() }
    }
    Column(Modifier.fillMaxSize()) {
        // iOS: "Sortieren" in the navigation bar opens a menu with a tick at the chosen order; LiDio remembers it.
        NavBar(state, trailing = {
            Box(Modifier.clickable(role = Role.Button, onClickLabel = "Sortieren") { sortMenu = true }.semantics { contentDescription = tr("Sortieren nach {label}", "label" to order.label) }) {
                Label("Sortieren", 17f, color = Ink.tint)
            }
        })
        if (sortMenu) MenuSheet(tr("Sortieren nach"), null, { sortMenu = false }) {
            val choices = listOf(AlbumOrder.Newest, AlbumOrder.Recent, AlbumOrder.Alphabetical, AlbumOrder.Artist, AlbumOrder.Year)
            choices.forEachIndexed { i, o ->
                ListRow(o.label, separator = i < choices.lastIndex, onClick = { order = o; prefs.edit().putString("albenSortierung", o.name).apply(); sortMenu = false },
                    trailing = { if (o == order) SymbolIcon(Symbol.Check, Ink.tint, 16.dp, weight = 2.4f) })
            }
        }
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = chromePadding()) {
            largeTitle(tr("Alben"), topInset = false)
            item { Label(order.label, 13f, color = Ink.secondary, modifier = Modifier.padding(horizontal = 16.dp)) }
            albumGrid(state, server, pages.items)
            pageFooter(pages)
        }
    }
}

fun LazyListScope.pageFooter(pages: Pages<*>) {
    when {
        pages.error != null -> item { Failed(pages.error!!) { } }
        pages.loading -> item { Waiting() }
    }
}

@Composable
fun TracksScreen(state: AppState, server: MusicServer) {
    val pages = rememberPages(server, state.generation) { offset, size ->
        server.tracks(size, offset).let { if (state.playback.settings.hideDuplicates) Duplicates.tracks(it) else it }
    }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(list, pages) {
        androidx.compose.runtime.snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= list.layoutInfo.totalItemsCount - 10) pages.more() }
    }
    Column(Modifier.fillMaxSize()) {
        NavBar(state)
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = chromePadding()) {
            largeTitle(tr("Titel"), topInset = false)
            if (pages.items.isNotEmpty()) item { PlayButtons(state, server, pages.items) }
            itemsIndexed(pages.items, key = { i, t -> "$i-${t.id}" }) { i, track -> TrackRow(state, server, track) { state.playback.play(server, pages.items, i) } }
            pageFooter(pages)
        }
    }
}

private val mixtapeScope = kotlinx.coroutines.MainScope()

/** A sheet asking for a name (new Mixtape …). */
@Composable
fun NameSheet(title: String, hint: String, onClose: () -> Unit, onDone: (String) -> Unit) {
    val ink = Ink
    var name by remember { mutableStateOf("") }
    MenuSheet(title, null, onClose) {
        Row(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(ink.grouped).padding(10.dp)) {
            Box(Modifier.weight(1f)) {
                if (name.isEmpty()) Label(hint, 17f, color = ink.tertiary)
                androidx.compose.foundation.text.BasicTextField(name, { name = it.take(80) }, singleLine = true, textStyle = style(17f, color = ink.label),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(ink.tint), modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint })
            }
        }
        MenuRow(tr("Anlegen"), Symbol.Plus, last = true, color = if (name.isBlank()) ink.tertiary else ink.tint) { if (name.isNotBlank()) onDone(name.trim()) }
    }
}

@Composable
fun PlaylistsScreen(state: AppState, server: MusicServer, mixtapes: Boolean = false) {
    // mixtapes: the section "Mixtapes" (card c9b15c67) – the same list, only the playlists shared as a Mixtape.
    val (all, retry) = rememberCachedLoad("${state.account?.id}:${server.kind}:playlists", server, state.generation) { server.playlists() }
    val lists = if (!mixtapes) all else Load(all.value?.filter { it.mixtape }, all.error, all.loading)
    var naming by remember { mutableStateOf(false) }
    if (naming) NameSheet(tr("Neues Mixtape"), tr("Name des Mixtapes"), { naming = false }) { name ->
        naming = false
        mixtapeScope.launch {
            val made = withContext(Dispatchers.IO) { runCatching { server.createPlaylist(name, emptyList()).also { server.markMixtape(it.id) } }.getOrNull() }
            state.notice = if (made != null) tr("Mixtape „{name}“ angelegt – lange auf Titel drücken → Zur Playlist hinzufügen.", "name" to name) else tr("Das Mixtape ließ sich nicht anlegen.")
            state.generation++
        }
    }
    Column(Modifier.fillMaxSize()) {
        NavBar(state)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = chromePadding()) {
            if (mixtapes) largeTitle(tr("Mixtapes"), topInset = false, trailing = {
                // A new Mixtape just for oneself: a name, an empty playlist marked as Mixtape – fill it with "Zur Playlist hinzufügen".
                Box(Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = tr("Neues Mixtape")) { naming = true }
                    .semantics { contentDescription = tr("Neues Mixtape") }, contentAlignment = Alignment.Center) { SymbolIcon(Symbol.Plus, Ink.tint, 24.dp, weight = 2.2f) }
            }) else largeTitle(tr("Playlists"), topInset = false, trailing = {
                Box(Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = tr("Playlist importieren")) { state.open(Route.Import) }
                    .semantics { contentDescription = tr("Playlist importieren") }, contentAlignment = Alignment.Center) { SymbolIcon(Symbol.Plus, Ink.tint, 24.dp, weight = 2.2f) }
            })
            loading(lists, retry) { list ->
                if (list.isEmpty()) item { Label(if (mixtapes) tr("Noch keine Mixtapes. Eine Playlist lange drücken → „Als Mixtape teilen“.") else tr("Noch keine Playlists auf diesem Server."),
                    15f, color = Ink.secondary, lines = 3, modifier = Modifier.padding(16.dp)) }
                items(list, key = { it.id }) { playlist ->
                    var menu by remember { mutableStateOf(false) }
                    if (menu) CollectionMenu(state, server, Pin("playlist", playlist.id, playlist.name, playlist.coverId, server.kind == ServerKind.Web), null, playlist.mixtape) { menu = false }
                    ListRow(playlist.name, listOfNotNull(if (playlist.mixtape) tr("Mixtape") else null, playlist.trackCount.takeIf { it > 0 }?.let { tr("{it} Titel", "it" to it) })
                        .joinToString(" · ").ifEmpty { null }, subtitleSymbol = if (playlist.mixtape) Symbol.Cassette else null,
                        onClick = { state.open(Route.PlaylistPage(playlist.id, playlist.name, web = server.kind == ServerKind.Web)) },
                        onLongClick = { menu = true }, height = 68.dp,
                        leading = { Cover(server.playlistCover(playlist, 160), Modifier.size(56.dp), 6.dp) }, trailing = { Chevron() })
                }
            }
        }
    }
}

@Composable
fun PlayButtons(state: AppState, server: MusicServer, tracks: List<Track>) {
    // Olaf 05.10.2026: from the internet, every title is checked in the background – what cannot be played turns grey,
    // so fast that the user notices nothing (about a second for a long list).
    if (server.kind == ServerKind.Web) LaunchedEffect(tracks.map { it.id }) {
        val bad = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { server.unplayable(tracks) }.getOrDefault(emptySet()) }
        bad.forEach { state.playback.unplayable[it] = true }
        // "Wiedergabe" starts with the first title: its sound address is found now, so the tap plays at once.
        // Only this one – the player fetches the next ones itself while a title plays.
        val first = tracks.firstOrNull { !state.playback.isUnavailable(it) }
        if (first != null && state.playback.current == null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { state.playback.warm(server, first) }
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Capsule(Symbol.Play, tr("Wiedergabe"), Modifier.weight(1f)) { if (tracks.isNotEmpty()) state.playback.play(server, tracks) }
        Capsule(Symbol.Shuffle, tr("Zufall"), Modifier.weight(1f)) { if (tracks.isNotEmpty()) state.playback.play(server, tracks, shuffled = true) }
    }
}

// ---------- pages ----------

@Composable
fun AlbumScreen(state: AppState, server: MusicServer, route: Route.AlbumPage) {
    val ink = Ink
    val hide = state.playback.settings.hideDuplicates
    val (load, retry) = rememberCachedLoad("${state.account?.id}:${server.kind}:al:${route.id}:$hide", server, route.id) {
        server.album(route.id).let { (a, t) -> a to if (hide) Duplicates.tracks(t) else t }
    }
    val modern = LocalModern.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val gapsOf = remember(route.id) { mutableStateOf(AlbumGaps.Gaps()) }
    LaunchedEffect(load.value) {
        val (album, tracks) = load.value ?: return@LaunchedEffect
        if (Variant.PRIVATE) gapsOf.value = withContext(Dispatchers.IO) { AlbumGaps.find(context, server, album, tracks) }
    }
    var ground by remember(route.id) { mutableStateOf<Color?>(null) }
    val trailing: (@Composable () -> Unit)? = load.value?.takeIf { server.kind != ServerKind.Local }?.let { {
        if (server.kind == ServerKind.Web) WebListButton(it.second.map { t -> t.asHit(server) }) else DownloadButton(state, server, it.second) } }
    PageFrame(state, modern, ground, trailing) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = chromePadding()) {
            loading(load, retry) { (album, all) ->
                val discs = all.mapNotNull { it.disc }.toSet().size > 1
                // Titles the LiDio-Lader brought have no number: in the album's order from the source (card 66bd0f1b).
                val tracks = if (discs) all else AlbumGaps.sorted(all, gapsOf.value.order)
                if (modern) item {
                    ImmersiveHeader(server.cover(album.coverId, 900), album.title, album.artist,
                        listOfNotNull(album.genre?.let { GenreNames.local(it) }, album.year?.toString()).joinToString(" · "), ground,
                        onSubtitle = album.artist.takeIf { it.isNotBlank() }?.let { { state.open(Route.ArtistPage(album.artistId ?: "", album.artist, web = server.kind == ServerKind.Web)) } },
                        fallback = { tracks.firstOrNull { it.coverId != null }?.let { server.cover(it, 900) } }) { ground = it }
                } else item {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Cover(server.cover(album.coverId, 600) ?: tracks.firstOrNull { it.coverId != null }?.let { server.cover(it, 600) },
                            Modifier.size(260.dp), 10.dp)
                        Label(album.title, 22f, 700, modifier = Modifier.padding(top = 16.dp, start = 24.dp, end = 24.dp), lines = 2, align = TextAlign.Center)
                        Label(album.artist, 22f, 400, ink.tint, Modifier.clickable(enabled = album.artist.isNotBlank()) {
                            state.open(Route.ArtistPage(album.artistId ?: "", album.artist, web = server.kind == ServerKind.Web)) })
                        Label(listOfNotNull(album.genre?.let { GenreNames.local(it) }, album.year?.toString()).joinToString(" · "), 13f, 600, ink.secondary, Modifier.padding(top = 4.dp))
                    }
                }
                item { PlayButtons(state, server, tracks) }
                item { Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(ink.separator)) }
                // Card c569f2c1: an incomplete album shows what the server lacks, grey at its place (LiDio privat).
                val gaps = if (discs) emptyList() else gapsOf.value.missing
                if (gaps.isNotEmpty()) item { MissingHeader(state, gaps) }
                val rows = withMissing(tracks, gaps)
                fun queue(): List<Track> = rows.mapNotNull { r -> if (r is Track) r else MissingHits.known(context, r as String) }
                itemsIndexed(rows, key = { i, r -> if (r is Track) r.id else "m$i-$r" }) { i, r ->
                    if (r is Track) {
                        val t = tracks.indexOf(r)
                        if (discs && (t == 0 || tracks[t - 1].disc != r.disc)) Label("CD ${r.disc}", 15f, 600, ink.secondary, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
                        TrackRow(state, server, r, number = if (gaps.isEmpty()) r.number ?: (t + 1) else i + 1, showArt = false,
                            subtitle = r.artist.takeIf { album.compilation || it != album.artist }) {
                            if (gaps.isEmpty()) state.playback.play(server, tracks, t) else queue().let { q -> state.playback.play(server, q, q.indexOf(r)) } }
                    } else MissingTrackRow(state, r as String, number = i + 1, onPlay = { found ->
                        val q = queue().let { list -> if (list.none { it.id == found.id }) list + found else list }
                        state.playback.play(server, q, q.indexOfFirst { it.id == found.id }.coerceAtLeast(0))
                    })
                }
                item {
                    Column(Modifier.padding(16.dp)) {
                        album.year?.let { Label(tr("Erschienen {it}", "it" to it), 13f, color = ink.secondary) }
                        Label(summary(tracks.size, tracks.sumOf { it.duration }), 13f, color = ink.secondary)
                    }
                }
            }
        }
    }
}

@Composable
fun ArtistScreen(state: AppState, server: MusicServer, route: Route.ArtistPage) {
    val ink = Ink
    val modern = LocalModern.current
    // A title only knows its artist's name: then the page looks the artist up first (route.id empty).
    val (load, retry) = rememberCachedLoad("${state.account?.id}:${server.kind}:ar:${route.id}:${route.name}", server, route.id, route.name) {
        val id = route.id.ifEmpty { server.findArtist(route.name) ?: throw ServerError(tr("Zu „{name}“ gibt es keine Interpretenseite.", "name" to route.name)) }
        server.artistPage(id)
    }
    var ground by remember(route.id, route.name) { mutableStateOf<Color?>(null) }
    val web = server.kind == ServerKind.Web
    // Card e0f5b181 (Olaf 06.10.2026: "man sieht nur das, was der Server hat"): LiDio privat adds the artist's page at its
    // music source – top titles, and the albums and singles the own server doesn't have – below the own ones.
    val context = androidx.compose.ui.platform.LocalContext.current
    val netServer = remember { if (web) null else Variant.webServer(context) }
    val (netLoad, _) = rememberLoad(route.name, netServer) {
        val net = netServer ?: return@rememberLoad null
        runCatching { net.findArtist(route.name)?.let { net.artistPage(it) } }.getOrNull()
    }
    PageFrame(state, modern, ground, trailing = null) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = chromePadding()) {
            if (!modern) largeTitle(route.name, topInset = false)
            loading(load, retry) { info ->
                val playable = if (state.playback.settings.hideDuplicates) Duplicates.tracks(info.top) else info.top
                val net = netLoad.value
                val ownTitles = (info.albums + info.singles).map { Matcher.normal(it.title) }.toSet()
                val netAlbums = net?.albums.orEmpty().filter { Matcher.normal(it.title) !in ownTitles }
                val netSingles = net?.singles.orEmpty().filter { Matcher.normal(it.title) !in ownTitles }
                val ownSongs = playable.map { Matcher.normal(it.title) }.toSet()
                val netTop = net?.top.orEmpty().filter { !it.unavailable && Matcher.normal(it.title) !in ownSongs }
                val picture = info.picture ?: server.cover(info.artist.coverId, 1200) ?: net?.let { n -> n.picture ?: netServer?.cover(n.artist.coverId, 1200) }
                if (modern) item {
                    ArtistHero(picture, info.artist.name.ifEmpty { route.name }, ground,
                        onPlay = playable.takeIf { it.isNotEmpty() }?.let { { state.playback.play(server, it) } }) { ground = it }
                } else if (picture != null) item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Cover(picture, Modifier.size(180.dp), 90.dp)
                    }
                }
                if (playable.isNotEmpty()) {
                    item { SectionHeader(tr("Top-Titel")) }
                    item { TrackPages(state, server, playable) }
                }
                if (info.albums.isNotEmpty()) {
                    item { SectionHeader(tr("Alben")) }
                    item { AlbumStrip(state, server, info.albums) }
                }
                if (info.singles.isNotEmpty()) {
                    item { SectionHeader(tr("Singles & EPs")) }
                    item { AlbumStrip(state, server, info.singles) }
                }
                if (netServer != null) {
                    if (netTop.isNotEmpty()) {
                        item { SectionHeader(if (playable.isEmpty()) tr("Top-Titel") else tr("Weitere Titel im Netz")) }
                        item { TrackPages(state, netServer, netTop) }
                    }
                    if (netAlbums.isNotEmpty()) {
                        item { SectionHeader(if (info.albums.isEmpty()) tr("Alben") else tr("Weitere Alben im Netz")) }
                        item { AlbumStrip(state, netServer, netAlbums) }
                    }
                    if (netSingles.isNotEmpty()) {
                        item { SectionHeader(if (info.singles.isEmpty()) tr("Singles & EPs") else tr("Weitere Singles & EPs im Netz")) }
                        item { AlbumStrip(state, netServer, netSingles) }
                    }
                }
                if (info.playlists.isNotEmpty()) {
                    item { SectionHeader(tr("Playlists")) }
                    item { PlaylistRow(state, server, info.playlists) }
                }
                (info.about ?: net?.about)?.let { text ->
                    item { SectionHeader(tr("Über {name}", "name" to info.artist.name.ifEmpty { route.name })) }
                    item { AboutCard(text) }
                }
                // The own server's similar artists, else the music source's (they open as its pages).
                val similarFrom = if (info.similar.isEmpty() && net != null && netServer != null) netServer else server
                val similar = if (similarFrom === server) info.similar else net?.similar.orEmpty()
                if (similar.isNotEmpty()) {
                    item { SectionHeader(tr("Ähnliche Künstler:innen")) }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(similar, key = { "aehnlich" + it.id }) { a ->
                                Column(Modifier.width(112.dp).clickable(role = Role.Button) { state.open(Route.ArtistPage(a.id, a.name, similarFrom.kind == ServerKind.Web)) },
                                    horizontalAlignment = Alignment.CenterHorizontally) {
                                    Cover(similarFrom.cover(a.coverId, 300), Modifier.size(112.dp), 56.dp)
                                    Label(a.name, 13f, 500, modifier = Modifier.padding(top = 6.dp), lines = 2, align = TextAlign.Center)
                                }
                            }
                        }
                    }
                }
                if (playable.isEmpty() && info.albums.isEmpty() && info.singles.isEmpty() && info.about == null && netTop.isEmpty() && netAlbums.isEmpty())
                    item { Label(tr("Zu diesem Interpreten gibt es hier noch nichts."), 15f, color = ink.secondary, modifier = Modifier.padding(16.dp)) }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** "Über …": the text in a card, a few lines first; a tap shows all of it (like Apple Music). */
@Composable
private fun AboutCard(text: String) {
    val ink = Ink
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ink.card)
        .clickable(role = Role.Button) { open = !open }.padding(16.dp)) {
        Label(text, 15f, color = ink.label, lines = if (open) Int.MAX_VALUE else 5)
        if (!open) Label(tr("Mehr"), 15f, 600, ink.tint, Modifier.padding(top = 6.dp))
    }
}

@Composable
fun PlaylistScreen(state: AppState, server: MusicServer, route: Route.PlaylistPage) {
    val ink = Ink
    val context = androidx.compose.ui.platform.LocalContext.current
    val hide = state.playback.settings.hideDuplicates
    val (load, retry) = rememberCachedLoad("${state.account?.id}:${server.kind}:pl:${route.id}:$hide", server, route.id, state.generation, disk = PlaylistDisk) {
        server.playlist(route.id).let { (p, t) -> p to if (hide) Duplicates.tracks(t) else t }
    }
    Column(Modifier.fillMaxSize()) {
        // Card f3cba42b: "Freigeben" next to ↓ (own server playlists only).
        var sharing by remember { mutableStateOf(false) }
        if (sharing) load.value?.first?.let { ShareSheet(state, server, it) { sharing = false } }
        val modern = LocalModern.current
        var ground by remember(route.id) { mutableStateOf<Color?>(null) }
        PageFrame(state, modern, ground, trailing = load.value?.takeIf { server.kind != ServerKind.Local }?.let { {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (server.kind == ServerKind.Emby || server.kind == ServerKind.Jellyfin || server.kind == ServerKind.Navidrome)
                    Box(Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button) { sharing = true }.semantics { contentDescription = tr("Freigeben") },
                        contentAlignment = Alignment.Center) { SymbolIcon(Symbol.People, Ink.tint, 24.dp) }
                if (server.kind == ServerKind.Web) WebListButton(it.second.map { t -> t.asHit(server) }, state, it.first.name,
                    server.cover(it.first.coverId ?: it.second.firstOrNull()?.coverId, 900)) else DownloadButton(state, server, it.second)
            } } }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = chromePadding()) {
            loading(load, retry) { (playlist, tracks) ->
                if (modern) item {
                    ImmersiveHeader(server.playlistCover(playlist, 900, tracks.firstOrNull()?.coverId), playlist.name, null,
                        summary(tracks.size, tracks.sumOf { it.duration }), ground) { ground = it }
                } else item {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Cover(server.playlistCover(playlist, 600, tracks.firstOrNull()?.coverId), Modifier.size(240.dp), 10.dp)
                        Label(playlist.name, 22f, 700, modifier = Modifier.padding(top = 16.dp, start = 24.dp, end = 24.dp), lines = 2, align = TextAlign.Center)
                        Label(summary(tracks.size, tracks.sumOf { it.duration }), 13f, color = ink.secondary, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                item { PlayButtons(state, server, tracks) }
                // Card eea4ee65 (Olaf: „in einem leeren Mixtape kann man keine Lieder hinzufügen“): like Apple Music, every own
                // playlist has "Musik hinzufügen" – a search over the own library, ＋ adds at once.
                if (server.kind == ServerKind.Emby || server.kind == ServerKind.Jellyfin || server.kind == ServerKind.Navidrome) item {
                    ListRow(tr("Musik hinzufügen"), onClick = { state.open(Route.AddMusic(playlist.id, playlist.name, playlist.mixtape)) }, titleColor = Ink.tint, height = 52.dp,
                        leading = { Box(Modifier.size(48.dp).clip(RoundedCornerShape(5.dp)).background(Ink.fill.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                            if (playlist.mixtape) SymbolIcon(Symbol.CassetteAdd, Ink.tint, 26.dp) else SymbolIcon(Symbol.Plus, Ink.tint, 22.dp, weight = 2.2f) } })
                }
                // Card f23bb4f0: what the server lacks stands in its place, greyed, like an unavailable title in Apple Music.
                val rows = withMissing(tracks, playlist.missing)
                if (playlist.missing.isNotEmpty()) item { MissingHeader(state, playlist.missing) }
                // Olaf 05.10.2026: the playlist plays through in its order – server titles and the missing ones that
                // were found in the internet or loaded onto the phone; the rest is skipped.
                fun queue(): List<Track> = rows.mapNotNull { r -> if (r is Track) r else MissingHits.known(context, r as String) }
                itemsIndexed(rows, key = { i, r -> if (r is Track) "$i-${r.id}" else "m$i-$r" }) { _, r ->
                    if (r is Track) TrackRow(state, server, r) { val q = queue(); state.playback.play(server, q, q.indexOf(r)) }
                    else MissingTrackRow(state, r as String, playlist.id, onPlay = { found ->
                        val q = queue().let { list -> if (list.none { it.id == found.id }) list + found else list }
                        state.playback.play(server, q, q.indexOfFirst { it.id == found.id }.coerceAtLeast(0))
                    })
                }
            }
        }
        }
    }
}

@Composable
fun SearchScreen(state: AppState, server: MusicServer) {
    val ink = Ink
    var query by remember { mutableStateOf(state.searchFor ?: "") }
    var asked by remember { mutableStateOf(query) }
    LaunchedEffect(state.searchFor) { state.searchFor?.let { query = it; asked = it; state.searchFor = null } }
    LaunchedEffect(query) { delay(300); asked = query.trim() }
    // LiDio privat: "Im Netz" beside the own library, like Apple Music's "Apple Music | Deine Mediathek" (card 1843f577).
    val context = androidx.compose.ui.platform.LocalContext.current
    val engine = remember { Variant.engine(context) }
    // Without an own server (the web source is the library) everything is "Im Netz" – no scope to choose.
    val onlyWeb = server.kind == ServerKind.Web
    // Olaf 05.10.2026: one big library, no "Deine Mediathek | Im Netz" – the own server's hits come first, the internet adds
    // what we don't have yet (a title already on the server is left out there).
    val unified = engine != null && !onlyWeb
    val web = engine != null
    val prefs = remember { context.getSharedPreferences("netz", android.content.Context.MODE_PRIVATE) }
    var source by remember { mutableStateOf(runCatching { WebSource.valueOf(prefs.getString("quelle", "")!!) }.getOrDefault(WebSource.All)) }
    // Apple Music's search: one bar of scopes below the field – "Top-Treffer", then each kind on its own (card 324583b9).
    var scope by remember { mutableStateOf(SearchScope.Top) }
    val webServer = remember { Variant.webServer(context) }
    val everywhere = web && source == WebSource.All
    val musicSearch = web && (source == WebSource.Music || everywhere) && !isLink(asked)
    // "Alle Quellen": video and sound sites (titles) and Deezer, Spotify (playlists) besides the music source – side by side.
    val (more, _) = rememberLoad(asked, web, everywhere) {
        if (!web || !everywhere || asked.isEmpty() || isLink(asked) || engine == null) Triple(emptyList<WebHit>(), emptyList<WebHit>(), emptyList<RemoteList>())
        else kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            val yt = async { runCatching { engine.search(WebSource.Video, asked, 8) }.getOrDefault(emptyList()) }
            val sc = async { runCatching { engine.search(WebSource.Sounds, asked, 8) }.getOrDefault(emptyList()) }
            val dz = async { runCatching { DeezerPlaylists().search(asked).take(8) }.getOrDefault(emptyList()) }
            val sp = async { runCatching { state.playback.settings.publicSources().firstOrNull { it.source == Source.Spotify }?.search(asked)?.take(8) }.getOrNull().orEmpty() }
            Triple(yt.await(), sc.await(), dz.await() + sp.await())
        }
    }
    // Deezer / Spotify in "Im Netz": their playlists (card 146a2d74).
    val playlistSource = web && !isLink(asked) && (source == WebSource.Deezer || source == WebSource.Spotify)
    val (found, foundRetry) = rememberLoad(asked, source, playlistSource) {
        if (!playlistSource || asked.isEmpty()) emptyList() else when (source) {
            WebSource.Deezer -> DeezerPlaylists().search(asked)
            else -> state.playback.settings.publicSources().firstOrNull { it.source == Source.Spotify }?.search(asked)
                ?: throw ServerError(tr("Die Spotify-Suche braucht eine eigene, kostenlose Spotify-App (Client-ID) unter Mediathek → Server. Links gehen auch ohne: einfügen oder aus Spotify teilen."))
        }
    }
    // A Deezer or Spotify playlist link (pasted, or shared from their apps): open it right away.
    LaunchedEffect(asked) { Links.parse(asked)?.let { (src, id) -> query = ""; asked = ""; state.open(Route.RemotePage(RemoteList(src, id, ""))) } }
    val searchServer = if (musicSearch) webServer ?: server else server
    val (result, retry) = rememberLoad(searchServer, asked, web, musicSearch) {
        if (asked.isEmpty() || (web && !musicSearch)) SearchResult()
        // Without the internet the own library still answers.
        else runCatching { searchServer.search(asked) }.getOrElse { if (unified) SearchResult() else throw it }
    }
    val ownAsked = unified && asked.isNotEmpty() && !isLink(asked)
    val hideTwice = state.playback.settings.hideDuplicates
    val (own, _) = rememberLoad(server, asked, ownAsked, hideTwice) {
        if (!ownAsked) SearchResult() else runCatching { server.search(asked) }.getOrDefault(SearchResult())
            .let { if (hideTwice) it.copy(tracks = Duplicates.tracks(it.tracks)) else it }
    }
    val (ownLists, _) = rememberLoad(server, asked, ownAsked) { if (!ownAsked) emptyList() else runCatching { server.searchPlaylists(asked) }.getOrDefault(emptyList()) }
    val (lists, _) = rememberLoad(searchServer, asked, web, musicSearch) {
        if (asked.isEmpty() || (web && !musicSearch)) emptyList() else searchServer.searchPlaylists(asked)
    }
    // Video and sound sites and links: the loader's list; every title plays at once (streamed) and loads with ↓.
    val (webResult, webRetry) = rememberLoad(asked, source, web, musicSearch) {
        when {
            engine == null || !web || musicSearch || asked.isEmpty() -> "" to emptyList()
            isLink(asked) -> engine.open(if (asked.startsWith("www.")) "https://$asked" else asked)
            else -> "" to engine.search(source, asked)
        }
    }
    var sourcesOn by remember { mutableStateOf(state.playback.settings.publicSources().size) }
    val (remote, _) = rememberLoad(asked, sourcesOn, web) {
        if (asked.isEmpty() || web) emptyList() else state.playback.settings.publicSources().flatMap { src -> runCatching { src.search(asked) }.getOrElse { throw it } }
    }
    // Start page (card 81b01b4a): recent searches (a search counts when it stood for 2 s) and genres.
    val recentPrefs = remember { context.getSharedPreferences("suchen", android.content.Context.MODE_PRIVATE) }
    var recent by remember { mutableStateOf(recentPrefs.getString("zuletzt", "")!!.split("\n").filter { it.isNotBlank() }) }
    LaunchedEffect(asked) {
        if (asked.length < 3 || isLink(asked)) return@LaunchedEffect
        delay(2000)
        recent = (listOf(asked) + recent.filter { !it.equals(asked, true) }).take(10)
        recentPrefs.edit().putString("zuletzt", recent.joinToString("\n")).apply()
    }
    val genreServer = if (web) webServer ?: server else server
    val (genres, _) = rememberCachedLoad("${state.account?.id}:${genreServer.kind}:genres", genreServer) { genreServer.genres() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = chromePadding()) {
        largeTitle(tr("Suchen"))
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(ink.fill.copy(alpha = if (ink.dark) 0.5f else 0.25f))
                .padding(horizontal = 8.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                SymbolIcon(Symbol.Search, ink.secondary, 18.dp)
                Box(Modifier.weight(1f).padding(start = 6.dp)) {
                    if (query.isEmpty()) Label(if (web) tr("Interpreten, Alben, Titel oder Link") else tr("Interpreten, Alben, Titel, Playlists"), 17f, color = ink.secondary)
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = style(17f, color = ink.label), cursorBrush = SolidColor(ink.tint),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Suchbegriff" })
                }
                if (query.isNotEmpty()) Box(Modifier.clickable(role = Role.Button, onClickLabel = tr("Löschen")) { query = "" }) { SymbolIcon(Symbol.Close, ink.secondary, 16.dp) }
            }
        }
        if (!isLink(asked) && (web || asked.isNotEmpty())) item {
            ScopeBar(scope, if (asked.isEmpty() || (web && !musicSearch)) listOf(SearchScope.Top) else SearchScope.entries, if (web) source else null,
                onSource = { source = it; prefs.edit().putString("quelle", it.name).apply() }) { scope = it }
        }
        if (asked.isEmpty() && query.isEmpty()) searchStart(state, genreServer, recent, genres.value.orEmpty(), onRecent = { query = it; asked = it },
            onClear = { recent = emptyList(); recentPrefs.edit().remove("zuletzt").apply() })
        else if (playlistSource) {
            if (asked.isNotEmpty()) loading(found, foundRetry) { lists ->
                if (lists.isEmpty()) item { Label(tr("Bei {label} keine Playlist zu „{asked}“.", "label" to source.label, "asked" to asked), 17f, color = ink.secondary, modifier = Modifier.padding(16.dp)) }
                else item { SectionHeader(tr("Playlists")) }
                items(lists, key = { "r" + it.source + it.id }) { p ->
                    ListRow(p.name, listOfNotNull(p.owner.takeIf { it.isNotEmpty() }, if (p.count > 0) tr("{count} Titel", "count" to p.count) else null).joinToString(" · "),
                        onClick = { state.open(Route.RemotePage(p)) }, height = 68.dp,
                        leading = { Cover(p.cover, Modifier.size(56.dp), 6.dp) }, trailing = { Chevron() })
                }
            }
        }
        else if (web && !musicSearch) webSearch(state, asked, source, webResult, webRetry)
        else if (asked.isNotEmpty()) loading(result, retry) { netFound ->
            val playlists = lists.value.orEmpty()
            val top = scope == SearchScope.Top
            val mine = own.value ?: SearchResult()
            val myLists = ownLists.value.orEmpty()
            // What the own server has is shown once – as ours; the internet's copy of it is left out.
            val found = SearchResult(
                netFound.artists.filter { a -> mine.artists.none { Matcher.normal(it.name) == Matcher.normal(a.name) } },
                netFound.albums.filter { a -> mine.albums.none { Matcher.similar(it.title, a.title) >= 0.9 && Matcher.artistScore(a.artist, it.artist) >= 0.9 } },
                netFound.tracks.filter { t -> mine.tracks.none { Matcher.score(Wanted(t.title, t.artist, seconds = t.duration), it) >= 0.9 } })
            if (mine.tracks.isNotEmpty() && (top || scope == SearchScope.Titel)) {
                val shown = if (top) mine.tracks.take(4) else mine.tracks
                item { SectionHeader(tr("In deiner Mediathek")) }
                itemsIndexed(shown, key = { _, t -> "mt" + t.id }) { i, track -> TrackRow(state, server, track) { state.playback.play(server, shown, i) } }
            }
            if (mine.albums.isNotEmpty() && (top || scope == SearchScope.Alben)) {
                if (mine.tracks.isEmpty()) item { SectionHeader(tr("In deiner Mediathek")) }
                items(if (top) mine.albums.take(3) else mine.albums, key = { "mb" + it.id }) { album ->
                    ListRow(album.title, listOfNotNull(album.artist.ifEmpty { null }, album.year?.toString()).joinToString(" · "),
                        onClick = { state.open(Route.AlbumPage(album.id)) }, height = 68.dp,
                        leading = { Cover(server.cover(album.coverId, 160), Modifier.size(56.dp), 6.dp,
                            fallback = if (album.coverId == null) ({ server.albumCoverFallback(album.id, 160) }) else null) }, trailing = { Chevron() })
                }
            }
            if (myLists.isNotEmpty() && (top || scope == SearchScope.Playlists)) {
                if (mine.tracks.isEmpty() && mine.albums.isEmpty()) item { SectionHeader(tr("In deiner Mediathek")) }
                items(if (top) myLists.take(3) else myLists, key = { "mp" + it.id }) { p ->
                    ListRow(p.name, tr("{trackCount} Titel", "trackCount" to p.trackCount), onClick = { state.open(Route.PlaylistPage(p.id, p.name)) },
                        height = 68.dp, leading = { Cover(server.playlistCover(p, 160), Modifier.size(56.dp), 6.dp) }, trailing = { Chevron() })
                }
            }
            if (mine.artists.isNotEmpty() && (top || scope == SearchScope.Interpreten)) {
                if (mine.tracks.isEmpty() && mine.albums.isEmpty() && myLists.isEmpty()) item { SectionHeader(tr("In deiner Mediathek")) }
                items(if (top) mine.artists.take(2) else mine.artists, key = { "ma" + it.id }) { artist ->
                    ListRow(artist.name, onClick = { state.open(Route.ArtistPage(artist.id, artist.name)) }, height = 60.dp,
                        leading = { Cover(server.cover(artist.coverId, 120), Modifier.size(48.dp), 24.dp) }, trailing = { Chevron() })
                }
            }
            val nothingOwn = mine.tracks.isEmpty() && mine.albums.isEmpty() && mine.artists.isEmpty() && myLists.isEmpty()
            if (nothingOwn && found.artists.isEmpty() && found.albums.isEmpty() && found.tracks.isEmpty() && playlists.isEmpty() && !lists.loading)
                item { Label(if (everywhere) tr("Nirgends etwas zu „{asked}“.", "asked" to asked) else if (web) tr("Bei {MUSIC} nichts zu „{asked}“.", "MUSIC" to Variant.MUSIC, "asked" to asked) else tr("Auf deinem Server nichts zu „{asked}“.", "asked" to asked), 17f, color = ink.secondary, modifier = Modifier.padding(16.dp)) }
            val tracks = if (top) found.tracks.take(6) else found.tracks
            if ((top || scope == SearchScope.Titel) && tracks.isNotEmpty()) {
                item { SectionHeader(tr("Titel"), if (top && found.tracks.size > 6) tr("Alle anzeigen") else null) { scope = SearchScope.Titel } }
                itemsIndexed(tracks, key = { _, t -> "t" + t.id }) { i, track -> TrackRow(state, searchServer, track, subtitle = if (everywhere) "${track.artist} · ${Variant.MUSIC}" else track.artist) { state.playback.play(searchServer, tracks, i) } }
            }
            more.value?.let { (yt, sc, _) ->
                val others = (if (top) yt.take(3) + sc.take(3) else yt + sc).map { it to it.asTrack() }
                if ((top || scope == SearchScope.Titel) && others.isNotEmpty()) {
                    if (tracks.isEmpty()) item { SectionHeader(tr("Titel")) }
                    items(others, key = { "o" + it.second.id }) { (hit, t) ->
                        TrackRow(state, searchServer, t, subtitle = "${t.artist} · ${hit.source.label}") { state.playback.play(searchServer, others.map { it.second }, others.indexOfFirst { it.second.id == t.id }) }
                    }
                }
            }
            val albums = if (top) found.albums.take(4) else found.albums
            if ((top || scope == SearchScope.Alben) && albums.isNotEmpty()) {
                item { SectionHeader(tr("Alben"), if (top && found.albums.size > 4) tr("Alle anzeigen") else null) { scope = SearchScope.Alben } }
                items(albums, key = { "b" + it.id }) { album ->
                    ListRow(album.title, listOfNotNull(album.artist.ifEmpty { null }, album.year?.toString()).joinToString(" · "),
                        onClick = { state.open(Route.AlbumPage(album.id, web = searchServer.kind == ServerKind.Web)) }, height = 68.dp,
                        leading = { Cover(searchServer.cover(album.coverId, 160), Modifier.size(56.dp), 6.dp,
                            fallback = if (album.coverId == null) ({ searchServer.albumCoverFallback(album.id, 160) }) else null) }, trailing = { Chevron() })
                }
            }
            if (top || scope == SearchScope.Playlists) {
                if (web) {
                    val shown = if (top) playlists.take(4) else playlists
                    if (shown.isNotEmpty()) item { SectionHeader(tr("Playlists"), if (top && playlists.size > 4) tr("Alle anzeigen") else null) { scope = SearchScope.Playlists } }
                    items(shown, key = { "p" + it.id }) { p ->
                        ListRow(p.name, listOfNotNull(if (p.trackCount > 0) tr("{trackCount} Titel", "trackCount" to p.trackCount) else "Playlist", if (everywhere) Variant.MUSIC else null).joinToString(" · "),
                            onClick = { state.open(Route.PlaylistPage(p.id, p.name, web = true)) },
                            height = 68.dp, leading = { Cover(searchServer.cover(p.coverId, 160), Modifier.size(56.dp), 6.dp) }, trailing = { Chevron() })
                    }
                    val remoteLists = more.value?.third.orEmpty().let { if (top) it.take(4) else it }
                    if (remoteLists.isNotEmpty() && shown.isEmpty()) item { SectionHeader(tr("Playlists")) }
                    items(remoteLists, key = { "r" + it.source + it.id }) { p ->
                        ListRow(p.name, listOfNotNull(p.owner.takeIf { it.isNotEmpty() }, if (p.count > 0) tr("{count} Titel", "count" to p.count) else null, p.source.label).joinToString(" · "),
                            onClick = { state.open(Route.RemotePage(p)) }, height = 68.dp, leading = { Cover(p.cover, Modifier.size(56.dp), 6.dp) }, trailing = { Chevron() })
                    }
                } else publicPlaylists(state, server, asked, if (top) playlists.take(4) else playlists, if (sourcesOn > 0) remote else null) {
                    state.playback.settings.deezer = true; sourcesOn = state.playback.settings.publicSources().size
                }
            }
            val artists = if (top) found.artists.take(3) else found.artists
            if ((top || scope == SearchScope.Interpreten) && artists.isNotEmpty()) {
                item { SectionHeader(tr("Interpreten"), if (top && found.artists.size > 3) tr("Alle anzeigen") else null) { scope = SearchScope.Interpreten } }
                items(artists, key = { "a" + it.id }) { artist ->
                    ListRow(artist.name, onClick = { state.open(Route.ArtistPage(artist.id, artist.name, web = searchServer.kind == ServerKind.Web)) }, height = 60.dp,
                        leading = { Cover(searchServer.cover(artist.coverId, 120), Modifier.size(48.dp), 24.dp) }, trailing = { Chevron() })
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** The scopes of a search, as in Music on iOS. */
enum class SearchScope(val label: String) { Top("Top-Treffer"), Titel(tr("Titel")), Alben(tr("Alben")), Playlists(tr("Playlists")), Interpreten(tr("Interpreten")) }

/** The bar below the search field: in "Im Netz" first the source as a pull-down button (HIG), then the scopes as capsules. */
@Composable
private fun ScopeBar(scope: SearchScope, scopes: List<SearchScope>, source: WebSource?, onSource: (WebSource) -> Unit, onScope: (SearchScope) -> Unit) {
    val ink = Ink
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (source != null) Box {
            Row(Modifier.clip(RoundedCornerShape(16.dp)).background(ink.fill.copy(alpha = if (ink.dark) 0.5f else 0.25f))
                .clickable(role = Role.DropdownList, onClickLabel = tr("Quelle wählen")) { menu = true }.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(source.color))
                Label(source.label, 15f, 600, modifier = Modifier.padding(start = 6.dp, end = 4.dp))
                SymbolIcon(Symbol.ChevronDown, ink.secondary, 12.dp, weight = 2.6f)
            }
            if (menu) androidx.compose.ui.window.Popup(onDismissRequest = { menu = false }, offset = androidx.compose.ui.unit.IntOffset(0, 110),
                properties = androidx.compose.ui.window.PopupProperties(focusable = true)) {
                Column(Modifier.width(230.dp).shadow(24.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
                    .background(if (ink.dark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7))) {
                    val all = WebSource.entries.filter { it.searchable }
                    all.forEachIndexed { i, s ->
                        ListRow(s.label, separator = i < all.lastIndex, onClick = { menu = false; onSource(s) },
                            leading = { Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) { if (s == source) SymbolIcon(Symbol.Check, ink.label, 16.dp, weight = 2.4f) } },
                            trailing = { Box(Modifier.size(8.dp).clip(CircleShape).background(s.color)) })
                    }
                }
            }
        }
        if (scopes.size > 1) scopes.forEach { s ->
            val on = s == scope
            Box(Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) ink.tint else ink.fill.copy(alpha = if (ink.dark) 0.5f else 0.25f))
                .clickable(role = Role.Tab) { onScope(s) }.padding(horizontal = 14.dp, vertical = 6.dp)) {
                Label(s.label, 15f, 600, if (on) Color.White else ink.label)
            }
        }
    }
}

@Composable
fun ServersScreen(state: AppState) {
    val ink = Ink
    var all by remember(state.account) { mutableStateOf(state.accounts.all()) }
    Column(Modifier.fillMaxSize()) {
        NavBar(state)
        LazyColumn(Modifier.fillMaxSize().background(ink.grouped), contentPadding = chromePadding()) {
            largeTitle(tr("Einstellungen"), topInset = false)
            item { Label(tr("SERVER"), 13f, color = ink.secondary, modifier = Modifier.padding(start = 32.dp, top = 16.dp, bottom = 6.dp)) }
            item {
                Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(10.dp)).background(ink.card)) {
                    all.forEachIndexed { i, account ->
                        ListRow(account.name.ifEmpty { account.address }, if (account.kind == ServerKind.Local) tr("{count} Ordner", "count" to (LocalLibrary.decode(account.address).size))
                            else "${account.kind.label} · ${account.user}", height = 60.dp,
                            separator = i < all.lastIndex, onClick = { state.use(account) },
                            leading = { SymbolIcon(Symbol.Server, ink.tint, 24.dp) },
                            trailing = { if (account.id == state.account?.id) SymbolIcon(Symbol.Check, ink.tint, 18.dp, weight = 2.4f) })
                    }
                }
            }
            state.account?.takeIf { it.kind != ServerKind.Local }?.let { account -> item { AddressSettings(state, account) } }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 20.dp).clip(RoundedCornerShape(10.dp)).background(ink.card)) {
                    ListRow(tr("Server hinzufügen"), onClick = { state.adding = true }, titleColor = ink.tint, separator = true)
                    ListRow(tr("Ordner auf diesem Gerät hinzufügen"), onClick = { state.pickFolder() }, titleColor = ink.tint, separator = true)
                    if (state.account?.kind == ServerKind.Local) {
                        val scope = androidx.compose.runtime.rememberCoroutineScope()
                        val context = androidx.compose.ui.platform.LocalContext.current
                        ListRow(tr("Ordner neu durchsuchen"), onClick = { state.account?.let { a -> scope.launch { scanLocal(state, a) } } }, titleColor = ink.tint, separator = true)
                        LocalLibrary.decode(state.account?.address ?: "").forEach { folder ->
                            val name = android.net.Uri.decode(folder.lastPathSegment ?: "").substringAfterLast(':').ifEmpty { tr("Ordner") }
                            ListRow(name, tr("Ordner auf diesem Gerät"), height = 56.dp, trailing = {
                                Label(tr("Entfernen"), 15f, 400, Red, Modifier.clickable(role = Role.Button) {
                                    // Give the grant back to Android, then read what's left (or drop the source when nothing is).
                                    runCatching { context.contentResolver.releasePersistableUriPermission(folder, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                                    val account = state.account ?: return@clickable
                                    val rest = LocalLibrary.decode(account.address) - folder
                                    if (rest.isEmpty()) { state.accounts.remove(account.id); java.io.File(context.filesDir, "lokal/${account.id}.json").delete()
                                        state.account = state.accounts.active(); state.stacks.clear(); state.generation++; all = state.accounts.all() }
                                    else scope.launch { scanLocal(state, account.copy(address = LocalLibrary.encode(rest))) }
                                })
                            })
                        }
                    }
                    ListRow(tr("Diesen Server abmelden"), onClick = {
                        state.account?.let { state.accounts.remove(it.id) }
                        state.playback.controller?.stop()
                        all = state.accounts.all(); state.account = state.accounts.active(); state.stacks.clear(); state.generation++
                    }, titleColor = androidx.compose.ui.graphics.Color(0xFFFF3B30), separator = false)
                }
            }
            item {
                var wifi by remember { mutableStateOf(state.playback.settings.wifiBitrate) }
                var mobile by remember { mutableStateOf(state.playback.settings.mobileBitrate) }
                Label("AUDIOQUALITÄT (KBIT/S)", 13f, color = ink.secondary, modifier = Modifier.padding(start = 32.dp, bottom = 6.dp))
                Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(10.dp)).background(ink.card).padding(vertical = 6.dp)) {
                    for ((label, value, set) in listOf(Triple("WLAN", wifi, { v: Int -> wifi = v; state.playback.settings.wifiBitrate = v }),
                        Triple(tr("Mobile Daten"), mobile, { v: Int -> mobile = v; state.playback.settings.mobileBitrate = v }))) {
                        Label(label, 15f, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                        Segmented(Settings.BITRATES.map(Settings::label), Settings.BITRATES.indexOf(value).coerceAtLeast(0),
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) { set(Settings.BITRATES[it]) }
                    }
                }
                Label(tr("„Original“ spielt die Datei, wie sie auf dem Server liegt. Kleinere Werte lässt der Server umrechnen – das spart Datenvolumen."),
                    13f, color = ink.secondary, lines = 4, modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp, bottom = 20.dp))
            }
            item {
                var hide by remember { mutableStateOf(state.playback.settings.hideDuplicates) }
                Label(tr("MEDIATHEK"), 13f, color = ink.secondary, modifier = Modifier.padding(start = 32.dp, bottom = 6.dp))
                Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(10.dp)).background(ink.card)) {
                    ListRow(tr("Duplikate ausblenden"), tr("Gleicher Titel vom gleichen Interpreten nur einmal"), height = 60.dp,
                        trailing = { IosSwitch(hide, tr("Duplikate ausblenden")) { hide = it; state.playback.settings.hideDuplicates = it; state.generation++ } })
                    // Card 7ac89c11: lyrics from LRCLIB when the server has none.
                    var lyricsOnline by remember { mutableStateOf(state.playback.settings.lyricsOnline) }
                    ListRow(tr("Liedtexte aus dem Netz"), tr("Fehlt der Text auf dem Server, fragt LiDio LRCLIB"), height = 60.dp, separator = false,
                        trailing = { IosSwitch(lyricsOnline, tr("Liedtexte aus dem Netz")) { lyricsOnline = it; state.playback.settings.lyricsOnline = it } })
                }
                Label(tr("Für Liedtexte gehen Titel und Interpret an lrclib.net – frei, ohne Konto und Werbung."), 13f, color = ink.secondary, lines = 2,
                    modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp))
                Spacer(Modifier.height(20.dp))
            }
            item {
                Label(tr("HILFE"), 13f, color = ink.secondary, modifier = Modifier.padding(start = 32.dp, bottom = 6.dp))
                Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(10.dp)).background(ink.card)) {
                    ListRow(tr("Anleitung"), tr("Rundgang, Kapitel, PDF, Datenschutz"), height = 60.dp, separator = false, onClick = { state.open(Route.Guide) }, trailing = { Chevron() })
                }
                Spacer(Modifier.height(20.dp))
            }
            item {
                val w = state.winamp
                Label(tr("DARSTELLUNG"), 13f, color = ink.secondary, modifier = Modifier.padding(start = 32.dp, bottom = 6.dp))
                Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(10.dp)).background(ink.card)) {
                    // Card d894cc42: "Klassisch" or "Modern" (iOS 26) – independent of the Winamp view.
                    Label(tr("Erscheinungsbild"), 15f, modifier = Modifier.padding(start = 16.dp, top = 10.dp))
                    Segmented(listOf(tr("Klassisch"), tr("Modern")), if (state.modern) 1 else 0, Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        state.modern = it == 1; state.playback.settings.modern = it == 1
                    }
                    Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(ink.separator))
                    // Language: like the system or chosen here – takes effect at the next start.
                    Label(tr("Sprache"), 15f, modifier = Modifier.padding(start = 16.dp, top = 10.dp))
                    val i18n = io.github.veritasx1.lidio.i18n.I18n
                    val codes = listOf<String?>(null) + i18n.LANGUAGES.keys
                    Segmented(listOf(tr("System")) + i18n.LANGUAGES.values, codes.indexOf(i18n.chosen).coerceAtLeast(0), Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        i18n.chosen = codes[it]; state.notice = tr("Die Sprache wechselt beim nächsten Start von LiDio.")
                    }
                    Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(ink.separator))
                    val context = androidx.compose.ui.platform.LocalContext.current
                    var allowed by remember { mutableStateOf(LockScreen.allowed(context)) }
                    LaunchedEffect(Unit) { while (true) { allowed = LockScreen.allowed(context); kotlinx.coroutines.delay(1000) } }
                    fun permission() = runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + context.packageName))) }
                    ListRow(tr("Winamp auf dem Sperrbildschirm"), if (w.lockScreen && !allowed) tr("Erlaubnis fehlt – hier antippen") else tr("Wenn Musik läuft und Winamp offen war"),
                        height = 60.dp, titleColor = ink.label, onClick = if (w.lockScreen && !allowed) ({ permission() }) else null,
                        trailing = { IosSwitch(w.lockScreen, tr("Winamp auf dem Sperrbildschirm")) { on ->
                            w.lockScreen = on; w.save(); if (on && !LockScreen.allowed(context)) permission() } })
                    ListRow(tr("Winamp-Skin"), height = 48.dp, separator = false, onClick = { state.open(Route.Skins) }, trailing = {
                        Label(SkinLibrary.nameOf(state.accounts.context, w.skin), 17f, color = ink.secondary, modifier = Modifier.padding(end = 6.dp)); Chevron() })
                }
                Label(tr("„Modern“ sieht aus wie Musik unter iOS 26: Glas, schwebende Leiste, Suche als eigener Knopf. ") +
                    tr("Der Blitz unten in „Jetzt läuft“ wechselt in die Winamp-Ansicht, der Blitz im Winamp-Fenster wieder zurück. LiDio merkt sich, welche Ansicht du zuletzt hattest."),
                    13f, color = ink.secondary, lines = 3, modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp, bottom = 20.dp))
            }
            item { OfflineSettings(state) }
            item { PublicSettings(state) }
            item { Label(tr("LiDio spricht nur mit den Servern, die du hier einträgst. Passwörter bleiben verschlüsselt auf diesem Gerät."),
                13f, color = ink.secondary, lines = 4, modifier = Modifier.padding(horizontal = 32.dp)) }
        }
    }
}

/** Both addresses of the server in use, and which one is in use now. */
@Composable
fun AddressSettings(state: AppState, account: Account) {
    val ink = Ink
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var home by remember(account.id) { mutableStateOf(account.address) }
    var away by remember(account.id) { mutableStateOf(account.external) }
    fun save() {
        val clean = { a: String -> a.trim().trimEnd('/').let { if (it.isEmpty() || it.startsWith("http")) it else "https://$it" } }
        val next = account.copy(address = clean(home).ifEmpty { account.address }, external = clean(away))
        if (next != account) { state.accounts.save(next); state.account = next; scope.launch { state.resolve() } }
    }
    Label(tr("ADRESSEN"), 13f, color = ink.secondary, modifier = Modifier.padding(start = 32.dp, top = 20.dp, bottom = 6.dp))
    Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(10.dp)).background(ink.card)) {
        for ((label, value, set) in listOf(Triple(tr("Im WLAN"), home, { v: String -> home = v }), Triple("Unterwegs", away, { v: String -> away = v }))) {
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(label, 15f, modifier = Modifier.width(96.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Label("optional", 15f, color = ink.tertiary)
                    BasicTextField(value, set, singleLine = true, textStyle = style(15f, color = ink.label), cursorBrush = SolidColor(ink.tint),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { save() }),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = tr("Adresse {label}", "label" to label) }
                            .onFocusChanged { if (!it.isFocused) save() })
                }
                if (Reach.normal(state.address) == Reach.normal(value) && value.isNotEmpty())
                    Label(tr("in Benutzung"), 13f, 600, Green, Modifier.padding(start = 8.dp))
            }
        }
    }
    Label(tr("Im WLAN nimmt LiDio die schnelle Adresse, sonst die für unterwegs – beim Start und bei jedem Netzwechsel, auch während der Wiedergabe."),
        13f, color = ink.secondary, lines = 3, modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp))
}

/** "Lieblingstitel": everything marked with ★, from the server (Emby/Jellyfin favourites, Navidrome stars). */
@Composable
fun FavoritesScreen(state: AppState, server: MusicServer) {
    val hide = state.playback.settings.hideDuplicates
    val (load, retry) = rememberCachedLoad("${state.account?.id}:${server.kind}:favoriten:$hide", server, state.generation) {
        server.favorites().let { if (hide) Duplicates.tracks(it) else it } }
    Column(Modifier.fillMaxSize()) {
        NavBar(state)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = chromePadding()) {
            largeTitle(tr("Lieblingstitel"), topInset = false)
            loading(load, retry) { all ->
                val tracks = all.filter { state.playback.isFavorite(it) }
                if (tracks.isEmpty()) item {
                    Label(tr("Noch keine Lieblingstitel. Tippe in „Jetzt läuft“ auf ★ oder halte einen Titel gedrückt → Favorit."), 15f,
                        color = Ink.secondary, lines = 3, modifier = Modifier.padding(16.dp))
                } else item { PlayButtons(state, server, tracks) }
                itemsIndexed(tracks, key = { _, t -> "f" + t.id }) { i, t -> TrackRow(state, server, t) { state.playback.play(server, tracks, i) } }
            }
        }
    }
}

/** A genre/mood: its albums (own library) or playlists (web source), as tiles. */
@Composable
fun GenreScreen(state: AppState, server: MusicServer, genre: Genre) {
    val (albums, retry) = rememberCachedLoad("${state.account?.id}:${server.kind}:genre:${genre.id}", server, genre.id) { server.genreAlbums(genre) }
    val (lists, _) = rememberLoad(server, genre.id) { server.genrePlaylists(genre) }
    Column(Modifier.fillMaxSize()) {
        NavBar(state)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = chromePadding()) {
            largeTitle(GenreNames.local(genre.name), topInset = false)
            // Card 8fa32042: a mood from the web has only playlists – then they fill the page as a grid (like Apple's
            // category pages) instead of one row to the right with nothing below it.
            val onlyLists = albums.value?.isEmpty() == true || server.kind == ServerKind.Web
            lists.value?.takeIf { it.isNotEmpty() }?.let {
                item { SectionHeader(tr("Playlists")) }
                if (onlyLists) playlistGrid(state, server, it) else item { PlaylistRow(state, server, it) }
            }
            loading(albums, retry) { a -> if (a.isNotEmpty()) { item { SectionHeader(tr("Alben")) }; albumGrid(state, server, a) } }
        }
    }
}

/** Search before typing, like Music on iOS: what was searched lately, then genres and moods as coloured tiles. */
fun LazyListScope.searchStart(state: AppState, server: MusicServer, recent: List<String>, genres: List<Genre>, onRecent: (String) -> Unit, onClear: () -> Unit) {
    if (recent.isNotEmpty()) {
        item { SectionHeader(tr("Zuletzt gesucht"), tr("Löschen"), onClear) }
        items(recent, key = { "r$it" }) { q ->
            ListRow(q, onClick = { onRecent(q) }, height = 44.dp, titleColor = Ink.tint,
                leading = { Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) { SymbolIcon(Symbol.Search, Ink.secondary, 16.dp) } })
        }
    }
    val shown = GenreNames.shown(genres)
    if (shown.isNotEmpty()) {
        item { SectionHeader(tr("Kategorien entdecken")) }
        items(shown.chunked(2), key = { row -> "k" + row.joinToString { it.id } }) { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { g -> GenreTile(state, server, g, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** A category tile like Apple Music (card ef701805, Olaf's template): a picture from the category over the whole tile, toned in
 *  the tile's colour (grey picture, colour on top in "colour" blending – the duotone look), the name at the bottom left. The
 *  picture changes every 10 hours, to another cover of the category. */
@Composable
private fun GenreTile(state: AppState, server: MusicServer, g: Genre, modifier: Modifier) {
    val color = g.color?.let { Color(it.toInt()) } ?: genreColor(g.name)
    val slot = (System.currentTimeMillis() / (10 * 3_600_000L)).toInt()
    val (picture, _) = rememberCachedLoad("${server.kind}:kachel:${g.id}:$slot", server, g.id, slot) { server.genreCover(g, slot + (g.id.hashCode() and 0xff)) }
    val grey = remember { androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(0f) }) }
    Box(modifier.aspectRatio(1.6f).clip(RoundedCornerShape(12.dp)).background(color)
        .clickable(role = Role.Button) { state.open(Route.GenrePage(g, web = server.kind == ServerKind.Web)) }) {
        picture.value?.let { url ->
            Box(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(color, blendMode = androidx.compose.ui.graphics.BlendMode.Color)
                    drawRect(color.copy(alpha = 0.18f))
                }) {
                Cover(url, Modifier.fillMaxSize(), 0.dp, colorFilter = grey)
            }
        }
        // A soft shade at the bottom keeps the name readable on any picture.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.5f))))
        Label(GenreNames.local(g.name), 19f, 800, Color.White, lines = 2, shadow = OnArt,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 10.dp, end = 12.dp))
    }
}

/** A steady colour per genre name (Apple's tiles are colourful). */
fun genreColor(name: String): Color {
    val palette = listOf(0xFFE5484D, 0xFFF76B15, 0xFFFFB224, 0xFF30A46C, 0xFF12A594, 0xFF0090FF, 0xFF3E63DD, 0xFF8E4EC6, 0xFFD6409F, 0xFF978365)
    return Color(palette[(name.lowercase().hashCode() and 0x7fffffff) % palette.size])
}

/** A mix tile like Apple's: a colour gradient with the mix's name. */
@Composable
fun MixTile(title: String, subtitle: String, colors: List<Color>, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(colors)).clickable(role = Role.Button, onClick = onClick)
        .padding(12.dp), verticalArrangement = Arrangement.Bottom) {
        Label(title, 20f, 700, Color.White, lines = 2)
        Label(subtitle, 13f, color = Color.White.copy(alpha = 0.85f), lines = 2)
    }
}

/** A mix's page: its titles, play/shuffle, and "Neu mischen" (a new selection). */
@Composable
fun MixScreen(state: AppState, server: MusicServer, kind: String) {
    var round by remember { mutableStateOf(0) }
    val (load, retry) = rememberLoad(server, kind, round) {
        when (kind) {
            "favoriten" -> {
                val favs = server.favorites().shuffled()
                val similar = favs.take(3).flatMap { f -> runCatching { server.similar(f, 15) }.getOrDefault(emptyList()) }
                (favs.take(25) + similar).distinctBy { it.id }.shuffled().take(50)
            }
            else -> server.discover(50)
        }
    }
    Column(Modifier.fillMaxSize()) {
        NavBar(state, trailing = { Label(tr("Neu mischen"), 17f, color = Ink.tint, modifier = Modifier.clickable(role = Role.Button) { round++ }) })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = chromePadding()) {
            largeTitle(if (kind == "favoriten") tr("Lieblings-Mix") else tr("Neu entdecken"), topInset = false)
            loading(load, retry) { tracks ->
                if (tracks.isEmpty()) item {
                    Label(if (kind == "favoriten") tr("Markiere Titel mit ★ – daraus entsteht dein Mix.") else tr("Hier ist gerade nichts Ungehörtes."), 15f,
                        color = Ink.secondary, lines = 3, modifier = Modifier.padding(16.dp))
                } else item { PlayButtons(state, server, tracks) }
                itemsIndexed(tracks, key = { _, t -> "x" + t.id }) { i, t -> TrackRow(state, server, t) { state.playback.play(server, tracks, i) } }
            }
        }
    }
}


/** Card eea4ee65: "Musik hinzufügen" to a playlist – search the own library (or pick from the favourites and what came in lately);
 *  ＋ adds the title at once and turns into a tick. */
@Composable
fun AddMusicScreen(state: AppState, shown: MusicServer, route: Route.AddMusic) {
    val ink = Ink
    // Always the own server – LiDio privat may be on its internet fallback for a moment, and a title can only go into an own
    // playlist from the own library (Olaf: „Musik lässt sich nicht hinzufügen – es gibt sogar eine Meldung“).
    val server = remember(state.account, state.address) {
        state.account?.let { a -> if (a.kind == ServerKind.Local || a.kind == ServerKind.Web) null else state.serverFor(a.copy(address = state.address.ifEmpty { a.address })) } ?: shown
    }
    var query by remember { mutableStateOf("") }
    var asked by remember { mutableStateOf("") }
    LaunchedEffect(query) { delay(300); asked = query.trim() }
    val added = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    val (found, _) = rememberLoad(server, asked) {
        (if (asked.isEmpty()) (runCatching { server.favorites() }.getOrDefault(emptyList()) + runCatching { server.tracks(40, 0) }.getOrDefault(emptyList())).distinctBy { it.id }.take(60)
        else server.search(asked).tracks).let { if (state.playback.settings.hideDuplicates) Duplicates.tracks(it) else it }
    }
    Column(Modifier.fillMaxSize()) {
        NavBar(state, trailing = { Label(tr("Fertig"), 17f, 600, ink.tint, Modifier.clickable(role = Role.Button) { state.generation++; state.back() }) })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = chromePadding()) {
            largeTitle(tr("Musik hinzufügen"), topInset = false)
            item { Label(tr("zu „{name}“", "name" to route.name), 15f, color = ink.secondary, modifier = Modifier.padding(horizontal = 16.dp)) }
            item {
                Row(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(ink.grouped).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    SymbolIcon(Symbol.Search, ink.secondary, 18.dp); Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Label(tr("Interpreten, Alben, Titel"), 17f, color = ink.tertiary)
                        androidx.compose.foundation.text.BasicTextField(query, { query = it }, singleLine = true, textStyle = style(17f, color = ink.label),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(ink.tint), modifier = Modifier.fillMaxWidth().semantics { contentDescription = tr("Suchen") })
                    }
                }
            }
            if (asked.isEmpty()) item { SectionHeader(tr("Lieblingstitel und zuletzt hinzugefügt")) }
            found.value?.let { tracks ->
                items(tracks, key = { "add" + it.id }) { t ->
                    val done = t.id in added
                    ListRow(t.title, t.artist, height = 60.dp, leading = { Cover(server.cover(t, 120), Modifier.size(48.dp), 5.dp) },
                        trailing = {
                            Box(Modifier.size(36.dp).clip(CircleShape).clickable(enabled = !done, role = Role.Button) {
                                added += t.id
                                addScope.launch {
                                    // One quiet second try (a moment without the network) before the message.
                                    val ok = withContext(Dispatchers.IO) { server.addToPlaylist(route.playlistId, listOf(t)) || run { Thread.sleep(800); server.addToPlaylist(route.playlistId, listOf(t)) } }
                                    if (!ok) { added -= t.id; state.notice = tr("„{title}“ ließ sich nicht hinzufügen.", "title" to t.title) }
                                }
                            }.semantics { contentDescription = if (done) tr("Hinzugefügt") else tr("Hinzufügen") }, contentAlignment = Alignment.Center) {
                                if (done) SymbolIcon(Symbol.Check, ink.secondary, 18.dp, weight = 2.4f)
                                else if (route.mixtape) SymbolIcon(Symbol.CassetteAdd, ink.tint, 26.dp) else SymbolIcon(Symbol.Plus, ink.tint, 22.dp, weight = 2.2f)
                            }
                        })
                }
            }
        }
    }
}

private val addScope = kotlinx.coroutines.MainScope()
