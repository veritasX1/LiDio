package io.github.veritasx1.lidio

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
        .putExtra(Intent.EXTRA_SUBJECT, "„${track.title}“ in LiDio")
    context.startActivity(Intent.createChooser(send, "Lied teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
            ListRow("Als Nächstes spielen", onClick = { state.playback.playNext(server, track); onClose() }, trailing = { SymbolIcon(Symbol.PlayNext, ink.label, 20.dp) })
            ListRow("Zuletzt spielen", onClick = { state.playback.addToQueue(server, track); onClose() }, trailing = { SymbolIcon(Symbol.PlayLast, ink.label, 20.dp) })
            var picking by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            if (picking) { PlaylistPicker(state, server, listOf(track)) { picking = false; onClose() } }
            ListRow("Zur Playlist hinzufügen …", onClick = { picking = true }, trailing = { SymbolIcon(Symbol.Playlists, ink.label, 20.dp) })
            if (server.kind != ServerKind.Local && server.kind != ServerKind.Web && state.account?.let { Offline.stored(context, it.id, track) } == null)
                ListRow("Aufs Telefon laden", onClick = { state.account?.let { Offline.download(context, it.id, server, listOf(track)) }; onClose() },
                    trailing = { SymbolIcon(Symbol.Downloaded, ink.label, 20.dp) })
            val fav = state.playback.isFavorite(track)
            ListRow(if (fav) "Aus Favoriten entfernen" else "Favorit", onClick = { state.playback.toggleFavorite(track, server); onClose() },
                trailing = { SymbolIcon(Symbol.Star, ink.label, 20.dp, filled = fav) })
            val stored = state.account?.let { Offline.stored(context, it.id, track) }
            ListRow("Teilen …", separator = stored != null, onClick = { onClose(); scope.launch { shareTrack(context, state, server, track) } },
                trailing = { SymbolIcon(Symbol.Share, ink.label, 20.dp) })
            // Like iOS: "Download entfernen" in red, only when the title is on the phone.
            if (stored != null) ListRow("Download entfernen", separator = false, titleColor = Red, onClick = { Offline.remove(context, listOf(stored.first)); onClose() },
                trailing = { SymbolIcon(Symbol.Trash, Red, 20.dp) })
        }
    }
}
