package io.github.veritasx1.lidio

import io.github.veritasx1.lidio.i18n.tr

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Im Netz" in Suchen and "Aus dem Netz" in the Mediathek – only "LiDio privat" (card 1843f577). Olaf: the user must always see
 *  what is available, where it comes from, what is happening right now – and load single titles, not only lists. */

fun isLink(text: String) = text.trim().let { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("www.") }

/** Where a title comes from: a small coloured mark with the source's name – on every result and every download. */
@Composable
fun SourceBadge(source: WebSource, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(4.dp)).background(source.color.copy(alpha = 0.16f)).padding(horizontal = 5.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(source.color))
        Label(source.label, 11f, 600, if (Ink.dark && source == WebSource.Dailymotion) Ink.label else source.color, Modifier.padding(start = 4.dp))
    }
}

/** The App Store's download button: arrow → ring filling up (tap stops) → tick; red "!" when it failed (tap tries again). */
@Composable
fun WebLoadButton(hit: WebHit, state: AppState? = null, playlistId: String? = null, line: String? = null, position: Int? = null) {
    val ink = Ink
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val job = WebDownloads.jobFor(hit.key)
    // Card a984b806: ↓ asks where to – this phone or the own server (LiDio-Lader on the Pi), like an iOS action sheet.
    var asking by remember { mutableStateOf(false) }
    var laderThere by remember { mutableStateOf<Boolean?>(null) }
    val server = Lader.jobs[hit.key]
    // The phone's own copy comes first (it plays at once); the server's state shows only when there is no phone job.
    if (server != null && job == null) {
        val (st, note) = server
        LaunchedEffect(st) {
            while (state != null && st !in setOf("fertig", "fehler")) {
                kotlinx.coroutines.delay(3000)
                val a = state.account ?: break
                withContext(Dispatchers.IO) { Lader.poll(a, state.address) }
                if (Lader.jobs[hit.key]?.first != st) break
            }
            if (st == "fertig") { Keep.onServer(context, hit.key); state?.generation = (state?.generation ?: 0) + 1 }
        }
        Box(Modifier.size(36.dp).semantics { contentDescription = when (st) { "fertig" -> tr("Auf dem Server"); "fehler" -> tr("Auf den Server laden ging nicht: {note}", "note" to note); else -> tr("Wird auf den Server geladen") } },
            contentAlignment = Alignment.Center) {
            when (st) {
                "fertig" -> SymbolIcon(Symbol.Server, ink.secondary, 18.dp)
                "fehler" -> Label("!", 20f, 700, Red)
                else -> ProgressRing(null, ink.tint)
            }
        }
        return
    }
    if (asking && state != null) MenuSheet(tr("Laden"), hit.title, { asking = false }) {
        MenuRow(tr("Auf dieses Gerät"), Symbol.Downloaded) { asking = false; WebDownloads.add(context, listOf(hit)) }
        val ok = laderThere == true
        ListRow(if (playlistId != null) tr("Auf den Server – in diese Playlist") else tr("Auf den Server"),
            if (ok) tr("In deine Mediathek auf dem Server") else if (laderThere == null) tr("Prüft …") else tr("Braucht den LiDio-Lader auf dem Pi"),
            separator = false, height = 60.dp, titleColor = if (ok) ink.label else ink.tertiary,
            onClick = if (!ok) null else ({
                asking = false
                val a = state.account ?: return@ListRow
                scope.launch {
                    val err = withContext(Dispatchers.IO) { runCatching { Lader.load(a, state.address, hit, playlistId, line, position) }.exceptionOrNull() }
                    if (err != null) state.notice = err.message ?: tr("Der Lader hat abgelehnt.")
                }
            }), trailing = { SymbolIcon(Symbol.Server, if (ok) ink.label else ink.tertiary, 20.dp) })
        LaunchedEffect(Unit) { laderThere = withContext(Dispatchers.IO) { Lader.available(state.account, state.address) } }
    }
    val description = when (job?.state) {
        null -> tr("Laden"); WebJob.State.Waiting -> tr("Wartet – antippen bricht ab"); WebJob.State.Loading -> tr("Wird geladen – antippen bricht ab")
        WebJob.State.Converting -> tr("Wird umgewandelt"); WebJob.State.Done -> tr("Geladen"); WebJob.State.Failed -> tr("Fehlgeschlagen – erneut versuchen")
    }
    Box(Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button) {
        when (job?.state) {
            // Olaf 05.10.2026: ↓ loads onto the phone at once – no question; the own server fetches it quietly in the
            // background when the LiDio-Lader is there (a slow server never keeps the music waiting).
            null -> {
                WebDownloads.add(context, listOf(hit))
                val a = state?.account
                if (Variant.PRIVATE && a != null) scope.launch(Dispatchers.IO) {
                    runCatching { if (Lader.available(a, state.address)) Lader.load(a, state.address, hit, playlistId, line, position) }
                }
            }
            WebJob.State.Failed -> WebDownloads.retry(context, job.id)
            WebJob.State.Waiting, WebJob.State.Loading -> WebDownloads.remove(context, job.id, deleteFile = true)
            else -> {}
        }
    }.semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        when (job?.state) {
            null -> SymbolIcon(Symbol.Downloaded, ink.tint, 26.dp)
            WebJob.State.Done -> SymbolIcon(Symbol.Check, ink.secondary, 18.dp, weight = 2.4f)
            WebJob.State.Failed -> Label("!", 20f, 700, Red)
            WebJob.State.Waiting -> WaitingRing()
            else -> ProgressRing(if (job.state == WebJob.State.Loading) WebDownloads.progress[job.id] else null, ink.tint)
        }
    }
}

