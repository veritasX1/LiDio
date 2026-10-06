package io.github.veritasx1.lidio

import io.github.veritasx1.lidio.i18n.tr

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shares a title as a link through Android's share sheet (Signal, mail …) – only where the user sends it. */
suspend fun shareTrack(context: Context, state: AppState, server: MusicServer, track: Track) {
    val account = state.account ?: return
    val id = withContext(Dispatchers.IO) { state.shareId(account, server) }
    val shared = Shared.of(id, track)
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shared.text())
        .putExtra(Intent.EXTRA_SUBJECT, tr("„{title}“ in LiDio", "title" to track.title))
    context.startActivity(Intent.createChooser(send, tr("Lied teilen")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** "Als Datei teilen" (Olaf 05.10.2026): the song itself as "Interpret - Titel.mp3" with its details inside. Card da219e16:
 *  it failed now and then, because only the server's MP3 conversion was tried (slow or refused for some files) and a title on
 *  the phone was fetched again. Now in this order, the first that works: the file on the phone (download, completely heard,
 *  or loaded from the internet) → the server's MP3 → the original file from the server. Only to the chosen app. */
suspend fun shareFile(context: Context, state: AppState, server: MusicServer, track: Track) {
    val name = "${track.artist} - ${track.title}".replace(Regex("""[\\/:*?"<>|]+"""), "_").trim().take(120)
    val dir = java.io.File(context.cacheDir, "teilen").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
    state.notice = tr("Wird vorbereitet …")
    val file = withContext(Dispatchers.IO) {
        val suffix = (track.suffix ?: "mp3").lowercase().takeIf { it.matches(Regex("[a-z0-9]{2,5}")) } ?: "mp3"
        fun fetch(url: String, out: java.io.File) = runCatching {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 180_000
            try {
                if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
                c.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
            } finally { c.disconnect() }
            out.takeIf { it.length() > 0 }
        }.onFailure { android.util.Log.w("LiDio", "Teilen als Datei: $url", it) }.getOrNull()
        val account = state.account
        val local = WebDownloads.fileFor(track)
            ?: track.path?.takeIf { server.kind == ServerKind.Local || it.startsWith("/") }?.let { java.io.File(it) }?.takeIf { it.isFile }
        val picked = track.path?.takeIf { server.kind == ServerKind.Local && it.startsWith("content:") }?.let { path ->
            runCatching { java.io.File(dir, "$name.$suffix").also { out ->
                context.contentResolver.openInputStream(android.net.Uri.parse(path))!!.use { i -> out.outputStream().use { i.copyTo(it) } } } }.getOrNull() }
        local?.let { java.io.File(dir, "$name.${it.extension}").also { out -> it.copyTo(out, overwrite = true) } }
            ?: picked
            ?: account?.let { Offline.stored(context, it.id, track) }?.let { (key, uri) ->
                java.io.File(dir, "$name.$suffix").takeIf { Offline.copyOut(context, key, uri, it) } }
            ?: server.mp3Url(track)?.let { fetch(it, java.io.File(dir, "$name.mp3")) }
            ?: server.streamUrl(track, 0).takeIf { it.startsWith("http") }?.let { fetch(it, java.io.File(dir, "$name.$suffix")) }
    }
    if (file == null || file.length() == 0L) { state.notice = tr("Die Datei ließ sich nicht holen."); return }
    state.notice = null
    val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".teilen", file)
    val send = Intent(Intent.ACTION_SEND).setType(if (file.extension == "mp3") "audio/mpeg" else "audio/*")
        .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, tr("Lied teilen")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Sharing outlives the menu it starts from (the menu closes at once; preparing a file takes a few seconds). */
private val shareScope = kotlinx.coroutines.MainScope()

/** Teilen: as a link to LiDio, or as the song file – like a small iOS sheet. */
@Composable
fun ShareChoice(state: AppState, server: MusicServer, track: Track, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = shareScope
    MenuSheet(tr("Teilen"), "${track.artist} – ${track.title}", onClose) {
        MenuRow(tr("Link teilen"), Symbol.Share) { onClose(); scope.launch { shareTrack(context, state, server, track) } }
        MenuRow(tr("Als Datei teilen"), Symbol.Downloaded) { onClose(); scope.launch { shareFile(context, state, server, track) } }
    }
}

/** The context menu of a title (long press), like iOS: play next, play last, share, remove the download. */
@Composable
fun TrackMenu(state: AppState, server: MusicServer, track: Track, onClose: () -> Unit) {
    val ink = Ink
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(14.dp)).background(ink.elevated)) {
            Column(Modifier.padding(16.dp)) {
                Label(track.title, 15f, 600, lines = 2)
                Label(track.artist, 13f, color = ink.secondary)
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(ink.separator))
            ListRow(tr("Als Nächstes spielen"), onClick = { state.playback.playNext(server, track); onClose() }, trailing = { SymbolIcon(Symbol.PlayNext, ink.label, 20.dp) })
            ListRow(tr("Zuletzt spielen"), onClick = { state.playback.addToQueue(server, track); onClose() }, trailing = { SymbolIcon(Symbol.PlayLast, ink.label, 20.dp) })
            var picking by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            if (picking) { PlaylistPicker(state, server, listOf(track)) { picking = false; onClose() } }
            ListRow(tr("Zur Playlist hinzufügen …"), onClick = { picking = true }, trailing = { SymbolIcon(Symbol.Playlists, ink.label, 20.dp) })
            if (server.kind != ServerKind.Local && server.kind != ServerKind.Web && state.account?.let { Offline.stored(context, it.id, track) } == null)
                ListRow(tr("Aufs Telefon laden"), onClick = { state.account?.let { Offline.download(context, it.id, server, listOf(track)) }; onClose() },
                    trailing = { SymbolIcon(Symbol.Downloaded, ink.label, 20.dp) })
            val fav = state.playback.isFavorite(track)
            ListRow(if (fav) tr("Aus Favoriten entfernen") else "Favorit", onClick = { state.playback.toggleFavorite(track, server); onClose() },
                trailing = { SymbolIcon(Symbol.Star, ink.label, 20.dp, filled = fav) })
            val stored = state.account?.let { Offline.stored(context, it.id, track) }
            var sharing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            if (sharing) ShareChoice(state, server, track) { sharing = false; onClose() }
            ListRow(tr("Teilen …"), separator = stored != null, onClick = { sharing = true },
                trailing = { SymbolIcon(Symbol.Share, ink.label, 20.dp) })
            // Like iOS: "Download entfernen" in red, only when the title is on the phone.
            if (stored != null) ListRow(tr("Download entfernen"), separator = false, titleColor = Red, onClick = { Offline.remove(context, listOf(stored.first)); onClose() },
                trailing = { SymbolIcon(Symbol.Trash, Red, 20.dp) })
        }
    }
}
