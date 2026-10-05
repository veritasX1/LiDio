package io.github.veritasx1.lidio

import io.github.veritasx1.lidio.i18n.tr

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Card 81b01b4a, stage B – iOS 26's context menus, "Zur Playlist hinzufügen" and the pins at the top of the library. */

/** Something pinned at the top of the library (up to six, like iOS 26): an album or a playlist. */
data class Pin(val kind: String, val id: String, val name: String, val cover: String?, val web: Boolean = false)

object Pins {
    const val MAX = 6
    private fun prefs(context: Context) = context.getSharedPreferences("pins", Context.MODE_PRIVATE)
    fun all(context: Context, account: String): List<Pin> = runCatching {
        JSONArray(prefs(context).getString(account, "[]")).objects().map { Pin(it.getString("kind"), it.getString("id"), it.optString("name"), it.str("cover"), it.optBoolean("web")) }
    }.getOrDefault(emptyList())
    fun has(context: Context, account: String, id: String) = all(context, account).any { it.id == id }
    fun toggle(context: Context, account: String, pin: Pin): Boolean {
        val list = all(context, account)
        val next = if (list.any { it.id == pin.id }) list.filter { it.id != pin.id } else (list + pin).takeLast(MAX)
        prefs(context).edit().putString(account, JSONArray(next.map { JSONObject().put("kind", it.kind).put("id", it.id).put("name", it.name)
            .put("cover", it.cover).put("web", it.web) }).toString()).apply()
        return next.any { it.id == pin.id }
    }
}

/** An iOS menu as a dialog: a heading, then rows with their symbol on the right. */
@Composable
fun MenuSheet(title: String, subtitle: String?, onClose: () -> Unit, content: @Composable () -> Unit) {
    val ink = Ink
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(14.dp)).background(ink.elevated)) {
            Column(Modifier.padding(16.dp)) {
                Label(title, 15f, 600, lines = 2)
                subtitle?.let { Label(it, 13f, color = ink.secondary) }
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(ink.separator))
            content()
        }
    }
}

@Composable
fun MenuRow(label: String, symbol: Symbol, last: Boolean = false, color: androidx.compose.ui.graphics.Color = Ink.label, filled: Boolean = false, action: () -> Unit) =
    ListRow(label, onClick = action, separator = !last, titleColor = color, trailing = { SymbolIcon(symbol, color, 20.dp, filled = filled) })

/** Long press on an album or a playlist: play, shuffle, next, last, add to a playlist, pin. The titles come when needed. */
@Composable
fun CollectionMenu(state: AppState, server: MusicServer, pin: Pin, subtitle: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf<List<Track>?>(null) }
    val account = state.account?.id ?: ""
    fun withTracks(then: (List<Track>) -> Unit) = scope.launch {
        val tracks = withContext(Dispatchers.IO) { runCatching { if (pin.kind == "album") server.album(pin.id).second else server.playlist(pin.id).second }.getOrNull() }
        if (tracks.isNullOrEmpty()) state.notice = tr("„{name}“ ließ sich nicht laden.", "name" to pin.name) else then(tracks)
    }
    picking?.let { PlaylistPicker(state, server, it) { picking = null; onClose() }; return }
    MenuSheet(pin.name, subtitle, onClose) {
        MenuRow(tr("Wiedergabe"), Symbol.Play) { withTracks { state.playback.play(server, it) }; onClose() }
        MenuRow(tr("Zufall"), Symbol.Shuffle) { withTracks { state.playback.play(server, it, shuffled = true) }; onClose() }
        MenuRow(tr("Als Nächstes spielen"), Symbol.PlayNext) { withTracks { state.playback.playNextAll(server, it) }; onClose() }
        MenuRow(tr("Zuletzt spielen"), Symbol.PlayLast) { withTracks { state.playback.addAllToQueue(server, it) }; onClose() }
        MenuRow(tr("Zur Playlist hinzufügen …"), Symbol.Playlists) { withTracks { picking = it } }
        val pinned = Pins.has(context, account, pin.id)
        MenuRow(if (pinned) "Lösen" else tr("Anheften"), Symbol.Pin, last = true, filled = pinned) {
            val on = Pins.toggle(context, account, pin); state.generation++
            state.notice = if (on) tr("„{name}“ angeheftet – oben in der Mediathek.", "name" to pin.name) else tr("„{name}“ gelöst.", "name" to pin.name); onClose()
        }
    }
}