/** A ring like iOS's: grey track, tint arc for the progress (none = waiting/converting), a small stop square in the middle. */
@Composable
fun ProgressRing(progress: Float?, color: Color, size: androidx.compose.ui.unit.Dp = 26.dp) {
    val ink = Ink
    // No number yet (starting, converting): a short arc going round – like the App Store's "preparing".
    val spin = if (progress == null || progress <= 0f) androidx.compose.animation.core.rememberInfiniteTransition(label = "Ring").animateFloat(0f, 360f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing)), label = "Drehung").value else 0f
    Canvas(Modifier.size(size)) {
        val w = 2.5.dp.toPx()
        val r = Size(this.size.width - w, this.size.height - w)
        // Card 1092956d: the track in the ring's own colour, faint – on a coloured album page ink.fill was as light as the
        // arc, and the progress could not be seen.
        drawArc(color.copy(alpha = 0.22f), 0f, 360f, false, Offset(w / 2, w / 2), r, style = Stroke(w))
        if (progress != null && progress > 0f) drawArc(color, -90f, 360f * progress.coerceIn(0f, 1f), false, Offset(w / 2, w / 2), r, style = Stroke(w, cap = StrokeCap.Round))
        else drawArc(color, -90f + spin, 60f, false, Offset(w / 2, w / 2), r, style = Stroke(w, cap = StrokeCap.Round))
        val s = this.size.width * 0.3f
        drawRect(color, Offset((this.size.width - s) / 2, (this.size.height - s) / 2), Size(s, s))
    }
}

/** Waiting in line: a grey dotted circle, still – the App Store's "waiting" (card 2aaf09ce). */
@Composable
fun WaitingRing(size: androidx.compose.ui.unit.Dp = 26.dp) {
    val ink = Ink
    Canvas(Modifier.size(size).semantics { contentDescription = "Wartet" }) {
        val w = 2.dp.toPx()
        val r = Size(this.size.width - w, this.size.height - w)
        drawArc(ink.secondary, 0f, 360f, false, Offset(w / 2, w / 2), r,
            style = Stroke(w, cap = StrokeCap.Round, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(w * 0.1f, w * 2.2f))))
    }
}

@Composable
fun WebHitRow(hit: WebHit, showSource: Boolean = true) {
    val ink = Ink
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = tr("Laden")) {
            if (WebDownloads.jobFor(hit.key).let { it == null || it.state == WebJob.State.Failed }) WebDownloads.add(context, listOf(hit)) }
        .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(hit.thumbnail, Modifier.size(48.dp), 5.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            Label(hit.title, 17f)
            Label(listOfNotNull(hit.artist.ifEmpty { null }, hit.album).joinToString(" · "), 13f, color = ink.secondary)
            if (showSource) SourceBadge(hit.source, Modifier.padding(top = 3.dp))
        }
        if (hit.duration > 0) Label(duration(hit.duration), 13f, color = ink.secondary, tabular = true, modifier = Modifier.padding(end = 4.dp))
        WebLoadButton(hit)
    }
}

