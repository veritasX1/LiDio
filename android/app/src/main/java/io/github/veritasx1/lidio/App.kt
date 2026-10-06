package io.github.veritasx1.lidio

import io.github.veritasx1.lidio.i18n.tr

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.chrisbanes.haze.hazeSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.asPaddingValues

/** Where one is inside a tab – each tab keeps its own way back, like on the iPhone. */
sealed interface Route {
    data object Library : Route
    data object Artists : Route
    data object Albums : Route
    data object Tracks : Route
    data object Playlists : Route
    data object Servers : Route
    data object Downloaded : Route
    data object Import : Route
    data object Upload : Route
    data object Mixtapes : Route
    data class AddMusic(val playlistId: String, val name: String, val mixtape: Boolean = false) : Route
    data class CoverCrop(val playlistId: String, val name: String) : Route
    data object Skins : Route
    /** "Aus dem Netz" – only LiDio privat (card 1843f577). */
    data object Web : Route
    /** "Lieblingstitel" – the ★ titles (card 81b01b4a). */
    data object Favorites : Route
    data class GenrePage(val genre: Genre, val web: Boolean = false) : Route
    data class MixPage(val kind: String) : Route
    /** Card 5ef5e3c8: the guide. */
    data object Guide : Route
    data class RemotePage(val list: RemoteList) : Route
    /** cover: the source's playlist picture (Deezer, Spotify) – goes to the server with the new playlist. */
    data class ImportWith(val name: String, val wanted: List<Wanted>, val cover: String? = null, val mixtape: Boolean = false) : Route
    /** web = from "Im Netz" while the own server is in use. */
    data class ArtistPage(val id: String, val name: String, val web: Boolean = false) : Route
    data class AlbumPage(val id: String, val web: Boolean = false) : Route
    data class PlaylistPage(val id: String, val name: String, val web: Boolean = false) : Route
}

enum class Tab(val label: String, val symbol: Symbol, val root: Route?) {
    Start(tr("Start"), Symbol.Home, null), New(tr("Neu"), Symbol.Sparkle, null), Library(tr("Mediathek"), Symbol.Library, Route.Library),
    Search(tr("Suchen"), Symbol.Search, null)
}

/** The app's state: accounts, the server in use, the player and the way through each tab. */
class AppState(val accounts: Accounts, val playback: Playback, val serverFor: (Account) -> MusicServer = { it.server(accounts.context) }) {
    var account by mutableStateOf(accounts.active() ?: withoutServer())
    /** LiDio privat (card d1f83bf9): the own server doesn't answer (moving house, new phone …) – the web source plays meanwhile. */
    var fallback by mutableStateOf(false)
    /** Whether the own server answers (replaced in tests, whose servers are made up). */
    var probe: (ServerKind, String) -> Boolean = { kind, address -> Reach.answers(kind, address, 2500) }

    /** LiDio privat on a new phone: no form, music at once – the web source comes first; a server can come later. */
    private fun withoutServer(): Account? {
        if (!Variant.PRIVATE) return null
        val web = Account("netz", ServerKind.Web, "", "", "", Variant.MUSIC)
        accounts.save(web); accounts.activeId = web.id
        return web
    }
    /** The address in use right now (WLAN or away). */
    var address by mutableStateOf(account?.address ?: "")
    var tab by mutableStateOf(Tab.Start)
    val stacks = mutableStateMapOf<Tab, List<Route>>()
    var nowPlaying by mutableStateOf(false)
    /** Winamp mode (card f11d52ad): when on, "Now Playing" is Winamp's windows – and the app opens with them. */
    val winamp = WinampSettings(accounts.context)
    /** The "Modern" look (card d894cc42). */
    var modern by mutableStateOf(playback.settings.modern)
    /** iOS 26: scrolling down shrinks the tab bar to the current tab, the mini player moves up beside it. */
    var chromeSmall by mutableStateOf(false)
    /** The tour's current step (card 5ef5e3c8), null = no tour. */
    var tour by mutableStateOf<Int?>(null)
    /** Milkdrop full screen (card ef3a3dfb). */
    var milk by mutableStateOf(false)
    var adding by mutableStateOf(false)
    /** While a local folder is being read: how many titles so far (card 2d0c9407). */
    var scanning by mutableStateOf<Int?>(null)
    /** Opens Android's folder picker (set by the app screen). */
    var pickFolder: () -> Unit = {}
    /** Bumped to reload the screens after switching server. */
    var generation by mutableStateOf(0)

