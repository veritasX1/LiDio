package io.github.veritasx1.lidio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.launch

// ---------- building blocks ----------

/** The navigation bar of a pushed page: "‹ Mediathek" in tint on the left, the title small in the middle. */
@Composable
fun NavBar(state: AppState, title: String = "", trailing: (@Composable () -> Unit)? = null) {
    val ink = Ink
    val stack = state.stack()
    val previous = stack.getOrNull(stack.size - 2)?.let(::titleOf) ?: state.tab.label
    // iOS 26: back is a round glass button (no text), toolbar buttons sit in glass too.
    if (LocalModern.current) {
        Box(Modifier.fillMaxWidth().background(ink.background).windowInsetsPadding(WindowInsets.statusBars).height(56.dp)) {
            Box(Modifier.align(Alignment.CenterStart).padding(start = 16.dp)) { GlassCircle(Symbol.ChevronLeft, "Zurück zu $previous") { state.back() } }
            Label(title, 17f, 600, modifier = Modifier.align(Alignment.Center).width(180.dp), align = TextAlign.Center)
            trailing?.let { Box(Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).height(44.dp).glass(RoundedCornerShape(22.dp), 6.dp)
                .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { it() } }
        }
        return
    }
    Box(Modifier.fillMaxWidth().background(ink.background).windowInsetsPadding(WindowInsets.statusBars).height(44.dp)) {
        Row(Modifier.align(Alignment.CenterStart).clickable(role = Role.Button, onClickLabel = "Zurück") { state.back() }
            .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            SymbolIcon(Symbol.ChevronLeft, ink.tint, 22.dp, weight = 2.4f)
            Label(previous, 17f, 400, ink.tint, Modifier.padding(start = 2.dp).width(120.dp))
        }
        Label(title, 17f, 600, modifier = Modifier.align(Alignment.Center).width(180.dp), align = TextAlign.Center)
        trailing?.let { Box(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) { it() } }
    }
}