/** "Im Netz" with a site or a link (the loader): every title plays at once – streamed, like Apple Music – and loads
 *  only with ↓ (card 6c9ba022: listen first, so nothing fills the phone or the server by mistake). */
fun LazyListScope.webSearch(state: AppState, asked: String, source: WebSource, results: Load<Pair<String, List<WebHit>>>, retry: () -> Unit) {
    if (asked.isEmpty()) {
        item {
            Label(Variant.text("netz-hinweis"),
                15f, color = Ink.secondary, lines = 6, modifier = Modifier.padding(16.dp))
        }
        return
    }
    loading(results, retry) { (title, hits) ->
        if (hits.isEmpty()) item { Label(tr("Bei {if} nichts zu „{asked}“.", "if" to (if (isLink(asked)) "diesem Link" else source.label), "asked" to asked), 17f, color = Ink.secondary, modifier = Modifier.padding(16.dp)) }
        else if (isLink(asked) && hits.size > 1) item {
            val context = LocalContext.current
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Label(title, 22f, 700, lines = 2)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    SourceBadge(hits.first().source)
                    Label(tr("{size} Titel", "size" to hits.size), 13f, color = Ink.secondary, modifier = Modifier.padding(start = 8.dp))
                }
                val open = hits.filter { h -> WebDownloads.jobFor(h.key).let { it == null || it.state == WebJob.State.Failed } }
                Capsule(Symbol.Downloaded, if (open.isEmpty()) tr("Alle in der Warteschlange") else tr("Alle {size} laden", "size" to open.size), Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    WebDownloads.add(context, open)
                }
            }
        }
        else item {
            Label(tr("Gefunden bei {if}. Antippen spielt, ↓ lädt.", "if" to (if (isLink(asked)) hits.first().source.label else source.label)),
                13f, color = Ink.secondary, lines = 2, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        val tracks = hits.map { it.asTrack() }
        itemsIndexed(tracks, key = { _, t -> "w" + t.id }) { i, track ->
            val context = LocalContext.current
            val web = remember { Variant.webServer(context) } ?: return@itemsIndexed
            TrackRow(state, web, track) { state.playback.play(web, tracks, i) }
        }
    }
}

/** A found title as a title of the player (the page address is its path; the stream comes when it plays). */
fun WebHit.asTrack() = Track(key, title, artist, album ?: "", duration = duration, path = url, artUrl = thumbnail)

/** "Aus dem Netz" in the Mediathek: what is being loaded right now (with source and progress) and everything loaded. */
@Composable
fun WebScreen(state: AppState) {
    val ink = Ink
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { Variant.engine(context) } ?: return
    LaunchedEffect(Unit) { WebDownloads.load(context); WebDownloads.start(context) }
    val library = remember { WebLibrary.get(context) }
    val generation = WebLibrary.generation
    val (load, _) = rememberLoad(generation) { library.scan(); library.tracks(10000).sortedByDescending { java.io.File(android.net.Uri.parse(it.path).path ?: "").lastModified() } }
    var version by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { version = withContext(Dispatchers.IO) { runCatching { engine.version() }.getOrNull() } }
    val open = WebDownloads.jobs.filter { it.state != WebJob.State.Done }
    val done = WebDownloads.jobs.count { it.state == WebJob.State.Done }
    Column(Modifier.fillMaxSize()) {
        NavBar(state)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = chromePadding()) {
            largeTitle(tr("Aus dem Netz"), topInset = false)
            if (open.isNotEmpty() || done > 0) item { SectionHeader("Downloads", if (done > 0) tr("Fertige ausblenden") else null) { WebDownloads.clearDone(context) } }
            items(open, key = { "j" + it.id }) { job -> JobRow(job) }
            if (open.isEmpty() && done > 0) item {
                Label(tr("Alles geladen."), 15f, color = ink.secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            val tracks = load.value.orEmpty()
            item { SectionHeader(tr("Geladen")) }
            if (tracks.isEmpty() && !load.loading) item {
                Label(Variant.text("netz-leer"),
                    15f, color = ink.secondary, lines = 4, modifier = Modifier.padding(horizontal = 16.dp))
            } else if (tracks.isNotEmpty()) item { PlayButtons(state, library, tracks) }
            itemsIndexed(tracks, key = { _, t -> "t" + t.id }) { i, track ->
                TrackRow(state, library, track, subtitle = listOf(track.artist, track.album).filter { it.isNotEmpty() }.joinToString(" · ")) {
                    state.playback.play(library, tracks, i) }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 24.dp).clip(RoundedCornerShape(10.dp)).background(ink.card)) {
                    ListRow(Variant.text("lader"), version ?: "…", height = 56.dp, trailing = {
                        Label(if (updating) tr("Wird aktualisiert …") else "Aktualisieren", 17f, color = ink.tint, modifier = Modifier.clickable(role = Role.Button, enabled = !updating) {
                            updating = true
                            scope.launch { version = withContext(Dispatchers.IO) { runCatching { engine.update() }.getOrElse { tr("Aktualisieren ging nicht") } }; updating = false }
                        })
                    })
                    ListRow("Speicherort", WebDownloads.folder(context).absolutePath.substringAfter("/Android/").let { tr("Android/{it}", "it" to it) }, height = 56.dp, separator = false)
                }
                Label(tr("Nur für den privaten Gebrauch. Die Seiten ändern sich oft – geht etwas nicht mehr, hilft meist „Aktualisieren“."),
                    13f, color = ink.secondary, lines = 3, modifier = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 24.dp))
            }
        }
    }
}

