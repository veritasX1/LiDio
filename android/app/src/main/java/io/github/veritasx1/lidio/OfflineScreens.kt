package io.github.veritasx1.lidio

import io.github.veritasx1.lidio.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.delay

/** How many of these titles are downloaded, and how many are on their way (checked every second while visible). */
@Composable
fun rememberDownloadState(account: String, tracks: List<Track>): Pair<Int, Int> {
    val context = LocalContext.current
    var done by remember(tracks) { mutableIntStateOf(0) }
    var running by remember(tracks) { mutableIntStateOf(0) }
    LaunchedEffect(tracks, account) {
        while (true) {
            val keys = tracks.map { Offline.downloadKey(account, it) }.toSet()
            done = keys.count { Index.entry(context, it) != null }
            running = runCatching { Offline.manager(context).currentDownloads.count { it.request.id in keys } }.getOrDefault(0)
            delay(1000)
        }
    }
    return done to running
}

/** Apple Music's download button for an album or playlist: arrow → progress → check (tap again to remove). */
@Composable
fun DownloadButton(state: AppState, server: MusicServer, tracks: List<Track>) {
    val ink = Ink
    val context = LocalContext.current
    val account = state.account?.id ?: return
    val (done, running) = rememberDownloadState(account, tracks)
    var asking by remember { mutableStateOf(false) }
    // A title may stand twice in a playlist – it is one download (2Pac Top 30: 26 rows, 25 titles), so count distinct titles.
    val total = remember(tracks) { tracks.distinctBy { it.id }.size }
    val complete = total > 0 && done >= total
    val label = when { complete -> tr("Geladen – entfernen"); running > 0 -> tr("Wird geladen, {done} von {total}", "done" to done, "total" to total); else -> tr("Laden") }
    Box(Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = label) {
        // Like the App Store: tapping while it loads stops it (and removes what was loaded of this list).
        if (complete || running > 0 || done > 0) asking = true else Offline.download(context, account, server, tracks)
    }.semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        when {
            complete -> SymbolIcon(Symbol.Check, ink.tint, 20.dp, weight = 2.6f)
            running > 0 || done > 0 -> Label("$done/$total", 12f, 600, ink.tint, tabular = true)
            else -> SymbolIcon(Symbol.Downloaded, ink.tint, 24.dp)
        }
    }
    if (asking) Ask(if (complete) tr("Geladene Titel entfernen?") else tr("Laden abbrechen?"),
        if (complete) tr("Sie bleiben auf dem Server und lassen sich jederzeit wieder laden.") else tr("Was schon geladen ist, wird wieder entfernt. Auf dem Server bleibt alles."),
        if (complete) tr("Entfernen") else tr("Laden stoppen"),
        onYes = { asking = false; Offline.remove(context, tracks.map { Offline.downloadKey(account, it) }) }, onNo = { asking = false })
}