    fun stack(tab: Tab = this.tab): List<Route> = stacks[tab] ?: listOfNotNull(tab.root)
    fun open(route: Route) { stacks[tab] = stack() + route }
    /** A title's artist (Now Playing): the first named one, looked up by name on the title's own source. */
    fun openArtist(track: Track) {
        val name = track.artist.split(", ", " & ", " feat. ", " Feat. ", " ft. ", " x ").first().trim()
        open(Route.ArtistPage("", name, web = track.path?.startsWith("https://") == true))
    }
    fun back(): Boolean { val s = stack(); if (s.size <= 1 && (tab.root != null || s.isEmpty())) return false; stacks[tab] = s.dropLast(1); return true }
    fun use(account: Account) { accounts.save(account); this.account = account; address = account.address; playback.accountId = account.id; stacks.clear(); generation++ }

    /** A short note at the top (e.g. a shared title that isn't here). */
    var notice by mutableStateOf<String?>(null)
    /** Suchen: what to look for (e.g. a missing title from an import), and whether "Im Netz" is chosen (LiDio privat). */
    var searchFor by mutableStateOf<String?>(null)
    var searchWeb by mutableStateOf(false)
    /** A link that came in before the app was ready. */
    var pendingShare by mutableStateOf<Shared?>(null)
    var pendingList by mutableStateOf<SharedList?>(null)

    /** The id a link from this account carries: the server's own id, for Navidrome its outside address (or the WLAN one). */
    fun shareId(account: Account, server: MusicServer): String = when (account.kind) {
        ServerKind.Navidrome -> SubsonicServer(account.external.ifEmpty { account.address }, "", "").serverId()
        ServerKind.Local, ServerKind.Web -> ""
        else -> serverIdOf(account) { server.serverId() }
    }

    private fun serverIdOf(account: Account, ask: () -> String): String {
        val prefs = accounts.context.getSharedPreferences("serverkennung", android.content.Context.MODE_PRIVATE)
        return prefs.getString(account.id, null) ?: ask().also { if (it.isNotEmpty()) prefs.edit().putString(account.id, it).apply() }
    }

    /** The ids an account answers to (Navidrome: both addresses). */
    private fun idsOf(account: Account): Set<String> = when (account.kind) {
        ServerKind.Navidrome -> listOf(account.address, account.external).filter { it.isNotBlank() }.map { SubsonicServer(it, "", "").serverId() }.toSet()
        ServerKind.Local, ServerKind.Web -> emptySet()
        else -> setOf(serverIdOf(account) { runCatching { serverFor(account.copy(address = Reach.best(account))).serverId() }.getOrDefault("") })
    }

    /** Opens a shared title: from the same server when there's an account on it, else the same song from the own library. */
    suspend fun openShared(shared: Shared) {
        val match = withContext(Dispatchers.IO) { accounts.all().firstOrNull { shared.server.isNotEmpty() && shared.server in idsOf(it) } }
        if (match != null) {
            if (match.id != account?.id) use(match)
            resolve()
            val server = serverFor(match.copy(address = address))
            val track = withContext(Dispatchers.IO) { server.track(shared.id) }
            if (track != null) { playback.play(server, listOf(track)); nowPlaying = true; return }
        }
        val own = account ?: run { notice = tr("Für „{title}“ zuerst einen Server verbinden.", "title" to shared.title); return }
        val server = serverFor(own.copy(address = address))
        val result = withContext(Dispatchers.IO) { Matcher.run(server, listOf(Wanted(shared.title, shared.artist, shared.album))).first() }
        val track = result.track
        if (result.match != Match.Missing && track != null) { playback.play(server, listOf(track)); nowPlaying = true }
        else notice = tr("„{title}“ von {artist} ist nicht in deiner Mediathek.", "title" to shared.title, "artist" to shared.artist)
    }