/** One title in the queue: cover, title, source and what is happening ("37 % · noch 12 s", "Wartet", the error in words). */
@Composable
private fun JobRow(job: WebJob) {
    val ink = Ink
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(job.hit.thumbnail, Modifier.size(48.dp), 5.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            Label(job.hit.title, 17f)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                SourceBadge(job.hit.source)
                Label(when (job.state) {
                    WebJob.State.Waiting -> "Wartet"
                    WebJob.State.Failed -> "Fehlgeschlagen"
                    else -> WebDownloads.progress[job.id].let { WebDownloads.noteOf(job) }
                }, 13f, color = if (job.state == WebJob.State.Failed) Red else ink.secondary, modifier = Modifier.padding(start = 6.dp))
            }
            // The reason in German; the loader's own line stays in the queue file for questions.
            if (job.state == WebJob.State.Failed) Label(job.note.substringBefore("\n"), 13f, color = ink.secondary, lines = 3)
        }
        WebLoadButton(job.hit)
        if (job.state == WebJob.State.Failed) Box(Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button) { WebDownloads.remove(context, job.id) }
            .semantics { contentDescription = tr("Aus der Liste nehmen") }, contentAlignment = Alignment.Center) { SymbolIcon(Symbol.Close, ink.secondary, 14.dp) }
    }
}

/** A title of a source in the internet as something to load ("Aus dem Netz"). */
fun Track.asHit(server: MusicServer) = WebHit(path ?: Variant.watch(id), title, artist, album.ifEmpty { null }, duration,
    server.cover(this, 544))

