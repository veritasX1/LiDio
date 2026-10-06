package io.github.veritasx1.lidio

import io.github.veritasx1.lidio.i18n.tr

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val Green = Color(0xFF34C759)
val Orange = Color(0xFFFF9500)
val Red = Color(0xFFFF3B30)

/** "Playlist importieren" (card 4): a file or pasted text → compared with the server → a playlist of what's there, and
 *  the missing ones as text. Nothing is downloaded from anywhere. */
@Composable
fun ImportScreen(state: AppState, server: MusicServer, initial: List<Wanted> = emptyList(), initialName: String = "", cover: String? = null,
                 mixtape: Boolean = false) {
    val ink = Ink
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initialName) }
    var pasted by remember { mutableStateOf("") }
    var wanted by remember { mutableStateOf(initial) }
    var progress by remember { mutableIntStateOf(-1) }
    val results = remember { mutableStateListOf<Result>() }
    var filter by remember { mutableIntStateOf(0) }
    var choosing by remember { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val file = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: "Playlist"
        val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
        if (text == null) { message = tr("Die Datei ließ sich nicht lesen."); return@rememberLauncherForActivityResult }
        wanted = Lists.read(text, file); results.clear()
        if (name.isBlank()) name = file.substringBeforeLast('.')
        message = if (wanted.isEmpty()) tr("In der Datei steht keine Playlist, die LiDio lesen kann.") else null
    }

    fun compare() {
        results.clear(); progress = 0
        scope.launch {
            val found = withContext(Dispatchers.IO) { Matcher.run(server, wanted) { progress = it } }
            results.addAll(found); progress = -1
        }
    }
    fun create() {
        val tracks = results.mapNotNull { if (it.match != Match.Missing) it.track else null }
        if (tracks.isEmpty()) return
        scope.launch {
            val missing = results.filter { it.match == Match.Missing }.map { it.wanted.toString() }
            val made = withContext(Dispatchers.IO) { runCatching {
                server.createPlaylist(name.trim().ifEmpty { "Importiert" }, tracks).also { made ->
                    if (missing.isNotEmpty()) server.noteMissing(made.id, missing)
                    // A received Mixtape stays one on the own server, too (card c9b15c67).
                    if (mixtape) server.markMixtape(made.id)
                    // Olaf 05.10.2026: the playlist keeps the picture it has at Deezer/Spotify (best effort, quietly).
                    cover?.let { url -> runCatching { java.net.URL(url).openStream().use { it.readBytes() } }.getOrNull()?.let { server.setPlaylistCover(made.id, it) } }
                } } }
            made.onSuccess { state.back(); state.open(Route.PlaylistPage(it.id, it.name)) }
                .onFailure { message = (it as? ServerError)?.message ?: tr("Die Playlist ließ sich nicht anlegen.") }
        }
    }

    Column(Modifier.fillMaxSize()) {
        NavBar(state, tr("Importieren"))
        LazyColumn(Modifier.fillMaxSize().background(ink.grouped), contentPadding = chromePadding()) {
            largeTitle(tr("Playlist importieren"), topInset = false)
            item {
                Column(Modifier.padding(16.dp).clip(RoundedCornerShape(10.dp)).background(ink.card)) {
                    ListRow(tr("Datei wählen …"), tr("M3U, M3U8, PLS, XSPF, CSV oder Text"), onClick = { picker.launch(arrayOf("*/*")) }, height = 60.dp,
                        titleColor = ink.tint, leading = { SymbolIcon(Symbol.Import, ink.tint, 24.dp) })
                    Column(Modifier.padding(16.dp)) {
                        Label(tr("Oder Liste einfügen – eine Zeile je Titel, „Interpret – Titel“"), 13f, color = ink.secondary, lines = 2)
                        Box(Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(min = 88.dp).clip(RoundedCornerShape(8.dp)).background(ink.grouped).padding(10.dp)) {
                            if (pasted.isEmpty()) Label(tr("Die Testtöne – Quinte\nKreis Quartett – Spirale"), 15f, color = ink.tertiary, lines = 3)
                            BasicTextField(pasted, { text ->
                                pasted = text; results.clear()
                                // A Deezer or Spotify playlist link: fetch its titles (only if that source is switched on).
                                val link = Links.parse(text)
                                if (link != null) scope.launch {
                                    val got = withContext(Dispatchers.IO) { runCatching { RemoteTracks.load(state.playback.settings, RemoteList(link.first, link.second, "")) } }
                                    wanted = got.getOrNull()?.second.orEmpty()
                                    got.getOrNull()?.first?.name?.takeIf { it.isNotEmpty() && name.isEmpty() }?.let { name = it }
                                    message = got.exceptionOrNull()?.message ?: if (wanted.isEmpty()) tr("Die Playlist ließ sich nicht laden.") else null
                                }
                                else wanted = Lists.read(text)
                            }, textStyle = style(15f, color = ink.label),
                                cursorBrush = SolidColor(ink.tint), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Liste" })
                        }
                    }
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Label(tr("Name"), 17f, modifier = Modifier.width(72.dp))
                        Box(Modifier.weight(1f)) {
                            if (name.isEmpty()) Label(tr("Neue Playlist"), 17f, color = ink.tertiary)
                            BasicTextField(name, { name = it }, singleLine = true, textStyle = style(17f, color = ink.label), cursorBrush = SolidColor(ink.tint),
                                modifier = Modifier.fillMaxWidth().semantics { contentDescription = tr("Name der Playlist") })
                        }
                    }
                }
            }
            message?.let { item { Label(it, 15f, 500, Red, Modifier.padding(horizontal = 32.dp), lines = 3) } }
            if (wanted.isNotEmpty() && results.isEmpty()) item {
                Wide(if (progress >= 0) tr("Abgleich … {value} von {size}", "value" to (progress + 1), "size" to wanted.size) else tr("{size} Titel mit dem Server abgleichen", "size" to wanted.size), progress < 0) { compare() }
            }
            if (results.isNotEmpty()) {
                val found = results.count { it.match == Match.Found }; val unsure = results.count { it.match == Match.Unsure }
                val missing = results.count { it.match == Match.Missing }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Count(found, "gefunden", Green); Count(unsure, "unsicher", Orange); Count(missing, "fehlen", Red)
                    }
                }
                item { Wide(tr("Als Playlist anlegen ({value} Titel)", "value" to (found + unsure)), found + unsure > 0) { create() } }
                // LiDio privat: what the server lacks can come from the internet – the best match for each (card 1843f577).
                if (missing > 0 && Variant.PRIVATE) item {
                    var fetching by remember { mutableStateOf<String?>(null) }
                    Label(fetching ?: tr("Fehlende aus dem Netz laden ({missing})", "missing" to missing), 17f, 400, ink.tint, Modifier.fillMaxWidth().clickable(role = Role.Button, enabled = fetching == null) {
                        fetching = tr("Sucht bei {MUSIC} …", "MUSIC" to Variant.MUSIC)
                        WebDownloads.addSearched(context, results.filter { it.match == Match.Missing }.map { it.wanted }) { done, total ->
                            fetching = if (done < total) tr("Sucht bei {MUSIC} … {done} von {total}", "MUSIC" to Variant.MUSIC, "done" to done, "total" to total) else tr("{total} in der Warteschlange – siehe Mediathek → Aus dem Netz", "total" to total)
                        }
                    }.padding(12.dp), align = TextAlign.Center, lines = 2)
                }
                if (missing > 0) item {
                    Label(tr("Fehlende als Text teilen"), 17f, 400, ink.tint, Modifier.fillMaxWidth().clickable(role = Role.Button) {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Matcher.missingText(results))
                            .putExtra(Intent.EXTRA_SUBJECT, tr("Fehlt in „{value}“", "value" to (name.ifEmpty { "Playlist" })))
                        context.startActivity(Intent.createChooser(send, tr("Fehlende Titel teilen")))
                    }.padding(12.dp), align = TextAlign.Center)
                }
                item {
                    Segmented(listOf(tr("Alle"), tr("Unsicher"), "Fehlen"), filter, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { filter = it }
                }
                item {
                    Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(10.dp)).background(ink.card)) {
                        results.forEachIndexed { i, r ->
                            if (filter == 1 && r.match != Match.Unsure || filter == 2 && r.match != Match.Missing) return@forEachIndexed
                            val (symbol, colour) = when (r.match) { Match.Found -> Symbol.Check to Green; Match.Unsure -> Symbol.Missing to Orange; Match.Missing -> Symbol.Close to Red }
                            ListRow(r.wanted.toString(), when (r.match) {
                                    Match.Found -> "${r.track?.artist} – ${r.track?.title}"
                                    Match.Unsure -> tr("Vielleicht: {value} – {value2} · antippen zum Wählen", "value" to (r.track?.artist), "value2" to (r.track?.title))
                                    Match.Missing -> if (Variant.PRIVATE) tr("Nicht auf dem Server · antippen: im Netz suchen") else tr("Nicht auf dem Server")
                                }, height = 60.dp, onClick = when {
                                    r.match == Match.Missing && Variant.PRIVATE -> ({
                                        state.searchFor = listOf(r.wanted.artist, r.wanted.title).filter { it.isNotEmpty() }.joinToString(" ")
                                        state.searchWeb = true; state.stacks[Tab.Search] = emptyList(); state.tab = Tab.Search })
                                    r.match != Match.Found || r.choices.isNotEmpty() -> ({ choosing = i })
                                    else -> null
                                },
                                leading = { SymbolIcon(symbol, colour, 20.dp, weight = 2.4f) })
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    choosing?.let { i ->
        val r = results[i]
        androidx.compose.ui.window.Dialog(onDismissRequest = { choosing = null }) {
            Column(Modifier.clip(RoundedCornerShape(14.dp)).background(ink.elevated).padding(vertical = 8.dp)) {
                Label(r.wanted.toString(), 17f, 600, modifier = Modifier.padding(16.dp), lines = 2)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    itemsIndexed(r.choices) { _, t ->
                        ListRow(t.title, "${t.artist} · ${t.album}", height = 56.dp, onClick = { results[i] = r.copy(match = Match.Found, track = t); choosing = null })
                    }
                }
                ListRow(tr("Weglassen"), titleColor = Red, separator = false, onClick = { results[i] = r.copy(match = Match.Missing, track = null); choosing = null })
            }
        }
    }
}

@Composable
private fun Count(n: Int, label: String, colour: Color) {
    Row(Modifier.padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(colour))
        Label("$n $label", 15f, 600, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun Wide(label: String, enabled: Boolean, onClick: () -> Unit) {
    val ink = Ink
    Box(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (enabled) ink.tint else ink.fill)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
        Label(label, 17f, 600, Color.White)
    }
}