    /** A received Mixtape (card c9b15c67): on the same server the playlist itself, when this user may see it; else the list as
     *  sent – played from the own library (and, in LiDio privat, the internet), with "Übertragen" to keep it. */
    suspend fun openSharedList(shared: SharedList) {
        val match = withContext(Dispatchers.IO) { accounts.all().firstOrNull { shared.server.isNotEmpty() && shared.server in idsOf(it) } }
        if (match != null && shared.id.isNotEmpty()) {
            if (match.id != account?.id) use(match)
            resolve()
            val server = serverFor(match.copy(address = address))
            val there = withContext(Dispatchers.IO) { runCatching { server.playlist(shared.id) }.getOrNull() }
            if (there != null && there.second.isNotEmpty()) { tab = Tab.Library; open(Route.PlaylistPage(shared.id, shared.name)); return }
        }
        tab = Tab.Library
        open(Route.RemotePage(RemoteList(Source.Mixtape, SharedList.pack(shared.items), shared.name, count = shared.items.size)))
    }

    /** Checks which address answers; on a change the screens reload and the playing queue moves over. */
    suspend fun resolve() {
        val a = account ?: return
        val best = withContext(Dispatchers.IO) { Reach.best(a) }
        // LiDio privat: no answer from the own server at all → the fallback, quietly; back as soon as it answers again.
        if (Variant.PRIVATE && a.kind != ServerKind.Local && a.kind != ServerKind.Web) {
            val answers = withContext(Dispatchers.IO) { probe(a.kind, best) }
            if (!answers != fallback) {
                fallback = !answers
                notice = if (fallback) tr("Dein Server ist gerade nicht erreichbar – LiDio spielt solange aus dem Netz ({MUSIC}).", "MUSIC" to Variant.MUSIC) else tr("Dein Server ist wieder da.")
                generation++
            }
        }
        if (best != address) {
            val old = address
            address = best
            if (a.kind != ServerKind.Local) playback.moveTo(Reach.normal(old), Reach.normal(best))
            generation++
        }
    }

    init { account?.let { playback.accountId = it.id } }
}

/** Loads something from the server off the main thread; error text in German, with "Erneut versuchen". */
class Load<T>(val value: T?, val error: String?, val loading: Boolean)

@Composable
fun <T> rememberLoad(vararg keys: Any?, block: () -> T): Pair<Load<T>, () -> Unit> = rememberCachedLoad(null, *keys, block = block)

/** Card 5902afb5: what was loaded before shows at once (no "Wird geladen …"), the fresh answer replaces it quietly. With a disk
 *  file it even survives a restart. A failed refresh keeps the old content. */
@Composable
@Suppress("UNCHECKED_CAST")
fun <T> rememberCachedLoad(cache: String?, vararg keys: Any?, disk: DiskCache<T>? = null, block: () -> T): Pair<Load<T>, () -> Unit> {
    val context = androidx.compose.ui.platform.LocalContext.current
    var attempt by remember { mutableStateOf(0) }
    var state by remember(*keys, attempt) {
        val cached = cache?.let { LoadCache.get(it) } as T?
        mutableStateOf<Load<T>>(if (cached != null) Load(cached, null, false) else Load(null, null, true))
    }
    LaunchedEffect(*keys, attempt) {
        if (state.value == null && cache != null && disk != null)
            withContext(Dispatchers.IO) { disk.read(context, cache) }?.let { state = Load(it, null, false) }
        val had = state.value
        state = try {
            val fresh = withContext(Dispatchers.IO) { block() }
            if (cache != null && fresh != null) { LoadCache.put(cache, fresh); disk?.let { d -> withContext(Dispatchers.IO) { d.write(context, cache, fresh) } } }
            Load(fresh, null, false)
        }
        catch (e: ServerError) { if (had != null) Load(had, null, false) else Load(null, e.message, false) }
        catch (e: Exception) { if (had != null) Load(had, null, false) else Load(null, tr("Unerwartete Antwort vom Server."), false) }
    }
    return state to { attempt++; Unit }
}

