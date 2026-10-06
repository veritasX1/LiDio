package io.github.veritasx1.lidio

import io.github.veritasx1.lidio.i18n.tr

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Card e1f44cfb (Olaf 06.10.2026): "Wenn ich Musik von meinen CDs rippe, will ich die Möglichkeit haben sie hochzuladen. Auch als
 *  Playlist … als einzelne Datei oder sogar als zip". Files go to the server's shared folder "Hochgeladen/<Name>" (Olaf: one folder
 *  for all, every user of the server sees the music), through the LiDio-Lader; then Emby reads them in, with a playlist if wanted.
 *  The upload goes on while the user looks elsewhere in the app. */
object Uploads {
    /** sub: the folder inside a chosen folder ("CD 2"), empty for single files. */
    class Item(val uri: Uri, val name: String, val size: Long, val folder: String = "", val root: String = "") {
        /** Its folder inside the batch: with several chosen folders each keeps its own name (a CD each). */
        val sub: String get() = if (items.map { it.root }.filter { it.isNotEmpty() }.distinct().size > 1) listOf(root, folder).filter { it.isNotEmpty() }.joinToString("/") else folder
        var progress by mutableStateOf(0f); var done by mutableStateOf(false)
    }
    /** The album an item belongs to: its folder, or – with several ZIPs – the ZIP's own name; "" = loose files / one album. */
    fun album(item: Item): String =
        if (item.name.endsWith(".zip", ignoreCase = true) && items.count { it.name.endsWith(".zip", ignoreCase = true) } > 1)
            listOf(item.sub, item.name.substringBeforeLast('.')).filter { it.isNotEmpty() }.joinToString("/")
        else item.sub
    fun albums(): List<String> = items.map(::album).distinct().sorted()
    /** Olaf 06.10.2026: "nicht jedes Album … als Playlist" – which albums become playlists; none unless switched on. */
    val asPlaylist = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    private val wanted = Regex("""(?i)\.(mp3|flac|m4a|aac|ogg|oga|opus|wav|aif|aiff|wma|ape|wv|mka|zip|jpg|jpeg|png)$""")

    /** Olaf 06.10.2026: "am besten ein Ordner" – e.g. an artist with three or four CDs: every file below it, each with its folder. */
    fun addFolder(context: Context, tree: Uri): String? {
        if (running) return null
        val root = android.provider.DocumentsContract.getTreeDocumentId(tree)
        val resolver = context.contentResolver
        var rootName: String? = null
        runCatching { resolver.query(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, root), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) rootName = it.getString(0) } }
        fun walk(doc: String, path: String, depth: Int) {
            if (depth > 4) return
            val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, doc)
            resolver.query(children, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE, android.provider.DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0); val name = c.getString(1) ?: continue
                    if (c.getString(2) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) walk(id, if (path.isEmpty()) name else "$path/$name", depth + 1)
                    else if (wanted.containsMatchIn(name)) {
                        val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        if (items.none { it.uri == uri }) items += Item(uri, name, c.getLong(3), path, rootName ?: root)
                    }
                }
            }
        }
        runCatching { walk(root, "", 0) }
        message = null
        return rootName
    }
    val items = mutableStateListOf<Item>()
    var running by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    private val scope = kotlinx.coroutines.MainScope()

    fun add(context: Context, uris: List<Uri>) {
        if (running) return
        uris.forEach { uri ->
            var name = uri.lastPathSegment ?: "Datei"; var size = 0L
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) { name = c.getString(0) ?: name; size = c.getLong(1) }
                }
            }
            if (items.none { it.uri == uri }) items += Item(uri, name, size)
        }
        message = null
    }

    fun start(context: Context, state: AppState, batch: String) {
        val account = state.account ?: return
        if (running || items.isEmpty() || batch.isBlank()) return
        running = true; message = tr("Wird hochgeladen …")
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    if (!Lader.available(account, state.address)) throw ServerError(tr("Braucht den LiDio-Lader auf dem Server."))
                    val several = items.count { it.name.endsWith(".zip", ignoreCase = true) } > 1
                    for (item in items.filter { !it.done }) {
                        val input = context.contentResolver.openInputStream(item.uri) ?: throw ServerError(tr("„{name}“ ließ sich nicht lesen.", "name" to item.name))
                        Lader.upload(account, state.address, batch.trim(), item.name, item.size, input, item.sub, several) { p -> scope.launch { item.progress = p } }
                        withContext(Dispatchers.Main) { item.progress = 1f; item.done = true }
                    }
                    val job = Lader.finishUpload(account, state.address, batch.trim(), albums().filter { asPlaylist[it] == true })
                    withContext(Dispatchers.Main) { message = tr("Hochgeladen – der Server liest ein …") }
                    // The server reads the files in (and makes the playlist); follow it a while.
                    repeat(120) {
                        delay(3000)
                        val (st, note) = Lader.status(account, state.address, job) ?: return@repeat
                        withContext(Dispatchers.Main) { message = note.ifEmpty { st } }
                        if (st == "fertig") return@runCatching
                        if (st == "fehler") throw ServerError(note)
                    }
                }.exceptionOrNull()
            }
            running = false
            if (error != null) message = error.message ?: tr("Hochladen ging nicht.")
            else { items.clear(); asPlaylist.clear(); state.generation++ }
        }
    }
}