/** Album/playlist from the internet: the App Store's ↓ in the navigation bar loads all its titles; a ring while they come in. */
@Composable
fun WebListButton(hits: List<WebHit>, state: AppState? = null, playlist: String? = null, cover: String? = null) {
    val ink = Ink
    val context = LocalContext.current
    val jobs = hits.mapNotNull { WebDownloads.jobFor(it.key) }
    val done = jobs.count { it.state == WebJob.State.Done }
    val busy = jobs.any { it.state == WebJob.State.Waiting || it.state == WebJob.State.Loading || it.state == WebJob.State.Converting }
    // Olaf 05.10.2026: a list that is loading can be stopped – a tap asks, like the server lists' button.
    var asking by remember { mutableStateOf(false) }
    if (asking) Ask(tr("Laden abbrechen?"), tr("Was schon geladen ist, bleibt auf dem Telefon."), tr("Laden stoppen"), onYes = {
        asking = false
        // Waiting ones first, so the loader doesn't pick up the next title while the running one is stopped.
        val open = jobs.filter { it.state != WebJob.State.Done && it.state != WebJob.State.Failed }
        open.sortedBy { if (it.state == WebJob.State.Waiting) 0 else 1 }.forEach { WebDownloads.remove(context, it.id, deleteFile = true) }
    }, onNo = { asking = false })
    // Card 55744d14 (Olaf 06.10.2026): a playlist from the internet can come along as a playlist on the own server – switch on
    // by default. Only where the own server can take it (Emby/Jellyfin with the LiDio-Lader).
    val account = state?.account
    val canServer = playlist != null && Variant.PRIVATE && account != null && (account.kind == ServerKind.Emby || account.kind == ServerKind.Jellyfin)
    var choosing by remember { mutableStateOf(false) }
    var asPlaylist by remember { mutableStateOf(true) }
    if (choosing && state != null && playlist != null) MenuSheet(tr("Laden"), playlist, { choosing = false }) {
        ListRow(tr("Als Playlist auf den Server"), tr("In dieser Reihenfolge, mit Bild"), height = 60.dp,
            trailing = { IosSwitch(asPlaylist, tr("Als Playlist auf den Server")) { asPlaylist = it } })
        MenuRow(tr("Alle {size} laden", "size" to hits.size), Symbol.Downloaded, last = true) {
            choosing = false
            ListToServer.start(context, state, playlist, cover, hits, asPlaylist)
        }
    }
    Box(Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button, enabled = busy || done < hits.size) {
        if (busy) asking = true else if (canServer) choosing = true else WebDownloads.add(context, hits) }
        .semantics { contentDescription = when { done == hits.size -> tr("Alle geladen"); busy -> tr("Wird geladen, {done} von {size} – antippen bricht ab", "done" to done, "size" to hits.size); else -> tr("Alle {size} laden", "size" to hits.size) } },
        contentAlignment = Alignment.Center) {
        when {
            hits.isNotEmpty() && done == hits.size -> SymbolIcon(Symbol.Check, ink.secondary, 18.dp, weight = 2.4f)
            busy -> ProgressRing(done.toFloat() / hits.size.coerceAtLeast(1), ink.tint)
            else -> SymbolIcon(Symbol.Downloaded, ink.tint, 26.dp)
        }
    }
}

/** Card 55744d14: a whole playlist from the internet onto the phone and the own server, optionally as a playlist there. What the
 *  server has comes from the server (no second copy); what it lacks comes from the internet onto the phone and – through the
 *  LiDio-Lader – into the library, at its place in the new playlist. Runs in the background; short notes at the top. */
object ListToServer {
    private val scope = kotlinx.coroutines.MainScope()

    fun start(context: android.content.Context, state: AppState, name: String, cover: String?, hits: List<WebHit>, asPlaylist: Boolean) {
        val account = state.account ?: return
        state.notice = tr("Sucht die Titel auf deinem Server …")
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    val address = state.address.ifEmpty { account.address }
                    val own = state.serverFor(account.copy(address = address))
                    val results = Matcher.run(own, hits.map { Wanted(it.title, it.artist, it.album ?: "", it.duration) })
                    val found = results.mapIndexedNotNull { i, r -> r.track?.takeIf { r.match == Match.Found }?.let { i to it } }
                    val missing = hits.indices.filter { i -> found.none { it.first == i } }
                    // On the phone: the server's copies of what it has, the internet's of the rest.
                    if (found.isNotEmpty()) Offline.download(context, account.id, own, found.map { it.second })
                    WebDownloads.add(context, missing.map { hits[it] })
                    val lader = Lader.available(account, address)
                    var made: Playlist? = null
                    if (asPlaylist) {
                        made = own.createPlaylist(name, found.map { it.second })
                        val lines = missing.map { i -> "${i + 1} · ${hits[i].artist} – ${hits[i].title}" }
                        if (lines.isNotEmpty()) own.noteMissing(made.id, lines)
                        cover?.let { url -> runCatching { java.net.URL(url).openStream().use { it.readBytes() } }.getOrNull()?.let { own.setPlaylistCover(made.id, it) } }
                    }
                    // The missing ones into the library – in ascending order, so each lands at its place in the playlist.
                    if (lader) missing.forEach { i ->
                        val line = "${i + 1} · ${hits[i].artist} – ${hits[i].title}"
                        runCatching { Lader.load(account, address, hits[i], made?.id, if (made != null) line else null, if (made != null) i + 1 else null) }
                    }
                    withContext(Dispatchers.Main) {
                        state.notice = (if (made != null) tr("„{name}“ auf dem Server angelegt", "name" to name) + " – " else "") +
                            tr("{found} vom Server, {missing} aus dem Netz", "found" to found.size, "missing" to missing.size) +
                            if (missing.isNotEmpty() && !lader) tr(" (der Server lädt nichts nach – kein LiDio-Lader)") else ""
                        state.generation++
                    }
                }.exceptionOrNull()
            }
            if (error != null) state.notice = error.message ?: tr("Laden ging nicht.")
        }
    }
}