/** The last answers of this session (playlists, albums), so going back and forth is instant. */
object LoadCache {
    private val memory = android.util.LruCache<String, Any>(64)
    fun get(key: String): Any? = memory.get(key)
    fun put(key: String, value: Any) { memory.put(key, value) }
    fun clear() { memory.evictAll() }
}

/** A loaded thing kept as a file between starts (playlists). */
interface DiskCache<T> {
    fun read(context: android.content.Context, key: String): T?
    fun write(context: android.content.Context, key: String, value: T)
}

/** Playlists with their titles, as JSON in the app's own cache folder (Android may clear it – then they simply load again). */
object PlaylistDisk : DiskCache<Pair<Playlist, List<Track>>> {
    private fun file(context: android.content.Context, key: String) =
        java.io.File(java.io.File(context.cacheDir, "playlists").apply { mkdirs() }, SubsonicServer.md5(key) + ".json")
    override fun read(context: android.content.Context, key: String): Pair<Playlist, List<Track>>? = runCatching {
        val j = org.json.JSONObject(file(context, key).readText())
        Playlist(j.getString("id"), j.optString("name"), j.optInt("count"), j.optInt("duration"), j.str("cover"),
            j.optJSONArray("missing").let { a -> if (a == null) emptyList() else (0 until a.length()).map(a::getString) }, j.str("origin")) to
            j.getJSONArray("tracks").objects().map { Index.decode(it).copy(path = it.str("path"), suffix = it.str("suffix")) }
    }.getOrNull()
    override fun write(context: android.content.Context, key: String, value: Pair<Playlist, List<Track>>) {
        val (p, tracks) = value
        runCatching {
            file(context, key).writeText(org.json.JSONObject().put("id", p.id).put("name", p.name).put("count", p.trackCount).put("duration", p.duration)
                .put("cover", p.coverId).put("missing", org.json.JSONArray(p.missing)).put("origin", p.origin).put("tracks", org.json.JSONArray(tracks.map { Index.encode(it).put("path", it.path).put("suffix", it.suffix) })).toString())
        }
    }
}

