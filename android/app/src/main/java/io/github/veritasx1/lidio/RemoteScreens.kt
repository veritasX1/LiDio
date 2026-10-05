package io.github.veritasx1.lidio

import io.github.veritasx1.lidio.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Arrangement
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** The source mark: a small neutral capsule with the name (no foreign logos). */
@Composable
fun SourceMark(source: Source) {
    val ink = Ink
    val colour = when (source) { Source.Server -> ink.tint; Source.Deezer -> Color(0xFFA238FF); Source.Spotify -> Color(0xFF1DB954) }
    Box(Modifier.clip(RoundedCornerShape(5.dp)).border(1.dp, colour, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 1.dp)
        .semantics { contentDescription = tr("Quelle: {label}", "label" to source.label) }) { Label(source.label, 11f, 700, colour) }
}

/** Search: "Playlists" from the own server and – when switched on – Deezer and Spotify, each with its mark. */
fun LazyListScope.publicPlaylists(state: AppState, server: MusicServer, query: String, own: List<Playlist>, remote: Load<List<RemoteList>>?, onEnableDeezer: () -> Unit) {
    val hasRemote = state.playback.settings.publicSources().isNotEmpty()
    if (own.isEmpty() && remote?.value.isNullOrEmpty() && hasRemote && remote?.loading != true) return
    item { SectionHeader(tr("Playlists")) }
    items(own, key = { "op" + it.id }) { p ->
        ListRow(p.name, tr("{trackCount} Titel", "trackCount" to p.trackCount), onClick = { state.open(Route.PlaylistPage(p.id, p.name, web = server.kind == ServerKind.Web)) }, height = 68.dp,
            leading = { Cover(server.cover(p.coverId, 160), Modifier.size(56.dp), 6.dp) }, trailing = { SourceMark(Source.Server) })
    }
    remote?.value?.let { list ->
        items(list, key = { "r" + it.source + it.id }) { p ->
            ListRow(p.name, listOfNotNull(p.owner.takeIf { it.isNotEmpty() }, tr("{count} Titel", "count" to p.count)).joinToString(" · "),
                onClick = { state.open(Route.RemotePage(p)) }, height = 68.dp,
                leading = { Cover(p.cover, Modifier.size(56.dp), 6.dp) }, trailing = { SourceMark(p.source) })
        }
    }
    remote?.error?.let { item { Label(it, 13f, color = Red, modifier = Modifier.padding(horizontal = 16.dp), lines = 2) } }
    if (!hasRemote) item {
        Column(Modifier.padding(16.dp).clip(RoundedCornerShape(10.dp)).background(Ink.card).clickable(role = Role.Button, onClick = onEnableDeezer).padding(14.dp)) {
            Label(tr("Auch öffentliche Playlists bei Deezer suchen"), 17f, 400, Ink.tint)
            Label(tr("Deine Suchbegriffe gehen dann an Deezer. Abschalten jederzeit unter Mediathek → Server."), 13f, color = Ink.secondary, lines = 3)
        }
    }
}