fun titleOf(route: Route): String = when (route) {
    Route.Library -> "Mediathek"; Route.Artists -> "Interpreten"; Route.Albums -> "Alben"; Route.Tracks -> "Titel"
    Route.Playlists -> "Playlists"; Route.Servers -> "Server"; Route.Downloaded -> "Geladen"; Route.Import -> "Importieren"; Route.Skins -> "Skins"; Route.Web -> "Aus dem Netz"; Route.Favorites -> "Lieblingstitel"; is Route.GenrePage -> route.genre.name; is Route.MixPage -> if (route.kind == "favoriten") "Lieblings-Mix" else "Neu entdecken"; Route.Guide -> "Anleitung"
    is Route.RemotePage -> route.list.name; is Route.ImportWith -> "Importieren"
    is Route.ArtistPage -> route.name; is Route.AlbumPage -> "Album"; is Route.PlaylistPage -> route.name
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
fun Waiting() = Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { Label("Wird geladen …", 15f, color = Ink.secondary) }

@Composable
fun Failed(text: String, retry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SymbolIcon(Symbol.Missing, Ink.secondary, 40.dp)
        Label(text, 17f, 500, modifier = Modifier.padding(top = 12.dp), lines = 3, align = TextAlign.Center)
        Label("Erneut versuchen", 17f, 400, Ink.tint, Modifier.padding(top = 12.dp).clickable(role = Role.Button, onClick = retry))
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
        .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = if (state != null) ({ menu = true }) else null, onLongClickLabel = "Mehr")
        .semantics { contentDescription = "${album.title} von ${album.artist}" }) {
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
             subtitle: String? = if (number == null) track.artist else null, onClick: () -> Unit) {
    val ink = Ink
    val playing = state.playback.current?.id == track.id
    var menu by remember { mutableStateOf(false) }
    if (menu) TrackMenu(state, server, track) { menu = false }
    // iOS: swipe a title to the right → "Als Nächstes", to the left → "Zuletzt spielen".
    var swipe by remember(track.id) { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val limit = with(androidx.compose.ui.platform.LocalDensity.current) { 96.dp.toPx() }
    Box(Modifier.fillMaxWidth().pointerInput(track.id) {
        detectHorizontalDragGestures(onDragEnd = {
            if (swipe > limit) { state.playback.playNext(server, track); state.notice = "„${track.title}“ kommt als Nächstes." }
            else if (swipe < -limit) { state.playback.addToQueue(server, track); state.notice = "„${track.title}“ kommt zuletzt." }
            swipe = 0f
        }, onDragCancel = { swipe = 0f }) { _, dx -> swipe = (swipe + dx).coerceIn(-limit * 1.4f, limit * 1.4f) }
    }) {
        if (swipe != 0f) Row(Modifier.matchParentSize().background(if (swipe > 0) Color(0xFF5856D6) else Color(0xFFFF9500)).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (swipe > 0) Arrangement.Start else Arrangement.End) {
            SymbolIcon(if (swipe > 0) Symbol.PlayNext else Symbol.PlayLast, Color.White, 22.dp)
        }
        Box(Modifier.graphicsLayer { translationX = swipe }.background(if (swipe != 0f) ink.background else Color.Transparent)) {
    ListRow(track.title, subtitle,
        onClick = onClick, onLongClick = { menu = true }, titleColor = if (playing) ink.tint else ink.label, height = if (showArt) 60.dp else 48.dp,
        leading = {
            if (showArt) Cover(server.cover(track, 120), Modifier.size(48.dp), 5.dp)
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
                // Neither on the server nor on the phone – out in the internet: a cloud (Olaf 05.10.2026).
                server.kind == ServerKind.Web ->
                    SymbolIcon(Symbol.Cloud, ink.tertiary, 15.dp, modifier = Modifier.padding(end = 8.dp).semantics { contentDescription = "Im Internet" })
                key != null && key in Offline.live -> Box(Modifier.padding(end = 8.dp)) {
                    val p = Offline.live[key]
                    if (p == null) WaitingRing(18.dp) else ProgressRing(p, ink.tint, 18.dp)
                }
                // Olaf 05.10.2026: where the title is – a small phone (on this phone: loaded, heard, own folders) or a small server.
                server.kind == ServerKind.Local || state.account?.let { Offline.stored(context, it.id, track) } != null ->
                    SymbolIcon(Symbol.Phone, ink.secondary, 14.dp, modifier = Modifier.padding(end = 8.dp).semantics { contentDescription = "Auf diesem Telefon" })
                // On the server: tap loads this one title onto the phone (Olaf 05.10.2026: single songs, not only whole playlists).
                else -> Box(Modifier.size(32.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Auf dieses Telefon laden") {
                        state.account?.let { a -> Offline.download(context, a.id, server, listOf(track)) } }
                    .semantics { contentDescription = "Auf deinem Server – antippen lädt aufs Telefon" }, contentAlignment = Alignment.Center) {
                    SymbolIcon(Symbol.Server, ink.tertiary, 14.dp)
                }
            }
            if (track.duration > 0) Label(duration(track.duration), 13f, color = ink.secondary, tabular = true)
            // From the internet: tap plays (streamed), ↓ loads it into "Aus dem Netz" (card 6c9ba022).
            if (server.kind == ServerKind.Web) Box(Modifier.padding(start = 4.dp)) { WebLoadButton(track.asHit(server), state) }
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
        largeTitle("Start")
        if (recent.value?.isNotEmpty() == true) { item { SectionHeader("Zuletzt gespielt") }; item { AlbumRow(state, server, recent.value) } }
        if (server.kind == ServerKind.Web) {
            // LiDio privat without server (card d1f83bf9): what is current – the charts and the web source's playlists first.
            item { SectionHeader("Charts & Playlists") }
            loading(lists, retryLists) { item { PlaylistRow(state, server, it) } }
        }
        // iOS "Für dich": two mixes made from the own library – favourites with similar titles, and what was never played yet.
        item { SectionHeader("Mixe für dich") }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MixTile("Lieblings-Mix", "Deine Favoriten und Ähnliches", listOf(Color(0xFFFF375F), Color(0xFFBF5AF2)), Modifier.weight(1f)) { state.open(Route.MixPage("favoriten")) }
                MixTile("Neu entdecken", "Noch nie gehört", listOf(Color(0xFF0A84FF), Color(0xFF30D158)), Modifier.weight(1f)) { state.open(Route.MixPage("entdecken")) }
            }
        }
        item { SectionHeader(if (server.kind == ServerKind.Web) "Neuerscheinungen" else "Neu hinzugefügt") }
        if (newest.error == null || recent.error == null) loading(newest, retryNewest) { item { AlbumRow(state, server, it) } }
        if (frequent.value?.isNotEmpty() == true && frequent.value != recent.value) { item { SectionHeader("Oft gehört") }; item { AlbumRow(state, server, frequent.value) } }
        if (recent.error != null && newest.error != null) item {
            Failed(recent.error, retryRecent)
            Label("Geladene Musik ansehen", 17f, 400, Ink.tint, Modifier.fillMaxWidth().clickable(role = Role.Button) {
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

/** Playlists as large tiles in a row. */
@Composable
fun PlaylistRow(state: AppState, server: MusicServer, lists: List<Playlist>) {
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(lists, key = { it.id }) { p ->
            Column(Modifier.width(160.dp).clickable(role = Role.Button) { state.open(Route.PlaylistPage(p.id, p.name, web = server.kind == ServerKind.Web)) }) {
                Cover(server.cover(p.coverId, 320), Modifier.size(160.dp), 8.dp)
                Label(p.name, 15f, 500, modifier = Modifier.padding(top = 6.dp), lines = 2)
                if (p.trackCount > 0) Label("${p.trackCount} Titel", 13f, color = Ink.secondary)
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
        largeTitle("Mediathek", trailing = {
            Label(if (editing) "Fertig" else "Bearbeiten", 17f, if (editing) 600 else 400, ink.tint,
                Modifier.padding(end = 12.dp).clickable(role = Role.Button) { editing = !editing })
            Box(Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Server") { state.open(Route.Servers) }
                .semantics { contentDescription = "Server" }, contentAlignment = Alignment.Center) { SymbolIcon(Symbol.Server, ink.tint, 24.dp) }
        })
        // iOS 26: what is pinned stands above everything (long press on an album or a playlist → Anheften).
        item { PinnedGrid(state, server) }
        val entries = listOf(Triple(Symbol.Playlists, "Playlists", Route.Playlists), Triple(Symbol.Artists, "Interpreten", Route.Artists),
            Triple(Symbol.Albums, "Alben", Route.Albums), Triple(Symbol.Note, "Titel", Route.Tracks), Triple(Symbol.Star, "Lieblingstitel", Route.Favorites), Triple(Symbol.Downloaded, "Geladen", Route.Downloaded)) +
            (if (Variant.PRIVATE) listOf(Triple(Symbol.Globe, "Aus dem Netz", Route.Web)) else emptyList())
        items(if (editing) entries else entries.filter { it.second !in hidden }) { (symbol, label, route) ->
            if (editing) ListRow(label, height = 48.dp,
                leading = { Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) { SymbolIcon(symbol, ink.tint, 24.dp) } },
                trailing = { IosSwitch(label !in hidden, label) { on -> hidden = if (on) hidden - label else hidden + label; prefs.edit().putStringSet("mediathekAus", hidden).apply() } })
            else ListRow(label, onClick = { state.open(route) }, height = 48.dp,
                leading = { Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) { SymbolIcon(symbol, ink.tint, 24.dp) } },
                trailing = { Chevron() }, titleColor = ink.label)
        }
        item { SectionHeader("Zuletzt hinzugefügt") }
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
            largeTitle("Interpreten", topInset = false)
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
            Box(Modifier.clickable(role = Role.Button, onClickLabel = "Sortieren") { sortMenu = true }.semantics { contentDescription = "Sortieren nach ${order.label}" }) {
                Label("Sortieren", 17f, color = Ink.tint)
            }
        })
        if (sortMenu) MenuSheet("Sortieren nach", null, { sortMenu = false }) {
            val choices = listOf(AlbumOrder.Newest, AlbumOrder.Recent, AlbumOrder.Alphabetical, AlbumOrder.Artist, AlbumOrder.Year)
            choices.forEachIndexed { i, o ->
                ListRow(o.label, separator = i < choices.lastIndex, onClick = { order = o; prefs.edit().putString("albenSortierung", o.name).apply(); sortMenu = false },
                    trailing = { if (o == order) SymbolIcon(Symbol.Check, Ink.tint, 16.dp, weight = 2.4f) })
            }
        }
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = chromePadding()) {
            largeTitle("Alben", topInset = false)
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
            largeTitle("Titel", topInset = false)
            if (pages.items.isNotEmpty()) item { PlayButtons(state, server, pages.items) }
            itemsIndexed(pages.items, key = { i, t -> "$i-${t.id}" }) { i, track -> TrackRow(state, server, track) { state.playback.play(server, pages.items, i) } }
            pageFooter(pages)
        }
    }
}

@Composable
fun PlaylistsScreen(state: AppState, server: MusicServer) {
    val (lists, retry) = rememberCachedLoad("${state.account?.id}:${server.kind}:playlists", server, state.generation) { server.playlists() }
    Column(Modifier.fillMaxSize()) {
        NavBar(state)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = chromePadding()) {
            largeTitle("Playlists", topInset = false, trailing = {
                Box(Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Playlist importieren") { state.open(Route.Import) }
                    .semantics { contentDescription = "Playlist importieren" }, contentAlignment = Alignment.Center) { SymbolIcon(Symbol.Plus, Ink.tint, 24.dp, weight = 2.2f) }
            })
            loading(lists, retry) { list ->
                if (list.isEmpty()) item { Label("Noch keine Playlists auf diesem Server.", 15f, color = Ink.secondary, modifier = Modifier.padding(16.dp)) }
                items(list, key = { it.id }) { playlist ->
                    var menu by remember { mutableStateOf(false) }
                    if (menu) CollectionMenu(state, server, Pin("playlist", playlist.id, playlist.name, playlist.coverId, server.kind == ServerKind.Web), null) { menu = false }
                    ListRow(playlist.name, playlist.trackCount.takeIf { it > 0 }?.let { "$it Titel" }, onClick = { state.open(Route.PlaylistPage(playlist.id, playlist.name, web = server.kind == ServerKind.Web)) },
                        onLongClick = { menu = true }, height = 68.dp,
                        leading = { Cover(server.cover(playlist.coverId, 160), Modifier.size(56.dp), 6.dp) }, trailing = { Chevron() })
                }
            }
        }
    }
}

@Composable
fun PlayButtons(state: AppState, server: MusicServer, tracks: List<Track>) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Capsule(Symbol.Play, "Wiedergabe", Modifier.weight(1f)) { if (tracks.isNotEmpty()) state.playback.play(server, tracks) }
        Capsule(Symbol.Shuffle, "Zufall", Modifier.weight(1f)) { if (tracks.isNotEmpty()) state.playback.play(server, tracks, shuffled = true) }
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
    Column(Modifier.fillMaxSize()) {
        NavBar(state, trailing = load.value?.takeIf { server.kind != ServerKind.Local }?.let { {
            if (server.kind == ServerKind.Web) WebListButton(it.second.map { t -> t.asHit(server) }) else DownloadButton(state, server, it.second) } })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = chromePadding()) {
            loading(load, retry) { (album, all) ->
                val tracks = all
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Cover(server.cover(album.coverId, 600) ?: tracks.firstOrNull { it.coverId != null }?.let { server.cover(it, 600) },
                            Modifier.size(260.dp), 10.dp)
                        Label(album.title, 22f, 700, modifier = Modifier.padding(top = 16.dp, start = 24.dp, end = 24.dp), lines = 2, align = TextAlign.Center)
                        Label(album.artist, 22f, 400, ink.tint, Modifier.clickable(enabled = album.artistId != null) {
                            album.artistId?.let { state.open(Route.ArtistPage(it, album.artist, web = server.kind == ServerKind.Web)) } })
                        Label(listOfNotNull(album.genre, album.year?.toString()).joinToString(" · "), 13f, 600, ink.secondary, Modifier.padding(top = 4.dp))
                    }
                }
                item { PlayButtons(state, server, tracks) }
                item { Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(ink.separator)) }
                val discs = tracks.mapNotNull { it.disc }.toSet().size > 1
                itemsIndexed(tracks, key = { _, t -> t.id }) { i, track ->
                    if (discs && (i == 0 || tracks[i - 1].disc != track.disc)) Label("CD ${track.disc}", 15f, 600, ink.secondary, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
                    TrackRow(state, server, track, number = track.number ?: (i + 1), showArt = false,
                        subtitle = track.artist.takeIf { album.compilation || it != album.artist }) { state.playback.play(server, tracks, i) }
                }
                item {
                    Column(Modifier.padding(16.dp)) {
                        album.year?.let { Label("Erschienen $it", 13f, color = ink.secondary) }
                        Label(summary(tracks.size, tracks.sumOf { it.duration }), 13f, color = ink.secondary)
                    }
                }
            }
        }
    }
}