@Composable
fun LiDioApp(state: AppState) {
    val ink = Ink
    val playback = state.playback
    LaunchedEffect(playback.controller) { while (true) { playback.refresh(); delay(if (state.winamp.on && state.nowPlaying) 200 else 500) } }
    // Download progress per title (card 2aaf09ce).
    val appContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { while (true) { Offline.refreshLive(appContext); delay(400) } }
    // Winamp mode: the main window is always there – the app starts with it.
    LaunchedEffect(Unit) { if (state.winamp.on && state.account != null) state.nowPlaying = true }
    // … and like Winamp's eject: pick something in the library, and its windows are back.
    LaunchedEffect(playback.current?.id) { if (state.winamp.on && playback.current != null) state.nowPlaying = true }
    BackHandler(enabled = state.nowPlaying) { state.nowPlaying = false }
    BackHandler(enabled = !state.nowPlaying && state.stack().size > 1) { state.back() }

    // Folders on the phone (card 2d0c9407): Android's picker, the grant kept; then read them in the background.
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val folders = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val existing = state.accounts.all().firstOrNull { it.kind == ServerKind.Local }
        val list = (existing?.let { LocalLibrary.decode(it.address) } ?: emptyList()) + uri
        val account = (existing ?: Account(java.util.UUID.randomUUID().toString(), ServerKind.Local, "", "", "", tr("Auf diesem Gerät")))
            .copy(address = LocalLibrary.encode(list.distinct()))
        scope.launch { scanLocal(state, account) }
    }
    state.pickFolder = { folders.launch(null) }

    // Home and away: look again whenever the network changes (WLAN joined or left).
    LaunchedEffect(state.account) { state.resolve() }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { scope.launch { delay(800); state.resolve() } }
            override fun onLost(network: android.net.Network) { scope.launch { delay(800); state.resolve() } }
        }
        runCatching { manager?.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { manager?.unregisterNetworkCallback(callback) } }
    }

    // Card 5ef5e3c8: the tour's targets report where they are; on the very first start the tour begins by itself.
    val anchors = remember { androidx.compose.runtime.mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>() }
    LaunchedEffect(state.account) {
        if (state.account != null && !Help.tourOffered(context) && Help.level(context) != HelpLevel.Off && android.os.Build.FINGERPRINT != "robolectric") {
            Help.markTourOffered(context); delay(800); state.tour = 0
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalTourAnchors provides anchors) {
    Box(Modifier.fillMaxSize().background(ink.background)) {
        val account = state.account
        if (account == null || state.adding) {
            Connect(onDone = { state.use(it); state.adding = false }, onCancel = if (account != null) ({ state.adding = false }) else null,
                onLocal = { state.pickFolder() })
            state.scanning?.let { n -> Box(Modifier.align(Alignment.Center).clip(RoundedCornerShape(14.dp)).background(ink.elevated)
                .padding(horizontal = 24.dp, vertical = 18.dp)) { Label(tr("Ordner wird durchsucht … {n} Titel", "n" to n), 15f, 600) } }
            return@Box
        }
        val server = remember(account, state.address, state.generation, state.fallback) {
            if (state.fallback) Variant.webServer(context) ?: state.serverFor(account)
            else state.serverFor(account.copy(address = state.address.ifEmpty { account.address }))
        }
        // While on the fallback: look for the own server again every minute.
        LaunchedEffect(state.fallback) { while (state.fallback) { delay(60_000); state.resolve() } }
        // How long music stays on the phone (Olaf 05.10.2026): tidied quietly a while after the start, at most once a day.
        LaunchedEffect(account.id, state.fallback) {
            if (state.fallback) return@LaunchedEffect
            delay(20_000)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { Keep.tidy(context, account.id, server) } }
        }
        // Under Now Playing the rest is still drawn, but TalkBack must not read it (nor find a second "Pause").
        if (state.modern) {
            // "Modern" (card d894cc42): the screens fill everything, the glass bars float above and blur what scrolls beneath.
            val haze = remember { dev.chrisbanes.haze.HazeState() }
            val bottom = chromeHeight(playback.current != null) +
                androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            androidx.compose.runtime.CompositionLocalProvider(LocalHaze provides haze, LocalModern provides true, LocalBottomChrome provides bottom) {
                val scroll = remember {
                    object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                        override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                            if (available.y < -6f) state.chromeSmall = true else if (available.y > 6f) state.chromeSmall = false
                            return androidx.compose.ui.geometry.Offset.Zero
                        }
                    }
                }
                LaunchedEffect(state.tab, state.stack().size) { state.chromeSmall = false }
                Box(Modifier.fillMaxSize().then(if (state.nowPlaying) Modifier.clearAndSetSemantics {} else Modifier)
                    .nestedScroll(scroll)) {
                    Box(Modifier.fillMaxSize().background(ink.background).then(Modifier.hazeSource(haze))) {
                        Screen(state, server, state.stack().lastOrNull())
                    }
                    ModernChrome(state, server, Modifier.align(Alignment.BottomCenter))
                }
            }
        } else Column(Modifier.fillMaxSize().then(if (state.nowPlaying) Modifier.clearAndSetSemantics {} else Modifier)) {
            Box(Modifier.weight(1f)) {
                val route = state.stack().lastOrNull()
                Screen(state, server, route)
            }
            MiniPlayer(state, server)
            TabBar(state)
        }
        AnimatedVisibility(state.nowPlaying, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            if (state.winamp.on) WinampScreen(state, server) { state.nowPlaying = false }
            else NowPlaying(state, server) { state.nowPlaying = false }
        }
        state.scanning?.let { n ->
            Box(Modifier.align(Alignment.Center).clip(RoundedCornerShape(14.dp)).background(ink.elevated).padding(horizontal = 24.dp, vertical = 18.dp)) {
                Label(tr("Ordner wird durchsucht … {n} Titel", "n" to n), 15f, 600)
            }
        }
        LaunchedEffect(state.pendingList, state.account) {
            val list = state.pendingList ?: return@LaunchedEffect
            if (state.account == null) return@LaunchedEffect
            state.pendingList = null
            scope.launch { state.openSharedList(list) }
        }
        // A shared link waits until a server is there.
        LaunchedEffect(state.pendingShare, state.account) {
            val shared = state.pendingShare ?: return@LaunchedEffect
            if (state.account == null) return@LaunchedEffect
            state.pendingShare = null   // this restarts the effect – so the work runs outside it, or it would be cancelled
            scope.launch { state.openShared(shared) }
        }
        // Short notes go away by themselves, like iOS's banners (errors stay until tapped).
        LaunchedEffect(state.notice) { if (state.notice != null) { delay(3000); state.notice = null } }
        if (state.milk && Milk.loaded) MilkScreen(state) { state.milk = false }
        state.tour?.let { i ->
            TourOverlay(i, anchors, onNext = { state.tour = if (i < Tour.steps().lastIndex) i + 1 else null }, onSkip = { state.tour = null })
        }
        (playback.error ?: state.notice)?.let { text ->
            Box(Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 8.dp, start = 16.dp, end = 16.dp)
                .clip(RoundedCornerShape(14.dp)).background(ink.elevated).clickable { playback.error = null; state.notice = null }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Label(text, 15f, 500, lines = 3)
            }
        }
    }
    }
}