@Composable
fun UploadScreen(state: AppState, server: MusicServer) {
    val ink = Ink
    val context = LocalContext.current
    var name by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        Uploads.add(context, uris)
        // A ZIP or the first file names the batch, when no name is given yet.
        if (name.isBlank()) Uploads.items.firstOrNull()?.name?.substringBeforeLast('.')?.replace(Regex("""^\d+[\s.\-_]*"""), "")?.let { name = it }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        tree ?: return@rememberLauncherForActivityResult
        val root = Uploads.addFolder(context, tree)
        if (name.isBlank() && root != null) name = root
    }
    Column(Modifier.fillMaxSize()) {
        NavBar(state)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = chromePadding()) {
            largeTitle(tr("Musik hochladen"), topInset = false)
            item {
                Label(tr("Eigene Musik, z. B. gerippte CDs, auf deinen Server – einzelne Dateien, ZIPs oder ein ganzer Ordner. Alle, die auf dem Server Musik hören, sehen sie danach."),
                    15f, color = ink.secondary, lines = 4, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            item {
                Column(Modifier.padding(16.dp).clip(RoundedCornerShape(10.dp)).background(ink.card)) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Label(tr("Name"), 17f, modifier = Modifier.width(72.dp))
                        Box(Modifier.weight(1f)) {
                            if (name.isEmpty()) Label(tr("Album, CD oder Künstler"), 17f, color = ink.tertiary)
                            BasicTextField(name, { name = it.take(80) }, singleLine = true, textStyle = style(17f, color = ink.label), cursorBrush = SolidColor(ink.tint),
                                modifier = Modifier.fillMaxWidth().semantics { contentDescription = tr("Name des Albums oder der CD") })
                        }
                    }
                }
            }
            // Playlists only where wanted (off by default): one switch for one album, else one per album.
            val albums = Uploads.albums()
            if (albums.isNotEmpty()) item {
                Label(if (albums.size == 1) tr("PLAYLIST") else tr("ALS PLAYLIST ANLEGEN"), 13f, color = ink.secondary, modifier = Modifier.padding(start = 32.dp, top = 8.dp, bottom = 6.dp))
                Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(10.dp)).background(ink.card)) {
                    albums.forEachIndexed { i, a ->
                        val label = if (albums.size == 1) tr("Als Playlist anlegen") else a.ifEmpty { name.ifBlank { tr("Lose Titel") } }.substringAfterLast('/')
                        ListRow(label, if (albums.size == 1) tr("In der Reihenfolge der CD") else tr("{n} Dateien", "n" to Uploads.items.count { Uploads.album(it) == a }),
                            height = 56.dp, separator = i < albums.lastIndex,
                            trailing = { IosSwitch(Uploads.asPlaylist[a] == true, label) { Uploads.asPlaylist[a] = it } })
                    }
                }
            }
            item {
                // Several at once: files (a loose collection), several ZIPs (one CD each) – or a whole folder with its CD folders.
                ListRow(tr("Dateien oder ZIPs auswählen …"), onClick = if (Uploads.running) null else ({ picker.launch(arrayOf("audio/*", "application/zip", "image/jpeg", "image/png")) }),
                    titleColor = ink.tint, leading = { Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) { SymbolIcon(Symbol.Plus, ink.tint, 22.dp) } })
                ListRow(tr("Ordner auswählen …"), tr("Mit Unterordnern – jeder Ordner wird ein Album"), onClick = if (Uploads.running) null else ({ folder.launch(null) }),
                    titleColor = ink.tint, height = 60.dp, leading = { Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) { SymbolIcon(Symbol.Albums, ink.tint, 22.dp) } })
            }
            itemsIndexed(Uploads.items, key = { _, it -> it.uri.toString() }) { _, item ->
                ListRow(item.name, listOfNotNull(item.sub.ifEmpty { null }, if (item.size > 0) "%.1f MB".format(item.size / 1048576.0) else null).joinToString(" · "), height = 56.dp,
                    trailing = { if (item.done) SymbolIcon(Symbol.Check, ink.secondary, 18.dp) else if (Uploads.running) ProgressRing(item.progress, ink.tint, 22.dp) })
            }
            item {
                Spacer(Modifier.height(12.dp))
                val ready = !Uploads.running && Uploads.items.isNotEmpty() && name.isNotBlank()
                Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(50.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (ready) ink.tint else ink.fill).clickable(enabled = ready, role = Role.Button) { Uploads.start(context, state, name) },
                    contentAlignment = Alignment.Center) {
                    Label(if (Uploads.running) tr("Lädt hoch …") else tr("Hochladen"), 17f, 600, if (ready) androidx.compose.ui.graphics.Color.White else ink.tertiary)
                }
            }
            Uploads.message?.let { item { Label(it, 15f, color = ink.secondary, lines = 4, modifier = Modifier.padding(16.dp)) } }
        }
    }
}