@Composable
fun ArtistScreen(state: AppState, server: MusicServer, route: Route.ArtistPage) {
    val (load, retry) = rememberLoad(server, route.id) { server.artist(route.id) }
    Column(Modifier.fillMaxSize()) {
        NavBar(state)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = chromePadding()) {
            largeTitle(route.name, topInset = false)
            loading(load, retry) { (_, albums) ->
                item { SectionHeader("Alben") }
                albumGrid(state, server, albums)
            }
        }
    }
}

@Composable
fun PlaylistScreen(state: AppState, server: MusicServer, route: Route.PlaylistPage) {
    val ink = Ink
    val hide = state.playback.settings.hideDuplicates
    val (load, retry) = rememberCachedLoad("${state.account?.id}:${server.kind}:pl:${route.id}:$hide", server, route.id, disk = PlaylistDisk) {
        server.playlist(route.id).let { (p, t) -> p to if (hide) Duplicates.tracks(t) else t }
    }
    Column(Modifier.fillMaxSize()) {
        // Card f3cba42b: "Freigeben" next to ↓ (own server playlists only).
        var sharing by remember { mutableStateOf(false) }
        if (sharing) load.value?.first?.let { ShareSheet(state, server, it) { sharing = false } }
        NavBar(state, trailing = load.value?.takeIf { server.kind != ServerKind.Local }?.let { {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (server.kind == ServerKind.Emby || server.kind == ServerKind.Jellyfin || server.kind == ServerKind.Navidrome)
                    Box(Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button) { sharing = true }.semantics { contentDescription = "Freigeben" },
                        contentAlignment = Alignment.Center) { SymbolIcon(Symbol.People, Ink.tint, 24.dp) }
                if (server.kind == ServerKind.Web) WebListButton(it.second.map { t -> t.asHit(server) }) else DownloadButton(state, server, it.second)
            } } })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = chromePadding()) {
            loading(load, retry) { (playlist, tracks) ->
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Cover(server.cover(playlist.coverId ?: tracks.firstOrNull()?.coverId, 600), Modifier.size(240.dp), 10.dp)
                        Label(playlist.name, 22f, 700, modifier = Modifier.padding(top = 16.dp, start = 24.dp, end = 24.dp), lines = 2, align = TextAlign.Center)
                        Label(summary(tracks.size, tracks.sumOf { it.duration }), 13f, color = ink.secondary, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                item { PlayButtons(state, server, tracks) }
                // Card f23bb4f0: what the server lacks stands in its place, greyed, like an unavailable title in Apple Music.
                val rows = withMissing(tracks, playlist.missing)
                if (playlist.missing.isNotEmpty()) item { MissingHeader(state, playlist.missing) }
                itemsIndexed(rows, key = { i, r -> if (r is Track) "$i-${r.id}" else "m$i-$r" }) { _, r ->
                    if (r is Track) TrackRow(state, server, r) { state.playback.play(server, tracks, tracks.indexOf(r)) }
                    else MissingTrackRow(state, r as String, playlist.id)
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
    val web = engine != null && (onlyWeb || state.searchWeb || isLink(asked))
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
                ?: throw ServerError("Die Spotify-Suche braucht eine eigene, kostenlose Spotify-App (Client-ID) unter Mediathek → Server. Links gehen auch ohne: einfügen oder aus Spotify teilen.")
        }
    }
    // A Deezer or Spotify playlist link (pasted, or shared from their apps): open it right away.
    LaunchedEffect(asked) { Links.parse(asked)?.let { (src, id) -> query = ""; asked = ""; state.open(Route.RemotePage(RemoteList(src, id, ""))) } }
    val searchServer = if (musicSearch) webServer ?: server else server
    val (result, retry) = rememberLoad(searchServer, asked, web, musicSearch) {
        if (asked.isEmpty() || (web && !musicSearch)) SearchResult() else searchServer.search(asked)
    }
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
        largeTitle("Suchen")
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(ink.fill.copy(alpha = if (ink.dark) 0.5f else 0.25f))
                .padding(horizontal = 8.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                SymbolIcon(Symbol.Search, ink.secondary, 18.dp)
                Box(Modifier.weight(1f).padding(start = 6.dp)) {
                    if (query.isEmpty()) Label(if (web) "Titel, Interpreten oder Link" else "Interpreten, Alben, Titel, Playlists", 17f, color = ink.secondary)
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = style(17f, color = ink.label), cursorBrush = SolidColor(ink.tint),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Suchbegriff" })
                }
                if (query.isNotEmpty()) Box(Modifier.clickable(role = Role.Button, onClickLabel = "Löschen") { query = "" }) { SymbolIcon(Symbol.Close, ink.secondary, 16.dp) }
            }
        }
        if (engine != null && !onlyWeb) item {
            Segmented(listOf("Deine Mediathek", "Im Netz"), if (web) 1 else 0, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { state.searchWeb = it == 1 }
        }
        if (!isLink(asked) && (web || asked.isNotEmpty())) item {
            ScopeBar(scope, if (asked.isEmpty() || (web && !musicSearch)) listOf(SearchScope.Top) else SearchScope.entries, if (web) source else null,
                onSource = { source = it; prefs.edit().putString("quelle", it.name).apply() }) { scope = it }
        }
        if (asked.isEmpty() && query.isEmpty()) searchStart(state, genreServer, recent, genres.value.orEmpty(), onRecent = { query = it; asked = it },
            onClear = { recent = emptyList(); recentPrefs.edit().remove("zuletzt").apply() })
        else if (playlistSource) {
            if (asked.isNotEmpty()) loading(found, foundRetry) { lists ->
                if (lists.isEmpty()) item { Label("Bei ${source.label} keine Playlist zu „$asked“.", 17f, color = ink.secondary, modifier = Modifier.padding(16.dp)) }
                else item { SectionHeader("Playlists") }
                items(lists, key = { "r" + it.source + it.id }) { p ->
                    ListRow(p.name, listOfNotNull(p.owner.takeIf { it.isNotEmpty() }, if (p.count > 0) "${p.count} Titel" else null).joinToString(" · "),
                        onClick = { state.open(Route.RemotePage(p)) }, height = 68.dp,
                        leading = { Cover(p.cover, Modifier.size(56.dp), 6.dp) }, trailing = { Chevron() })
                }
            }
        }
        else if (web && !musicSearch) webSearch(state, asked, source, webResult, webRetry)
        else if (asked.isNotEmpty()) loading(result, retry) { found ->
            val playlists = lists.value.orEmpty()
            val top = scope == SearchScope.Top
            if (found.artists.isEmpty() && found.albums.isEmpty() && found.tracks.isEmpty() && playlists.isEmpty() && !lists.loading)
                item { Label(if (everywhere) "Nirgends etwas zu „$asked“." else if (web) "Bei ${Variant.MUSIC} nichts zu „$asked“." else "Auf deinem Server nichts zu „$asked“.", 17f, color = ink.secondary, modifier = Modifier.padding(16.dp)) }
            val tracks = if (top) found.tracks.take(6) else found.tracks
            if ((top || scope == SearchScope.Titel) && tracks.isNotEmpty()) {
                item { SectionHeader("Titel", if (top && found.tracks.size > 6) "Alle anzeigen" else null) { scope = SearchScope.Titel } }
                itemsIndexed(tracks, key = { _, t -> "t" + t.id }) { i, track -> TrackRow(state, searchServer, track, subtitle = if (everywhere) "${track.artist} · ${Variant.MUSIC}" else track.artist) { state.playback.play(searchServer, tracks, i) } }
            }
            more.value?.let { (yt, sc, _) ->
                val others = (if (top) yt.take(3) + sc.take(3) else yt + sc).map { it to it.asTrack() }
                if ((top || scope == SearchScope.Titel) && others.isNotEmpty()) {
                    if (tracks.isEmpty()) item { SectionHeader("Titel") }
                    items(others, key = { "o" + it.second.id }) { (hit, t) ->
                        TrackRow(state, searchServer, t, subtitle = "${t.artist} · ${hit.source.label}") { state.playback.play(searchServer, others.map { it.second }, others.indexOfFirst { it.second.id == t.id }) }
                    }
                }
            }
            val albums = if (top) found.albums.take(4) else found.albums
            if ((top || scope == SearchScope.Alben) && albums.isNotEmpty()) {
                item { SectionHeader("Alben", if (top && found.albums.size > 4) "Alle anzeigen" else null) { scope = SearchScope.Alben } }
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
                    if (shown.isNotEmpty()) item { SectionHeader("Playlists", if (top && playlists.size > 4) "Alle anzeigen" else null) { scope = SearchScope.Playlists } }
                    items(shown, key = { "p" + it.id }) { p ->
                        ListRow(p.name, listOfNotNull(if (p.trackCount > 0) "${p.trackCount} Titel" else "Playlist", if (everywhere) Variant.MUSIC else null).joinToString(" · "),
                            onClick = { state.open(Route.PlaylistPage(p.id, p.name, web = true)) },
                            height = 68.dp, leading = { Cover(searchServer.cover(p.coverId, 160), Modifier.size(56.dp), 6.dp) }, trailing = { Chevron() })
                    }
                    val remoteLists = more.value?.third.orEmpty().let { if (top) it.take(4) else it }
                    if (remoteLists.isNotEmpty() && shown.isEmpty()) item { SectionHeader("Playlists") }
                    items(remoteLists, key = { "r" + it.source + it.id }) { p ->
                        ListRow(p.name, listOfNotNull(p.owner.takeIf { it.isNotEmpty() }, if (p.count > 0) "${p.count} Titel" else null, p.source.label).joinToString(" · "),
                            onClick = { state.open(Route.RemotePage(p)) }, height = 68.dp, leading = { Cover(p.cover, Modifier.size(56.dp), 6.dp) }, trailing = { Chevron() })
                    }
                } else publicPlaylists(state, server, asked, if (top) playlists.take(4) else playlists, if (sourcesOn > 0) remote else null) {
                    state.playback.settings.deezer = true; sourcesOn = state.playback.settings.publicSources().size
                }
            }
            val artists = if (top) found.artists.take(3) else found.artists
            if ((top || scope == SearchScope.Interpreten) && artists.isNotEmpty()) {
                item { SectionHeader("Interpreten", if (top && found.artists.size > 3) "Alle anzeigen" else null) { scope = SearchScope.Interpreten } }
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
enum class SearchScope(val label: String) { Top("Top-Treffer"), Titel("Titel"), Alben("Alben"), Playlists("Playlists"), Interpreten("Interpreten") }

/** The bar below the search field: in "Im Netz" first the source as a pull-down button (HIG), then the scopes as capsules. */
@Composable
private fun ScopeBar(scope: SearchScope, scopes: List<SearchScope>, source: WebSource?, onSource: (WebSource) -> Unit, onScope: (SearchScope) -> Unit) {
    val ink = Ink
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (source != null) Box {
            Row(Modifier.clip(RoundedCornerShape(16.dp)).background(ink.fill.copy(alpha = if (ink.dark) 0.5f else 0.25f))
                .clickable(role = Role.DropdownList, onClickLabel = "Quelle wählen") { menu = true }.padding(horizontal = 12.dp, vertical = 6.dp),
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
            largeTitle("Server", topInset = false)
            item { Label("VERBUNDEN", 13f, color = ink.secondary, modifier = Modifier.padding(start = 32.dp, top = 16.dp, bottom = 6.dp)) }
            item {
                Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(10.dp)).background(ink.card)) {
                    all.forEachIndexed { i, account ->
                        ListRow(account.name.ifEmpty { account.address }, if (account.kind == ServerKind.Local) "${LocalLibrary.decode(account.address).size} Ordner"
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
                    ListRow("Server hinzufügen", onClick = { state.adding = true }, titleColor = ink.tint, separator = true)
                    ListRow("Ordner auf diesem Gerät hinzufügen", onClick = { state.pickFolder() }, titleColor = ink.tint, separator = true)
                    if (state.account?.kind == ServerKind.Local) {
                        val scope = androidx.compose.runtime.rememberCoroutineScope()
                        val context = androidx.compose.ui.platform.LocalContext.current
                        ListRow("Ordner neu durchsuchen", onClick = { state.account?.let { a -> scope.launch { scanLocal(state, a) } } }, titleColor = ink.tint, separator = true)
                        LocalLibrary.decode(state.account?.address ?: "").forEach { folder ->
                            val name = android.net.Uri.decode(folder.lastPathSegment ?: "").substringAfterLast(':').ifEmpty { "Ordner" }
                            ListRow(name, "Ordner auf diesem Gerät", height = 56.dp, trailing = {
                                Label("Entfernen", 15f, 400, Red, Modifier.clickable(role = Role.Button) {
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
                    ListRow("Diesen Server abmelden", onClick = {
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
                        Triple("Mobile Daten", mobile, { v: Int -> mobile = v; state.playback.settings.mobileBitrate = v }))) {
                        Label(label, 15f, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                        Segmented(Settings.BITRATES.map(Settings::label), Settings.BITRATES.indexOf(value).coerceAtLeast(0),
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) { set(Settings.BITRATES[it]) }
                    }
                }
                Label("„Original“ spielt die Datei, wie sie auf dem Server liegt. Kleinere Werte lässt der Server umrechnen – das spart Datenvolumen.",
                    13f, color = ink.secondary, lines = 4, modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp, bottom = 20.dp))
            }
            item {
                var hide by remember { mutableStateOf(state.playback.settings.hideDuplicates) }
                Label("MEDIATHEK", 13f, color = ink.secondary, modifier = Modifier.padding(start = 32.dp, bottom = 6.dp))
                Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(10.dp)).background(ink.card)) {
                    ListRow("Duplikate ausblenden", "Gleicher Titel vom gleichen Interpreten nur einmal", height = 60.dp,
                        trailing = { IosSwitch(hide, "Duplikate ausblenden") { hide = it; state.playback.settings.hideDuplicates = it; state.generation++ } })
                    // Card 7ac89c11: lyrics from LRCLIB when the server has none.
                    var lyricsOnline by remember { mutableStateOf(state.playback.settings.lyricsOnline) }
                    ListRow("Liedtexte aus dem Netz", "Fehlt der Text auf dem Server, fragt LiDio LRCLIB", height = 60.dp, separator = false,
                        trailing = { IosSwitch(lyricsOnline, "Liedtexte aus dem Netz") { lyricsOnline = it; state.playback.settings.lyricsOnline = it } })
                }
                Label("Für Liedtexte gehen Titel und Interpret an lrclib.net – frei, ohne Konto und Werbung.", 13f, color = ink.secondary, lines = 2,
                    modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp))
                Spacer(Modifier.height(20.dp))
            }
            item {
                Label("HILFE", 13f, color = ink.secondary, modifier = Modifier.padding(start = 32.dp, bottom = 6.dp))
                Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(10.dp)).background(ink.card)) {
                    ListRow("Anleitung", "Rundgang, Kapitel, PDF, Datenschutz", height = 60.dp, separator = false, onClick = { state.open(Route.Guide) }, trailing = { Chevron() })
                }
                Spacer(Modifier.height(20.dp))
            }
            item {
                val w = state.winamp
                Label("DARSTELLUNG", 13f, color = ink.secondary, modifier = Modifier.padding(start = 32.dp, bottom = 6.dp))
                Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(10.dp)).background(ink.card)) {
                    // Card d894cc42: "Klassisch" or "Modern" (iOS 26) – independent of the Winamp view.
                    Label("Erscheinungsbild", 15f, modifier = Modifier.padding(start = 16.dp, top = 10.dp))
                    Segmented(listOf("Klassisch", "Modern"), if (state.modern) 1 else 0, Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        state.modern = it == 1; state.playback.settings.modern = it == 1
                    }
                    Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(ink.separator))
                    val context = androidx.compose.ui.platform.LocalContext.current
                    var allowed by remember { mutableStateOf(LockScreen.allowed(context)) }
                    LaunchedEffect(Unit) { while (true) { allowed = LockScreen.allowed(context); kotlinx.coroutines.delay(1000) } }
                    fun permission() = runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + context.packageName))) }
                    ListRow("Winamp auf dem Sperrbildschirm", if (w.lockScreen && !allowed) "Erlaubnis fehlt – hier antippen" else "Wenn Musik läuft und Winamp offen war",
                        height = 60.dp, titleColor = ink.label, onClick = if (w.lockScreen && !allowed) ({ permission() }) else null,
                        trailing = { IosSwitch(w.lockScreen, "Winamp auf dem Sperrbildschirm") { on ->
                            w.lockScreen = on; w.save(); if (on && !LockScreen.allowed(context)) permission() } })
                    ListRow("Winamp-Skin", height = 48.dp, separator = false, onClick = { state.open(Route.Skins) }, trailing = {
                        Label(SkinLibrary.nameOf(state.accounts.context, w.skin), 17f, color = ink.secondary, modifier = Modifier.padding(end = 6.dp)); Chevron() })
                }
                Label("„Modern“ sieht aus wie Musik unter iOS 26: Glas, schwebende Leiste, Suche als eigener Knopf. " +
                    "Der Blitz unten in „Jetzt läuft“ wechselt in die Winamp-Ansicht, der Blitz im Winamp-Fenster wieder zurück. LiDio merkt sich, welche Ansicht du zuletzt hattest.",
                    13f, color = ink.secondary, lines = 3, modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp, bottom = 20.dp))
            }
            item { OfflineSettings(state) }
            item { PublicSettings(state) }
            item { Label("LiDio spricht nur mit den Servern, die du hier einträgst. Passwörter bleiben verschlüsselt auf diesem Gerät.",
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
    Label("ADRESSEN", 13f, color = ink.secondary, modifier = Modifier.padding(start = 32.dp, top = 20.dp, bottom = 6.dp))
    Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(10.dp)).background(ink.card)) {
        for ((label, value, set) in listOf(Triple("Im WLAN", home, { v: String -> home = v }), Triple("Unterwegs", away, { v: String -> away = v }))) {
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(label, 15f, modifier = Modifier.width(96.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Label("optional", 15f, color = ink.tertiary)
                    BasicTextField(value, set, singleLine = true, textStyle = style(15f, color = ink.label), cursorBrush = SolidColor(ink.tint),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { save() }),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Adresse $label" }
                            .onFocusChanged { if (!it.isFocused) save() })
                }
                if (Reach.normal(state.address) == Reach.normal(value) && value.isNotEmpty())
                    Label("in Benutzung", 13f, 600, Green, Modifier.padding(start = 8.dp))
            }
        }
    }
    Label("Im WLAN nimmt LiDio die schnelle Adresse, sonst die für unterwegs – beim Start und bei jedem Netzwechsel, auch während der Wiedergabe.",
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
            largeTitle("Lieblingstitel", topInset = false)
            loading(load, retry) { all ->
                val tracks = all.filter { state.playback.isFavorite(it) }
                if (tracks.isEmpty()) item {
                    Label("Noch keine Lieblingstitel. Tippe in „Jetzt läuft“ auf ★ oder halte einen Titel gedrückt → Favorit.", 15f,
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
            largeTitle(genre.name, topInset = false)
            lists.value?.takeIf { it.isNotEmpty() }?.let { item { SectionHeader("Playlists") }; item { PlaylistRow(state, server, it) } }
            loading(albums, retry) { a -> if (a.isNotEmpty()) { item { SectionHeader("Alben") }; albumGrid(state, server, a) } }
        }
    }
}

/** Search before typing, like Music on iOS: what was searched lately, then genres and moods as coloured tiles. */
fun LazyListScope.searchStart(state: AppState, server: MusicServer, recent: List<String>, genres: List<Genre>, onRecent: (String) -> Unit, onClear: () -> Unit) {
    if (recent.isNotEmpty()) {
        item { SectionHeader("Zuletzt gesucht", "Löschen", onClear) }
        items(recent, key = { "r$it" }) { q ->
            ListRow(q, onClick = { onRecent(q) }, height = 44.dp, titleColor = Ink.tint,
                leading = { Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) { SymbolIcon(Symbol.Search, Ink.secondary, 16.dp) } })
        }
    }
    if (genres.isNotEmpty()) {
        item { SectionHeader("Kategorien entdecken") }
        items(genres.chunked(2)) { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { g ->
                    val color = g.color?.let { Color(it.toInt()) } ?: genreColor(g.name)
                    Box(Modifier.weight(1f).aspectRatio(1.6f).clip(RoundedCornerShape(10.dp))
                        .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.7f).compositeOver(Color.Black))))
                        .clickable(role = Role.Button) { state.open(Route.GenrePage(g, web = server.kind == ServerKind.Web)) }
                        .padding(12.dp), contentAlignment = Alignment.BottomStart) {
                        Label(g.name, 17f, 700, Color.White, lines = 2)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
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
        NavBar(state, trailing = { Label("Neu mischen", 17f, color = Ink.tint, modifier = Modifier.clickable(role = Role.Button) { round++ }) })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = chromePadding()) {
            largeTitle(if (kind == "favoriten") "Lieblings-Mix" else "Neu entdecken", topInset = false)
            loading(load, retry) { tracks ->
                if (tracks.isEmpty()) item {
                    Label(if (kind == "favoriten") "Markiere Titel mit ★ – daraus entsteht dein Mix." else "Hier ist gerade nichts Ungehörtes.", 15f,
                        color = Ink.secondary, lines = 3, modifier = Modifier.padding(16.dp))
                } else item { PlayButtons(state, server, tracks) }
                itemsIndexed(tracks, key = { _, t -> "x" + t.id }) { i, t -> TrackRow(state, server, t) { state.playback.play(server, tracks, i) } }
            }
        }
    }
}