@Composable
private fun webOr(web: Boolean, server: MusicServer): MusicServer {
    val context = androidx.compose.ui.platform.LocalContext.current
    return if (web) remember { Variant.webServer(context) } ?: server else server
}

@Composable
private fun Screen(state: AppState, server: MusicServer, route: Route?) {
    when (route) {
        null -> when (state.tab) { Tab.Search -> SearchScreen(state, server); Tab.New -> NewScreen(state, server); else -> StartScreen(state, server) }
        Route.Library -> LibraryScreen(state, server)
        Route.Artists -> ArtistsScreen(state, server)
        Route.Albums -> AlbumsScreen(state, server)
        Route.Tracks -> TracksScreen(state, server)
        Route.Playlists -> PlaylistsScreen(state, server)
        Route.Servers -> ServersScreen(state)
        Route.Downloaded -> DownloadedScreen(state, server)
        Route.Import -> ImportScreen(state, server)
        Route.Upload -> UploadScreen(state, server)
        Route.Mixtapes -> PlaylistsScreen(state, server, mixtapes = true)
        is Route.AddMusic -> AddMusicScreen(state, server, route)
        is Route.CoverCrop -> CoverCropScreen(state, server, route)
        Route.Skins -> SkinsScreen(state)
        Route.Web -> WebScreen(state)
        Route.Favorites -> FavoritesScreen(state, server)
        is Route.GenrePage -> GenreScreen(state, webOr(route.web, server), route.genre)
        is Route.MixPage -> MixScreen(state, server, route.kind)
        Route.Guide -> GuideScreen(state)
        is Route.RemotePage -> RemoteScreen(state, server, route)
        is Route.ImportWith -> ImportScreen(state, server, route.wanted, route.name, route.cover, route.mixtape)
        is Route.ArtistPage -> ArtistScreen(state, webOr(route.web, server), route)
        is Route.AlbumPage -> AlbumScreen(state, webOr(route.web, server), route)
        is Route.PlaylistPage -> PlaylistScreen(state, webOr(route.web, server), route)
    }
}