/** "Zur Playlist hinzufügen": the own playlists, or a new one (a name, then created with these titles). */
@Composable
fun PlaylistPicker(state: AppState, server: MusicServer, tracks: List<Track>, onClose: () -> Unit) {
    val ink = Ink
    val scope = rememberCoroutineScope()
    val (lists, _) = rememberLoad(server, "picker") { server.playlists().filter { server.kind != ServerKind.Web || it.id.startsWith("eigene-") } }
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    fun done(text: String) { state.notice = text; state.generation++; onClose() }
    MenuSheet(tr("Zur Playlist hinzufügen"), if (tracks.size == 1) tracks[0].title else tr("{size} Titel", "size" to tracks.size), onClose) {
        if (naming) {
            Row(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(ink.grouped).padding(10.dp)) {
                Box(Modifier.weight(1f)) {
                    if (name.isEmpty()) Label(tr("Name der Playlist"), 17f, color = ink.tertiary)
                    BasicTextField(name, { name = it }, singleLine = true, textStyle = style(17f, color = ink.label), cursorBrush = SolidColor(ink.tint),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = tr("Name der Playlist") })
                }
            }
            MenuRow("Anlegen", Symbol.Plus, last = true, color = ink.tint) {
                val n = name.trim().ifEmpty { tr("Neue Playlist") }
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { server.createPlaylist(n, tracks) }.isSuccess }
                    done(if (ok) tr("Playlist „{n}“ angelegt.", "n" to n) else tr("Die Playlist ließ sich nicht anlegen."))
                }
            }
            return@MenuSheet
        }
        MenuRow(tr("Neue Playlist …"), Symbol.Plus, color = ink.tint) { naming = true }
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(lists.value.orEmpty(), key = { it.id }) { p ->
                ListRow(p.name, onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { runCatching { server.addToPlaylist(p.id, tracks) }.getOrDefault(false) }
                        done(if (ok) tr("Zu „{name}“ hinzugefügt.", "name" to p.name) else tr("„{name}“ lässt sich hier nicht ändern.", "name" to p.name))
                    }
                }, leading = { Cover(server.cover(p.coverId, 120), Modifier.size(40.dp), 5.dp) }, height = 56.dp)
            }
        }
    }
}

/** The pins at the top of the library: up to six tiles in two rows of three (iOS 26). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PinnedGrid(state: AppState, server: MusicServer) {
    val context = LocalContext.current
    val pins = remember(state.generation, state.account?.id) { Pins.all(context, state.account?.id ?: "") }
    if (pins.isEmpty()) return
    var menu by remember { mutableStateOf<Pin?>(null) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        pins.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { p ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).combinedClickable(role = Role.Button, onLongClick = { menu = p }, onLongClickLabel = tr("Mehr")) {
                        state.open(if (p.kind == "album") Route.AlbumPage(p.id, web = p.web) else Route.PlaylistPage(p.id, p.name, web = p.web)) }) {
                        Cover(p.cover?.let { server.cover(it, 300) }, Modifier.fillMaxWidth().aspectRatio(1f), 10.dp,
                            fallback = if (p.kind == "album") ({ server.albumCoverFallback(p.id, 300) }) else null)
                        Label(p.name, 12f, 500, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    menu?.let { CollectionMenu(state, server, it, null) { menu = null } }
}

/** Card f3cba42b: "Freigeben" – a playlist stays private unless shared: with everyone on this server or with chosen people,
 *  each to listen ("Hören") or also to change it ("Bearbeiten"), as far as the server can (Navidrome: only everyone). */
@Composable
fun ShareSheet(state: AppState, server: MusicServer, playlist: Playlist, onClose: () -> Unit) {
    val ink = Ink
    val scope = rememberCoroutineScope()
    val (load, _) = rememberLoad(server, playlist.id, "freigabe") { server.sharing(playlist.id) ?: throw ServerError(tr("Dieser Server kann Playlists nicht freigeben.")) }
    var everyone by remember { mutableStateOf<Boolean?>(null) }
    val levels = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    val s = load.value
    if (s != null && everyone == null) { everyone = s.everyone; s.users.forEach { levels[it.id] = it.level } }
    MenuSheet(tr("„{name}“ freigeben", "name" to playlist.name), tr("Ohne Freigabe ist die Playlist nur für dich."), onClose) {
        when {
            load.loading -> Label(tr("Wird geladen …"), 15f, color = ink.secondary, modifier = Modifier.padding(16.dp))
            load.error != null -> Label(load.error, 15f, color = ink.secondary, lines = 3, modifier = Modifier.padding(16.dp))
            s != null && !s.canManage -> Label(tr("Diese Playlist gehört jemand anderem – freigeben kann nur, wer sie angelegt hat."), 15f,
                color = ink.secondary, lines = 3, modifier = Modifier.padding(16.dp))
            s != null -> {
                ListRow(tr("Alle auf diesem Server"), tr("Jeder Benutzer darf zuhören"), height = 60.dp,
                    trailing = { IosSwitch(everyone == true, tr("Alle auf diesem Server")) { on -> everyone = on
                        if (on) s.users.forEach { if (levels[it.id] == "none") levels[it.id] = "read" } } })
                if (s.perUser) LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(s.users, key = { it.id }) { u ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Label(u.name, 17f)
                            val options = listOf("none", "read", "write")
                            Segmented(listOf("Aus", "Hören", tr("Bearbeiten")), options.indexOf(levels[u.id] ?: "none").coerceAtLeast(0), Modifier.padding(top = 4.dp)) {
                                levels[u.id] = options[it]; if (options[it] == "none") everyone = false
                            }
                        }
                    }
                }
                MenuRow(tr("Fertig"), Symbol.Check, last = true, color = ink.tint) {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { server.share(playlist.id, levels.toMap(), everyone == true) }
                        val shared = everyone == true || levels.values.any { it != "none" }
                        state.notice = when {
                            !ok -> tr("Die Freigabe ließ sich nicht speichern.")
                            !shared -> tr("„{name}“ ist jetzt nur für dich.", "name" to playlist.name)
                            everyone == true -> tr("„{name}“ ist für alle auf diesem Server freigegeben.", "name" to playlist.name)
                            else -> tr("„{name}“ ist freigegeben für {value}.", "name" to playlist.name, "value" to (s.users.filter { levels[it.id] != "none" }.joinToString(", ") { it.name }))
                        }
                        onClose()
                    }
                }
            }
        }
    }
}