/** "Geladen" in the Mediathek: what is on the phone – works without network. */
@Composable
fun DownloadedScreen(state: AppState, server: MusicServer) {
    val ink = Ink
    val context = LocalContext.current
    val account = state.account?.id ?: return
    var kind by remember { mutableIntStateOf(0) }
    var version by remember { mutableIntStateOf(0) }
    val titles = remember(account, kind, version) {
        Offline.titles(context, account).filter { it.second == (if (kind == 0) Index.DOWNLOADED else Index.HEARD) }.map { it.first }
    }
    LaunchedEffect(Unit) { while (true) { delay(2000); version++ } }
    // "Geladen": albums in the order they came in, their titles by number (disc, then track); a heading only for albums with
    // more than one title – a single title under its own album heading looked like the same song twice (card 29c6affc).
    // "Zuletzt gehört": simply the titles, last heard first, like iOS. Single titles go via the long-press menu.
    val albums = if (kind == 1) titles.map { listOf(it) }
        else titles.groupBy { it.albumId ?: it.album }.values.map { list -> list.sortedWith(compareBy({ it.disc ?: 1 }, { it.number ?: 0 })) }
    val ordered = albums.flatten()
    Column(Modifier.fillMaxSize()) {
        NavBar(state)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = chromePadding()) {
            largeTitle(tr("Geladen"), topInset = false)
            item {
                Segmented(listOf(tr("Geladen"), tr("Zuletzt gehört")), kind, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { kind = it }
            }
            if (titles.isEmpty()) item {
                Label(if (kind == 0) tr("Noch nichts geladen. Tippe auf einem Album oder einer Playlist auf den Pfeil.")
                    else tr("Hier landet, was du gehört hast – wenn „Gehörtes behalten“ an ist."), 15f, color = ink.secondary, lines = 3,
                    modifier = Modifier.padding(16.dp))
            } else item {
                PlayButtons(state, server, ordered)
                Label(tr("Halte einen Titel gedrückt, um ihn vom Gerät zu entfernen."), 13f, color = ink.secondary, lines = 2,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            albums.forEach { list ->
                if (list.size > 1) item(key = "h" + list.first().id) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(server.cover(list.first(), 160), Modifier.size(44.dp), 6.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Label(list.first().album.ifEmpty { tr("Einzelne Titel") }, 17f, 600)
                            Label(list.first().albumArtist ?: list.first().artist, 13f, color = ink.secondary)
                        }
                        Label(tr("Entfernen"), 15f, 400, Red, Modifier.clickable(role = Role.Button) {
                            Offline.remove(context, list.mapNotNull { t -> Offline.stored(context, account, t)?.first })
                            version++
                        })
                    }
                }
                items(list, key = { "t" + it.id }) { track ->
                    TrackRow(state, server, track, subtitle = if (list.size > 1) track.artist else listOf(track.artist, track.album).filter { it.isNotEmpty() }.joinToString(" · ")) {
                        state.playback.play(server, ordered, ordered.indexOf(track)) }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** The offline part of the settings (Server page). */
@Composable
fun OfflineSettings(state: AppState) {
    val ink = Ink
    val context = LocalContext.current
    val settings = state.playback.settings
    var keep by remember { mutableStateOf(settings.keepPlayed) }
    var wifi by remember { mutableStateOf(settings.wifiOnly) }
    var limit by remember { mutableIntStateOf(settings.keepLimitMb) }
    var usage by remember { mutableStateOf(0L to 0L) }
    LaunchedEffect(Unit) { while (true) { usage = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Offline.usage(context) }; delay(2000) } }
    Label(tr("OFFLINE"), 13f, color = ink.secondary, modifier = Modifier.padding(start = 32.dp, bottom = 6.dp))
    Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(10.dp)).background(ink.card)) {
        ListRow(tr("Gehörtes behalten"), tr("Was du hörst, wird im Hintergrund ganz geladen"), height = 60.dp,
            trailing = { IosSwitch(keep, tr("Gehörtes behalten")) { keep = it; settings.keepPlayed = it } })
        if (keep) Column(Modifier.padding(start = 16.dp, end = 12.dp, bottom = 10.dp)) {
            Label("Höchstens", 15f, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
            Segmented(Settings.LIMITS.map(Settings::size), Settings.LIMITS.indexOf(limit).coerceAtLeast(0)) { limit = Settings.LIMITS[it]; settings.keepLimitMb = limit }
        }
        ListRow(tr("Nur über WLAN laden"), height = 48.dp, trailing = { IosSwitch(wifi, tr("Nur über WLAN laden")) {
            wifi = it; settings.wifiOnly = it; Offline.manager(context).requirements = Offline.requirements(context) } })
        ListRow("Belegt", tr("Geladen {mb} · Gehört {mb2}", "mb" to (mb(usage.first)), "mb2" to (mb(usage.second))), height = 60.dp, separator = true)
        ListRow(tr("Gehörtes löschen"), onClick = { Offline.clearHeard(context) }, titleColor = Color(0xFFFF3B30), separator = false)
    }
    Label(tr("Eine neue Höchstgrenze gilt ab dem nächsten Start von LiDio. Geladenes bleibt, bis du es entfernst."), 13f, color = ink.secondary,
        lines = 3, modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp, bottom = 20.dp))
}

fun mb(bytes: Long) = if (bytes >= 1024L * 1024 * 1024) "%.1f GB".format(bytes / 1073741824.0).replace('.', ',') else "${bytes / 1048576} MB"

/** An iOS alert with two choices. */
@Composable
fun Ask(title: String, body: String, yes: String, onYes: () -> Unit, onNo: () -> Unit) {
    val ink = Ink
    androidx.compose.ui.window.Dialog(onDismissRequest = onNo) {
        Column(Modifier.clip(RoundedCornerShape(14.dp)).background(ink.elevated), horizontalAlignment = Alignment.CenterHorizontally) {
            Label(title, 17f, 600, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp), lines = 2)
            Label(body, 13f, modifier = Modifier.padding(16.dp), lines = 4, align = androidx.compose.ui.text.style.TextAlign.Center)
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(ink.separator))
            Row(Modifier.fillMaxWidth()) {
                Label(tr("Abbrechen"), 17f, 400, ink.tint, Modifier.weight(1f).clickable(role = Role.Button, onClick = onNo).padding(vertical = 12.dp),
                    align = androidx.compose.ui.text.style.TextAlign.Center)
                Label(yes, 17f, 600, Color(0xFFFF3B30), Modifier.weight(1f).clickable(role = Role.Button, onClick = onYes).padding(vertical = 12.dp),
                    align = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}