/** A public playlist: its titles as the source lists them; "Übertragen" compares them with the own server (card 4). */
@Composable
fun RemoteScreen(state: AppState, server: MusicServer, route: Route.RemotePage) {
    val ink = Ink
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val (load, retry) = rememberLoad(route.list.source, route.list.id) { RemoteTracks.load(state.playback.settings, route.list) }
    // A pasted or shared link knows only the id – name, owner and picture come with the titles.
    val list = load.value?.first ?: route.list
    val web = androidx.compose.runtime.remember { Variant.webServer(context) }
    /** Card 970f3578: playing from here – through the variant's music source (one title found after the other, the first plays at once);
     *  the public LiDio from the own server, as far as it has the titles. */
    fun playAll(wanted: List<Wanted>, from: Int = 0, shuffled: Boolean = false, started: Track? = null) {
        val order = (if (shuffled) wanted.shuffled() else wanted.drop(from) + wanted.take(from))
        scope.launch {
            if (web != null) {
                val lines = order.map { it.toString() }
                // A tapped title already plays (started): the others follow behind it.
                val first = started ?: lines.firstNotNullOfOrNull { l -> withContext(Dispatchers.IO) { MissingHits.find(context, l) } }
                    ?: run { state.notice = tr("Bei {MUSIC} nichts davon gefunden.", "MUSIC" to Variant.MUSIC); return@launch }
                if (started == null) state.playback.play(web, listOf(first))
                lines.forEach { l -> withContext(Dispatchers.IO) { MissingHits.find(context, l) }?.let { t ->
                    if (t.id != first.id && state.playback.current != null) state.playback.addToQueue(web, t) } }
            } else {
                state.notice = tr("Sucht die Titel auf deinem Server …")
                val found = withContext(Dispatchers.IO) { Matcher.run(server, order) }.filter { it.match == Match.Found }.mapNotNull { it.track }
                if (found.isEmpty()) state.notice = tr("Keiner dieser Titel liegt auf deinem Server.") else state.playback.play(server, found)
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        // The transfer is a small symbol now, like ↓ on an album: the server with an arrow (Olaf 05.10.2026).
        NavBar(state, trailing = load.value?.second?.let { wanted -> {
            Box(Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .clickable(role = Role.Button, onClickLabel = tr("Auf den Server übertragen")) { state.open(Route.ImportWith(list.name, wanted, list.cover)) }
                .semantics { contentDescription = tr("Auf den Server übertragen") }, contentAlignment = Alignment.Center) { SymbolIcon(Symbol.ServerDown, ink.tint, 24.dp) }
        } })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = chromePadding()) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Cover(list.cover, Modifier.size(240.dp), 10.dp)
                    Label(list.name, 22f, 700, modifier = Modifier.padding(top = 16.dp, start = 24.dp, end = 24.dp), lines = 2, align = TextAlign.Center)
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SourceMark(list.source)
                        if (list.owner.isNotEmpty()) Label(tr("  von {owner}", "owner" to list.owner), 13f, color = ink.secondary)
                    }
                }
            }
            loading(load, retry) { (_, wanted) ->
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Capsule(Symbol.Play, tr("Wiedergabe"), Modifier.weight(1f)) { playAll(wanted) }
                        Capsule(Symbol.Shuffle, tr("Zufall"), Modifier.weight(1f)) { playAll(wanted, shuffled = true) }
                    }
                    Label(if (web != null) tr("Antippen spielt einen Titel über {MUSIC}, ↓ lädt ihn. Oben rechts überträgst du die ganze Playlist auf deinen Server.", "MUSIC" to Variant.MUSIC)
                        else tr("Antippen spielt einen Titel von deinem Server, wenn er dort liegt. Oben rechts überträgst du die Playlist auf deinen Server."),
                        13f, color = ink.secondary, lines = 3, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                }
                if (web != null) item {
                    // Covers and lengths come in one after the other in the background, without tapping.
                    androidx.compose.runtime.LaunchedEffect(wanted) {
                        wanted.forEach { w -> if (MissingHits.known(context, w.toString()) == null) {
                            withContext(Dispatchers.IO) { MissingHits.find(context, w.toString()) }; MissingTick.value.intValue++ } }
                    }
                }
                if (web != null) itemsIndexed(wanted, key = { i, w -> "w$i$w" }) { i, w ->
                    MissingTrackRow(state, w.toString(), dim = false, number = i + 1, cover = w.cover) { t -> state.playback.play(web, listOf(t)); playAll(wanted.drop(i + 1), started = t) }
                }
                else itemsIndexed(wanted) { i, w -> ListRow(w.title, w.artist, height = 60.dp, leading = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Label("${i + 1}", 15f, color = ink.secondary, tabular = true, modifier = Modifier.padding(end = 10.dp))
                            Cover(w.cover, Modifier.size(48.dp), 5.dp)
                        } },
                    onClick = {
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { Matcher.run(server, listOf(w)) }.first()
                            val t = r.track
                            if (r.match != Match.Missing && t != null) state.playback.play(server, listOf(t)) else state.notice = tr("„{title}“ liegt nicht auf deinem Server.", "title" to w.title)
                        }
                    },
                    trailing = {
                        SymbolIcon(Symbol.Cloud, ink.tertiary, 15.dp, modifier = Modifier.padding(end = 8.dp).semantics { contentDescription = tr("Im Internet") })
                        if (w.seconds > 0) Label(duration(w.seconds), 13f, color = ink.secondary, tabular = true)
                    }) }
            }
        }
    }
}

/** Settings: Deezer on/off, Spotify's own app. */
@Composable
fun PublicSettings(state: AppState) {
    val ink = Ink
    val settings = state.playback.settings
    var deezer by remember { mutableStateOf(settings.deezer) }
    var id by remember { mutableStateOf(settings.spotifyId) }
    var secret by remember { mutableStateOf(settings.spotifySecret) }
    Label(tr("ÖFFENTLICHE PLAYLISTS"), 13f, color = ink.secondary, modifier = Modifier.padding(start = 32.dp, bottom = 6.dp))
    Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(10.dp)).background(ink.card)) {
        ListRow(tr("Deezer durchsuchen"), tr("Ohne Konto – Suchbegriffe gehen an Deezer"), height = 60.dp,
            trailing = { IosSwitch(deezer, tr("Deezer durchsuchen")) { deezer = it; settings.deezer = it } })
        for ((label, value, set) in listOf(Triple("Spotify-Client-ID", id, { v: String -> id = v; settings.spotifyId = v }),
            Triple("Spotify-Secret", secret, { v: String -> secret = v; settings.spotifySecret = v }))) {
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(label, 15f, modifier = Modifier.padding(end = 12.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Label("leer", 15f, color = ink.tertiary)
                    BasicTextField(value, set, singleLine = true, textStyle = style(15f, color = ink.label), cursorBrush = SolidColor(ink.tint),
                        visualTransformation = if (label.endsWith("Secret")) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
                }
            }
        }
    }
    Label(tr("Für Spotify brauchst du eine eigene App unter developer.spotify.com (Client-ID und Secret). Spotifys eigene Redaktions-Playlists sind für neue Apps gesperrt, Playlists von Nutzern gehen. Das Secret bleibt verschlüsselt auf dem Gerät."),
        13f, color = ink.secondary, lines = 6, modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp, bottom = 20.dp))
}