/** The playlist's titles with the missing ones put back in their places (line "12 · …" = 12th); lines without a place at the end. */
fun withMissing(tracks: List<Track>, missing: List<String>): List<Any> {
    val out = tracks.toMutableList<Any>()
    missing.mapNotNull { l -> MissingNote.position(l)?.let { it to l } }.sortedBy { it.first }
        .forEach { (pos, l) -> out.add((pos - 1).coerceIn(0, out.size), l) }
    out.addAll(missing.filter { MissingNote.position(it) == null })
    return out
}

/** Found once, kept: the best web title for a missing line (cover, length, address), on the phone (card 02ec07ef). */
object MissingHits {
    private val memory = java.util.concurrent.ConcurrentHashMap<String, Track>()
    private val none = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun file(context: android.content.Context) = java.io.File(java.io.File(context.filesDir, "netz").apply { mkdirs() }, "treffer.json")
    @Volatile private var loaded = false
    @Synchronized private fun load(context: android.content.Context) {
        if (loaded) return
        loaded = true
        runCatching { org.json.JSONObject(file(context).readText()).let { j -> j.keys().forEach { k -> memory[k] = Index.decode(j.getJSONObject(k)).copy(path = j.getJSONObject(k).str("path")) } } }
    }
    @Synchronized private fun save(context: android.content.Context) {
        val j = org.json.JSONObject(); memory.forEach { (k, t) -> j.put(k, Index.encode(t).put("path", t.path)) }
        runCatching { file(context).writeText(j.toString()) }
    }
    fun known(context: android.content.Context, line: String): Track? { load(context); return memory[MissingNote.label(line)] }
    fun nothing(line: String) = MissingNote.label(line) in none
    /** A title already known from the source (an album's list): no search needed. */
    fun remember(context: android.content.Context, line: String, track: Track) {
        load(context); if (memory.put(MissingNote.label(line), track) == null) save(context)
    }
    /** Blocking: looks the title up at the music source (only where the variant has one). */
    fun find(context: android.content.Context, line: String): Track? {
        known(context, line)?.let { return it }
        val server = Variant.webServer(context) ?: return null
        val w = MissingNote.wanted(line)
        val t = runCatching { server.search(listOf(w.artist, w.title).filter { it.isNotEmpty() }.joinToString(" ")).tracks.firstOrNull() }.getOrNull()
        if (t == null) none += MissingNote.label(line) else { memory[MissingNote.label(line)] = t; save(context) }
        return t
    }
}

/** Card c569f2c1 (Olaf 06.10.2026: "die fehlenden Lieder müssen gelistet werden und zum download angeboten werden"):
 *  LiDio privat looks the album up at its music source; what the own server lacks of it stands grey at its place, like the
 *  missing titles of a playlist ("3 · Interpret – Titel"). The source's own titles are remembered, so cover and length are
 *  there at once and a tap plays exactly that version. Kept per album for the session. */
object AlbumGaps {
    /** What the server lacks ("3 · Interpret – Titel") and every title's place on the album (normalised title → 1, 2, …) –
     *  titles the LiDio-Lader brought have no track number, the order comes from the source then. */
    data class Gaps(val missing: List<String> = emptyList(), val order: Map<String, Int> = emptyMap())
    private val memory = java.util.concurrent.ConcurrentHashMap<String, Gaps>()

    /** Blocking. Empty when the variant has no music source, the album isn't found there, or nothing is missing. */
    fun find(context: android.content.Context, server: MusicServer, album: Album, tracks: List<Track>): Gaps {
        if (server.kind == ServerKind.Web || album.title.isBlank()) return Gaps()
        val cacheKey = "${album.id}:${tracks.size}"
        memory[cacheKey]?.let { return it }
        val web = Variant.webServer(context) ?: return Gaps()
        val artist = album.artist.ifBlank { tracks.firstOrNull()?.artist ?: "" }
        val title = Matcher.normal(album.title)
        val match = runCatching { web.search("$artist ${album.title}").albums }.getOrDefault(emptyList())
            .filter { Matcher.similar(it.title, album.title) >= 0.85 || (title.length >= 4 && Matcher.normal(it.title).startsWith(title)) }
            .maxByOrNull { Matcher.similar(it.title, album.title) + Matcher.artistScore(artist, it.artist) }
            ?.takeIf { Matcher.artistScore(artist, it.artist) >= 0.5 } ?: return Gaps().also { memory[cacheKey] = it }
        val online = runCatching { web.album(match.id).second }.getOrDefault(emptyList())
        val have = tracks.map { Matcher.normal(it.title) }
        val missing = online.withIndex().filter { (_, t) -> have.none { h -> h == Matcher.normal(t.title) || Matcher.similar(h, t.title) >= 0.9 } }
            .map { (i, t) -> "${i + 1} · ${t.artist.ifBlank { artist }} – ${t.title}" to t }
        missing.forEach { (line, t) -> MissingHits.remember(context, line, t) }
        val order = online.withIndex().associate { (i, t) -> Matcher.normal(t.title) to i + 1 }
        return Gaps(missing.map { it.first }, order).also { memory[cacheKey] = it }
    }

