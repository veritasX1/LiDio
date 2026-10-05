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
    val job = WebDownloads.jobs.lastOrNull { it.hit.key == hit.key }
    // Card a984b806: ↓ asks where to – this phone or the own server (LiDio-Lader on the Pi), like an iOS action sheet.
    var asking by remember { mutableStateOf(false) }
    var laderThere by remember { mutableStateOf<Boolean?>(null) }
    val server = Lader.jobs[hit.key]
    if (server != null) {
        val (st, note) = server
        LaunchedEffect(st) {
            while (state != null && st !in setOf("fertig", "fehler")) {
                kotlinx.coroutines.delay(3000)
                val a = state.account ?: break
                withContext(Dispatchers.IO) { Lader.poll(a, state.address) }
                if (Lader.jobs[hit.key]?.first != st) break
            }
            if (st == "fertig") { state?.generation = (state?.generation ?: 0) + 1 }
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
            null -> if (state != null && Variant.PRIVATE) asking = true else WebDownloads.add(context, listOf(hit))
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
            else -> ProgressRing(if (job.state == WebJob.State.Loading) job.progress else null, ink.tint)
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
        drawArc(ink.fill, 0f, 360f, false, Offset(w / 2, w / 2), r, style = Stroke(w))
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
            if (WebDownloads.jobs.none { it.hit.key == hit.key && it.state != WebJob.State.Failed }) WebDownloads.add(context, listOf(hit)) }
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
                val open = hits.filter { h -> WebDownloads.jobs.none { it.hit.key == h.key && it.state != WebJob.State.Failed } }
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
                    else -> job.note
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
fun WebListButton(hits: List<WebHit>) {
    val ink = Ink
    val context = LocalContext.current
    val jobs = WebDownloads.jobs.filter { j -> hits.any { it.key == j.hit.key } }
    val done = jobs.count { it.state == WebJob.State.Done }
    val busy = jobs.any { it.state == WebJob.State.Waiting || it.state == WebJob.State.Loading || it.state == WebJob.State.Converting }
    Box(Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button, enabled = !busy && done < hits.size) { WebDownloads.add(context, hits) }
        .semantics { contentDescription = when { done == hits.size -> tr("Alle geladen"); busy -> tr("Wird geladen, {done} von {size}", "done" to done, "size" to hits.size); else -> tr("Alle {size} laden", "size" to hits.size) } },
        contentAlignment = Alignment.Center) {
        when {
            hits.isNotEmpty() && done == hits.size -> SymbolIcon(Symbol.Check, ink.secondary, 18.dp, weight = 2.4f)
            busy -> ProgressRing(done.toFloat() / hits.size.coerceAtLeast(1), ink.tint)
            else -> SymbolIcon(Symbol.Downloaded, ink.tint, 26.dp)
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
    Row(Modifier.fillMaxWidth().clickable(enabled = web != null, role = Role.Button, onClickLabel = tr("Aus dem Netz spielen")) {
            scope.launch { (found ?: withContext(Dispatchers.IO) { MissingHits.find(context, line) })?.let { t -> found = t
                if (onPlay != null) onPlay(t) else web?.let { state.playback.play(it, listOf(t)) } } } }
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
        val failed = found?.let { t -> web?.let { w -> WebDownloads.jobs.lastOrNull { it.hit.key == t.asHit(w).key }?.state == WebJob.State.Failed } } == true
        val nowhere = web == null || MissingHits.nothing(line) || failed
        SymbolIcon(if (nowhere) Symbol.CloudOff else Symbol.Cloud, ink.tertiary, 15.dp, modifier = Modifier.padding(end = 8.dp)
            .semantics { contentDescription = if (nowhere) tr("Nirgends zu bekommen") else tr("Nicht auf dem Server – im Internet") })
        found?.duration?.takeIf { it > 0 }?.let { Label(duration(it), 13f, color = if (dim) ink.tertiary else ink.secondary, tabular = true, modifier = Modifier.padding(end = 4.dp)) }
        if (web != null) {
            val hit = found?.asHit(web)
            if (hit != null) WebLoadButton(hit, state, playlistId, line, MissingNote.position(line))
            else if (!MissingHits.nothing(line)) Box(Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button) {
                    scope.launch { withContext(Dispatchers.IO) { MissingHits.find(context, line) }?.let { t -> found = t; WebDownloads.add(context, listOf(t.asHit(web))) } } }
                .semantics { contentDescription = tr("Aus dem Netz laden") }, contentAlignment = Alignment.Center) { SymbolIcon(Symbol.Downloaded, ink.tint, 26.dp) }
        }
    }
}