/** The tab bar: translucent, a hairline on top, the chosen tab in the tint colour. */
@Composable
private fun TabBar(state: AppState) {
    val ink = Ink
    Column(Modifier.fillMaxWidth().background(ink.bar)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(ink.separator))
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).height(54.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Tab.entries.forEach { tab ->
                val on = tab == state.tab
                Column(Modifier.weight(1f).tourAnchor(when (tab) { Tab.Start -> "start"; Tab.New -> "neu"; Tab.Library -> "mediathek"; Tab.Search -> "suchen" }).clickable(role = Role.Tab) {
                    // Tapping the tab you're on goes back to its start (iOS).
                    if (on) state.stacks.remove(tab) else state.tab = tab
                }.padding(top = 6.dp).semantics { contentDescription = tab.label }, horizontalAlignment = Alignment.CenterHorizontally) {
                    SymbolIcon(tab.symbol, if (on) ink.tint else ink.secondary, 26.dp, filled = on)
                    Label(tab.label, 10f, 600, if (on) ink.tint else ink.secondary, Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

/** The mini player above the tab bar: art, title, play/pause, next – a tap opens Now Playing. */
@Composable
private fun MiniPlayer(state: AppState, server: MusicServer) {
    val ink = Ink
    val playback = state.playback
    val track = playback.current ?: return
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).tourAnchor("miniplayer").shadow(10.dp, RoundedCornerShape(14.dp))
        .clip(RoundedCornerShape(14.dp)).background(if (ink.dark) Color(0xFF2C2C2E) else Color(0xFFF9F9F9))
        .clickable(onClickLabel = tr("Wiedergabe öffnen")) { state.nowPlaying = true }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(server.cover(track, 120), Modifier.size(40.dp), 6.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Label(track.title, 15f, 500)
            Label(track.artist, 13f, color = ink.secondary)
        }
        Box(Modifier.size(40.dp).clickable(role = Role.Button, onClickLabel = if (playback.playing) tr("Pause") else tr("Wiedergabe")) { playback.toggle() }
            .semantics { contentDescription = if (playback.playing) tr("Pause") else tr("Wiedergabe") }, contentAlignment = Alignment.Center) {
            SymbolIcon(if (playback.playing) Symbol.Pause else Symbol.Play, ink.label, 22.dp)
        }
        Box(Modifier.size(40.dp).clickable(role = Role.Button, onClickLabel = tr("Nächster Titel")) { playback.next() }
            .semantics { contentDescription = tr("Nächster Titel") }, contentAlignment = Alignment.Center) {
            SymbolIcon(Symbol.Forward, ink.label, 24.dp)
        }
        Spacer(Modifier.width(2.dp))
    }
}

/** Reads the local folders (only changed files) and switches to them. */
suspend fun scanLocal(state: AppState, account: Account) {
    state.scanning = 0
    val count = kotlinx.coroutines.withContext(Dispatchers.IO) {
        LocalLibrary(state.accounts.context, account.id, LocalLibrary.decode(account.address)).scan { n -> state.scanning = n }
    }
    state.scanning = null
    state.adding = false
    state.use(account.copy(name = tr("Auf diesem Gerät · {count} Titel", "count" to count)))
}

/** A long list from the server in pages (Emby: 12 000 albums, 35 000 titles): the next page comes when the end is near. */
class Pages<T>(private val pageSize: Int, private val fetch: (offset: Int, size: Int) -> List<T>) {
    var items by mutableStateOf<List<T>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var done by mutableStateOf(false); private set

    suspend fun more() {
        if (loading || done) return
        loading = true; error = null
        try {
            val page = withContext(Dispatchers.IO) { fetch(items.size, pageSize) }
            items = items + page
            if (page.size < pageSize) done = true
        } catch (e: ServerError) { error = e.message } catch (e: Exception) { error = tr("Unerwartete Antwort vom Server.") }
        loading = false
    }
}

@Composable
fun <T> rememberPages(vararg keys: Any?, pageSize: Int = 200, fetch: (Int, Int) -> List<T>): Pages<T> {
    val pages = remember(*keys) { Pages(pageSize, fetch) }
    LaunchedEffect(pages) { pages.more() }
    return pages
}