    /** The server's titles in the album's order: their own number first, else their place at the source. */
    fun sorted(tracks: List<Track>, order: Map<String, Int>): List<Track> =
        if (order.isEmpty() || tracks.all { it.number != null }) tracks
        else tracks.withIndex().sortedBy { (i, t) -> order[Matcher.normal(t.title)] ?: t.number ?: (1000 + i) }.map { it.value }
}

/** Above the playlist when titles are missing: how many, and (LiDio privat) "Alle laden". */
@Composable
fun MissingHeader(state: AppState, missing: List<String>) {
    val context = LocalContext.current
    var asking by remember { mutableStateOf<String?>(null) }
    // Covers and lengths of the missing titles come in at once, one after the other, in the background (card 02ec07ef).
    LaunchedEffect(missing) {
        if (Variant.PRIVATE) missing.forEach { line ->
            if (MissingHits.known(context, line) == null) { withContext(Dispatchers.IO) { MissingHits.find(context, line) }; MissingTick.value.intValue++ }
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Label("${missing.size} " + (if (missing.size == 1) tr("Titel fehlt") else tr("Titel fehlen")) + tr(" auf dem Server") +
            if (Variant.PRIVATE) tr(" – grau, mit ↓ zum Laden") else tr(" – grau"), 13f, color = Ink.secondary, lines = 2, modifier = Modifier.weight(1f))
        if (Variant.PRIVATE) Label(asking ?: tr("Alle laden"), 15f, 400, Ink.tint, Modifier.clickable(role = Role.Button, enabled = asking == null) {
            asking = tr("Sucht …")
            WebDownloads.addSearched(context, missing.map(MissingNote::wanted)) { done, total -> asking = if (done < total) "$done/$total" else tr("In der Warteschlange") }
        }.padding(start = 12.dp))
    }
}

/** Bumped when found covers arrive, so the grey rows redraw. */
object MissingTick { val value = androidx.compose.runtime.mutableIntStateOf(0) }

/** A missing title in its place: greyed like an unavailable title in Apple Music; cover and length as soon as known. LiDio privat:
 *  a tap plays it from the internet, ↓ loads it. */
@Composable
fun MissingTrackRow(state: AppState, line: String, playlistId: String? = null, dim: Boolean = true, number: Int? = null,
                    cover: String? = null, onPlay: ((Track) -> Unit)? = null) {
    val ink = Ink
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wanted = remember(line) { MissingNote.wanted(line) }
    val tick = MissingTick.value.intValue
    var found by remember(line, tick) { mutableStateOf(MissingHits.known(context, line)) }
    val web = remember { Variant.webServer(context) }
    // Loaded onto this phone (Olaf 05.10.2026): the row is a normal title now – full colour, the phone instead of the
    // cloud, a tap plays the file. It leaves the list of missing ones once the server has it.
    val onPhone = found?.let { WebDownloads.fileFor(it) } != null
    @Suppress("NAME_SHADOWING") val dim = dim && !onPhone
    // On the phone but not yet on the server: the LiDio-Lader fetches it quietly into the library and puts it at its
    // place in the playlist; when it is done, the playlist reloads and the row is an ordinary server title.
    if (onPhone && Variant.PRIVATE && playlistId != null) {
        val hit = found?.let { t -> web?.let { t.asHit(it) } }
        LaunchedEffect(hit?.key) {
            val a = state.account ?: return@LaunchedEffect
            if (hit == null) return@LaunchedEffect
            if (Lader.jobs[hit.key] == null) withContext(Dispatchers.IO) {
                runCatching { if (Lader.available(a, state.address)) Lader.load(a, state.address, hit, playlistId, line, MissingNote.position(line)) }
            }
            while (Lader.jobs[hit.key]?.first.let { it != null && it !in setOf("fertig", "fehler") }) {
                kotlinx.coroutines.delay(4000)
                withContext(Dispatchers.IO) { runCatching { Lader.poll(a, state.address) } }
            }
            if (Lader.jobs[hit.key]?.first == "fertig") { Keep.onServer(context, hit.key); state.generation++ }
        }
    }
    Row(Modifier.fillMaxWidth().clickable(enabled = web != null, role = Role.Button, onClickLabel = tr("Spielen und laden")) {
            scope.launch { (found ?: withContext(Dispatchers.IO) { MissingHits.find(context, line) })?.let { t -> found = t
                if (onPlay != null) onPlay(t) else web?.let { state.playback.play(it, listOf(t)) }
                // Olaf 05.10.2026: a tap on a missing title plays it AND loads it – onto the phone and onto the server.
                web?.let { w ->
                    val hit = t.asHit(w)
                    if (WebDownloads.fileFor(t) == null && WebDownloads.jobFor(hit.key).let { it == null || it.state == WebJob.State.Failed })
                        WebDownloads.add(context, listOf(hit))
                    val a = state.account
                    if (Variant.PRIVATE && a != null && playlistId != null) launch(Dispatchers.IO) {
                        runCatching { if (Lader.available(a, state.address)) Lader.load(a, state.address, hit, playlistId, line, MissingNote.position(line)) }
                    }
                }
            } } }
        .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp).semantics { contentDescription = "${wanted.title}${if (dim) ", fehlt auf dem Server" else ""}" },
        verticalAlignment = Alignment.CenterVertically) {
        number?.let { Label("$it", 15f, color = ink.secondary, tabular = true, modifier = Modifier.padding(end = 10.dp)) }
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(5.dp)).graphicsLayer { alpha = if (dim) 0.45f else 1f }) {
            Cover(found?.let { t -> web?.cover(t, 120) } ?: cover, Modifier.size(48.dp), 5.dp)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            val playing = found?.id != null && state.playback.current?.id == found?.id
            Label(wanted.title, 17f, color = if (playing) ink.tint else if (dim) ink.tertiary else ink.label)
            Label(wanted.artist.ifEmpty { found?.artist ?: "" }, 13f, color = if (dim) ink.tertiary else ink.secondary)
        }
        // Cloud: out in the internet. Crossed out (grey): nowhere to be had – the public LiDio loads nothing from the internet,
        // Nothing found at the music source, or loading failed (e.g. blocked in Germany).
        val failed = found?.let { t -> web?.let { w -> WebDownloads.jobFor(t.asHit(w).key)?.state == WebJob.State.Failed } } == true
        val nowhere = !onPhone && (web == null || MissingHits.nothing(line) || failed)
        if (onPhone) SymbolIcon(Symbol.Phone, ink.secondary, 14.dp, modifier = Modifier.padding(end = 8.dp).semantics { contentDescription = tr("Auf diesem Telefon") })
        else SymbolIcon(if (nowhere) Symbol.CloudOff else Symbol.Cloud, ink.tertiary, 15.dp, modifier = Modifier.padding(end = 8.dp)
            .semantics { contentDescription = if (nowhere) tr("Nirgends zu bekommen") else tr("Nicht auf dem Server – im Internet") })
        found?.duration?.takeIf { it > 0 }?.let { Label(duration(it), 13f, color = if (dim) ink.tertiary else ink.secondary, tabular = true, modifier = Modifier.padding(end = 4.dp)) }
        if (web != null && !onPhone) {
            val hit = found?.asHit(web)
            if (hit != null) WebLoadButton(hit, state, playlistId, line, MissingNote.position(line))
            else if (!MissingHits.nothing(line)) Box(Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button) {
                    scope.launch { withContext(Dispatchers.IO) { MissingHits.find(context, line) }?.let { t -> found = t; WebDownloads.add(context, listOf(t.asHit(web))) } } }
                .semantics { contentDescription = tr("Aus dem Netz laden") }, contentAlignment = Alignment.Center) { SymbolIcon(Symbol.Downloaded, ink.tint, 26.dp) }
        }
    }
}
