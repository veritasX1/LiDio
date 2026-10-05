package io.github.veritasx1.lidio

import io.github.veritasx1.lidio.i18n.tr

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What a music file says about itself. */
data class Tags(val title: String?, val artist: String?, val albumArtist: String?, val album: String?, val number: Int?, val disc: Int?,
                val year: Int?, val genre: String?, val seconds: Int, val picture: ByteArray?)

/** Reads a file's tags; Android's MediaMetadataRetriever (MP3, M4A, FLAC, Ogg, Opus, WAV …). Replaced in tests. */
fun interface TagReader { fun read(context: Context, uri: Uri): Tags? }

/** Android's tag reader sometimes takes UTF-8 tags for an 8-bit charset ("SĂśhne" for "Söhne"). If the text, turned back
 *  into those bytes, is valid UTF-8 with non-ASCII letters, that was the real text. Plain text never passes this test. */
fun repair(text: String?): String? {
    if (text == null || text.all { it.code < 0x80 }) return text
    for (name in listOf("ISO-8859-1", "ISO-8859-2", "windows-1252", "windows-1250")) {
        val charset = runCatching { java.nio.charset.Charset.forName(name) }.getOrNull() ?: continue
        if (!charset.newEncoder().canEncode(text)) continue
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        val fixed = runCatching { decoder.decode(java.nio.ByteBuffer.wrap(text.toByteArray(charset))).toString() }.getOrNull() ?: continue
        if (fixed != text && fixed.any { it.code >= 0x80 }) return fixed
    }
    return text
}

val AndroidTags = TagReader { context, uri ->
    runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(context, uri)
            fun t(key: Int) = repair(r.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() })
            fun n(key: Int) = t(key)?.substringBefore('/')?.trim()?.toIntOrNull()
            Tags(t(MediaMetadataRetriever.METADATA_KEY_TITLE), t(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                t(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST), t(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                n(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER), n(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER),
                t(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4)?.toIntOrNull() ?: t(MediaMetadataRetriever.METADATA_KEY_DATE)?.take(4)?.toIntOrNull(),
                t(MediaMetadataRetriever.METADATA_KEY_GENRE),
                ((t(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000).toInt(), r.embeddedPicture)
        }
    }.getOrNull()
}

/** Folders on the phone (card 2d0c9407): chosen with Android's folder picker – LiDio sees only those, no storage permission.
 *  The index (filesDir/lokal/<account>.json) holds every title with its tags; a new scan reads only changed files. Covers
 *  come from the file or a cover/folder picture beside it. Plays without network; counts plays itself. */
class LocalLibrary(private val context: Context, private val accountId: String, private val folders: List<Uri>,
                   private val tags: TagReader = AndroidTags) : MusicServer {
    override val kind = ServerKind.Local
    private val dir = File(context.filesDir, "lokal").apply { mkdirs() }
    private val indexFile = File(dir, "$accountId.json")
    private val coverDir = File(dir, "cover").apply { mkdirs() }
    @Volatile private var cache: List<Track>? = null

    companion object {
        /** Raised when tag reading changes, so existing indexes are read again. */
        const val VERSION = 6
        val AUDIO = setOf("mp3", "m4a", "m4b", "mp4", "aac", "flac", "ogg", "oga", "opus", "wav", "mka", "webm", "ac3", "dts", "alac",
            "wma", "ape", "wv", "dsf", "dff", "mpc", "tak", "tta", "aiff", "aif", "mid", "midi", "kar", "rmi")
        val PICTURES = listOf("cover", "folder", "front", "albumart")
        fun encode(folders: List<Uri>) = JSONArray(folders.map { it.toString() }).toString()
        fun decode(text: String): List<Uri> = runCatching { JSONArray(text).let { a -> (0 until a.length()).map { Uri.parse(a.getString(it)) } } }.getOrDefault(emptyList())
    }

    // ---------- scanning ----------

    private class Entry(val uri: Uri, val name: String, val modified: Long, val size: Long, val folder: String, val root: String)

    /** All audio files below the folders (DocumentsContract queries – much faster than DocumentFile). */
    private fun files(progress: (Int) -> Unit): Pair<List<Entry>, Map<String, Uri>> {
        val out = mutableListOf<Entry>(); val pictures = mutableMapOf<String, Uri>()
        fun walk(tree: Uri, documentId: String, path: String) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE),
                null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0); val name = c.getString(1) ?: continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) { if (!name.startsWith(".")) walk(tree, id, "$path/$name"); continue }
                    val ext = name.substringAfterLast('.', "").lowercase()
                    when {
                        ext in AUDIO -> { out += Entry(uri, name, c.getLong(3), c.getLong(4), path, tree.toString()); if (out.size % 50 == 0) progress(out.size) }
                        ext in setOf("jpg", "jpeg", "png") && PICTURES.any { name.lowercase().startsWith(it) } -> pictures.putIfAbsent(path, uri)
                    }
                }
            }
        }
        // LiDio's own folders (file://, e.g. "Aus dem Netz" in LiDio privat) are plain files.
        fun walkFile(dir: File, path: String, root: String) {
            dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                if (f.isDirectory) { if (!f.name.startsWith(".")) walkFile(f, "$path/${f.name}", root); return@forEach }
                val ext = f.extension.lowercase()
                when {
                    ext in AUDIO && !f.name.endsWith(".part") -> out += Entry(Uri.fromFile(f), f.name, f.lastModified(), f.length(), path, root)
                    ext in setOf("jpg", "jpeg", "png") && PICTURES.any { f.name.lowercase().startsWith(it) } -> pictures.putIfAbsent(path, Uri.fromFile(f))
                }
            }
        }
        folders.forEach { tree ->
            if (tree.scheme == "file") runCatching { walkFile(File(tree.path!!), tree.toString(), tree.toString()) }
            else runCatching { walk(tree, DocumentsContract.getTreeDocumentId(tree), tree.toString()) }
        }
        return out to pictures
    }

    /** Scans the folders; unchanged files keep their entry. Returns the number of titles. */
    @Synchronized fun scan(progress: (Int) -> Unit = {}): Int {
        val old = load().associateBy { it.path }
        // A new way of reading tags (VERSION) reads every file again.
        val fresh = context.getSharedPreferences("lokal-$accountId", Context.MODE_PRIVATE).getInt("version", 0) == VERSION
        val (entries, pictures) = files(progress)
        val tracks = entries.mapIndexed { i, e ->
            if (i % 25 == 0) progress(i)
            val known = old[e.uri.toString()]
            if (known != null && fresh && known.size == e.size && stamp(known) == e.modified) known else read(e, pictures)
        }
        save(tracks)
        context.getSharedPreferences("lokal-$accountId", Context.MODE_PRIVATE).edit().putInt("version", VERSION).apply()
        return tracks.size
    }

    private val stamps = mutableMapOf<String, Long>()
    private fun stamp(t: Track) = stamps[t.path] ?: 0L

    private fun read(e: Entry, pictures: Map<String, Uri>): Track {
        val suffix = e.name.substringAfterLast('.', "").lowercase()
        // Android's reader for what it knows, LiDio's FFmpeg for the rest – and for the gaps Android leaves (year, cover).
        val midi = suffix in Midi.SUFFIXES
        val android = if (Formats.playable(suffix)) tags.read(context, e.uri) else null
        val ffmpeg = if (!midi && (android == null || android.year == null || android.picture == null)) Ffmpeg.tags(context, e.uri) else null
        // MIDI: title from the file name, length from Sonivox.
        val tag = if (midi) Tags(null, null, null, null, null, null, null, "MIDI", Midi.seconds(context, e.uri), null) else merge(android, ffmpeg)
        val base = e.name.substringBeforeLast('.')
        val split = Lists.split(base.replace(Regex("""^\d{1,3}[ ._-]+"""), ""))
        // Without tags the usual order of folders: …/Interpret/Album/Datei (or "Interpret - Album").
        val parts = e.folder.removePrefix(e.root).split('/').filter { it.isNotEmpty() }
        val folderAlbum = parts.lastOrNull() ?: ""
        val folderArtist = if (folderAlbum.contains(" - ")) folderAlbum.substringBefore(" - ") else parts.getOrNull(parts.size - 2) ?: ""
        val artist = tag?.artist ?: split.artist.ifEmpty { folderArtist.ifEmpty { tr("Unbekannt") } }
        val albumArtist = tag?.albumArtist ?: tag?.artist ?: folderArtist.ifEmpty { artist }
        val album = tag?.album ?: folderAlbum.substringAfter(" - ")
        val albumId = SubsonicServer.md5("$albumArtist|$album").take(16)
        // Cover: the file's own picture, else one beside it; kept once per album.
        val coverFile = File(coverDir, "$albumId.jpg")
        if (!coverFile.exists()) {
            tag?.picture?.let { coverFile.writeBytes(it) }
                ?: pictures[e.folder]?.let { uri -> runCatching { context.contentResolver.openInputStream(uri)?.use { coverFile.writeBytes(it.readBytes()) } } }
        }
        stamps[e.uri.toString()] = e.modified
        return Track(SubsonicServer.md5(e.uri.toString()).take(20), tag?.title ?: split.title.ifEmpty { base }, artist, album, albumId,
            tag?.seconds ?: 0, tag?.number ?: Regex("""^(\d{1,3})""").find(base)?.value?.toIntOrNull(), tag?.disc, tag?.year,
            if (coverFile.exists()) albumId else null, e.uri.toString(), e.size, e.name.substringAfterLast('.', "").lowercase(),
            albumArtist = albumArtist)
    }

    private fun albumArtist(t: Track) = t.albumArtist ?: t.artist

    private fun merge(a: Tags?, b: Tags?): Tags? = if (a == null) b else if (b == null) a else Tags(a.title ?: b.title, a.artist ?: b.artist,
        a.albumArtist ?: b.albumArtist, a.album ?: b.album, a.number ?: b.number, a.disc ?: b.disc, a.year ?: b.year, a.genre ?: b.genre,
        if (a.seconds > 0) a.seconds else b.seconds, a.picture ?: b.picture)

    private fun load(): List<Track> = cache ?: runCatching {
        JSONArray(indexFile.readText()).objects().map { j ->
            Index.decode(j).copy(path = j.str("path"), size = j.optLong("size"), suffix = j.str("suffix"))
                .also { stamps[it.path ?: ""] = j.optLong("modified") }
        }
    }.getOrDefault(emptyList()).also { cache = it }

    private fun save(tracks: List<Track>) {
        val json = JSONArray()
        tracks.forEach { t ->
            json.put(Index.encode(t).put("path", t.path).put("size", t.size).put("suffix", t.suffix).put("modified", stamps[t.path] ?: 0L))
        }
        indexFile.writeText(json.toString())
        cache = tracks
    }

    fun count() = load().size

    // ---------- the library ----------

    private val plays get() = context.getSharedPreferences("lokal-$accountId", Context.MODE_PRIVATE)

    override fun check() = tr("Auf diesem Gerät · {count} Titel", "count" to (count()))

    private fun albumsOf(tracks: List<Track>): List<Album> = tracks.groupBy { it.albumId }.map { (id, list) ->
        val first = list.first()
        val artists = list.map { albumArtist(it) }.distinct()
        Album(id ?: "", first.album.ifEmpty { tr("Einzelne Titel") }, if (artists.size == 1) artists[0] else tr("Verschiedene Interpreten"),
            SubsonicServer.md5(artists.first()).take(16), list.mapNotNull { it.year }.maxOrNull(), first.coverId, list.size, list.sumOf { it.duration },
            compilation = artists.size > 1)
    }

    override fun artists(): List<Artist> = load().groupBy { albumArtist(it) }.map { (name, list) ->
        Artist(SubsonicServer.md5(name).take(16), name, list.map { it.albumId }.distinct().size, list.firstNotNullOfOrNull { it.coverId })
    }.sortedBy { it.name.lowercase() }

    override fun artist(id: String): Pair<Artist, List<Album>> {
        val artist = artists().firstOrNull { it.id == id } ?: throw ServerError(tr("Nicht gefunden."))
        return artist to albumsOf(load().filter { albumArtist(it) == artist.name }).sortedByDescending { it.year ?: 0 }
    }

    override fun albums(order: AlbumOrder, size: Int, offset: Int): List<Album> {
        val all = albumsOf(load())
        val sorted = when (order) {
            AlbumOrder.Alphabetical -> all.sortedBy { it.title.lowercase() }
            AlbumOrder.Newest -> {
                val newest = load().groupBy { it.albumId }.mapValues { (_, list) -> list.maxOf { stamps[it.path] ?: 0L } }
                all.sortedByDescending { newest[it.id] ?: 0L }
            }
            AlbumOrder.Recent -> all.filter { plays.getLong("zuletzt_${it.id}", 0) > 0 }.sortedByDescending { plays.getLong("zuletzt_${it.id}", 0) }
            AlbumOrder.Frequent -> all.filter { plays.getInt("anzahl_${it.id}", 0) > 0 }.sortedByDescending { plays.getInt("anzahl_${it.id}", 0) }
            AlbumOrder.Artist -> all.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
            AlbumOrder.Year -> all.sortedWith(compareByDescending<Album> { it.year ?: 0 }.thenBy { it.title.lowercase() })
        }
        return sorted.drop(offset).take(size)
    }

    override fun album(id: String): Pair<Album, List<Track>> {
        val tracks = load().filter { it.albumId == id }.sortedWith(compareBy({ it.disc ?: 1 }, { it.number ?: 0 }, { it.title }))
        val album = albumsOf(tracks).firstOrNull() ?: throw ServerError(tr("Nicht gefunden."))
        return album to tracks
    }

    override fun track(id: String): Track? = load().firstOrNull { it.id == id }

    override fun tracks(size: Int, offset: Int) = load().sortedBy { it.title.lowercase() }.drop(offset).take(size)

    override fun playlists(): List<Playlist> = ownPlaylists().map { (id, p) -> Playlist(id, p.first, p.second.size, 0, null) }

    override fun playlist(id: String): Pair<Playlist, List<Track>> {
        val (name, ids) = ownPlaylists()[id] ?: throw ServerError(tr("Nicht gefunden."))
        val byId = load().associateBy { it.id }
        val tracks = ids.mapNotNull { byId[it] }
        return Playlist(id, name, tracks.size, tracks.sumOf { it.duration }, tracks.firstOrNull()?.coverId) to tracks
    }

    private fun ownPlaylists(): Map<String, Pair<String, List<String>>> = runCatching {
        val json = JSONObject(File(dir, "$accountId-playlists.json").readText())
        json.keys().asSequence().associateWith { k -> json.getJSONObject(k).let { it.getString("name") to it.getJSONArray("ids").let { a -> (0 until a.length()).map(a::getString) } } }
    }.getOrDefault(emptyMap())

    override fun createPlaylist(name: String, tracks: List<Track>): Playlist {
        val file = File(dir, "$accountId-playlists.json")
        val json = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        val id = "pl" + System.currentTimeMillis()
        json.put(id, JSONObject().put("name", name).put("ids", JSONArray(tracks.map { it.id })))
        file.writeText(json.toString())
        return Playlist(id, name, tracks.size, tracks.sumOf { it.duration })
    }

    override fun addToPlaylist(playlistId: String, tracks: List<Track>): Boolean {
        val file = File(dir, "$accountId-playlists.json")
        val json = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        val p = json.optJSONObject(playlistId) ?: return false
        tracks.forEach { p.getJSONArray("ids").put(it.id) }
        file.writeText(json.toString()); return true
    }

    override fun search(query: String): SearchResult {
        val q = Matcher.normal(query)
        if (q.isEmpty()) return SearchResult()
        val tracks = load()
        return SearchResult(artists().filter { Matcher.normal(it.name).contains(q) }, albumsOf(tracks).filter { Matcher.normal(it.title).contains(q) },
            tracks.filter { t -> Matcher.normal("${t.title} ${t.artist} ${t.album}").contains(q) }.take(100))
    }

    /** The file itself (content://…); formats Android can't open go through LiDio's own FFmpeg when it's there. */
    override fun streamUrl(track: Track, maxBitrate: Int): String {
        val path = track.path ?: return ""
        return when {
            track.suffix in Midi.SUFFIXES && Midi.available -> Midi.wrap(path)
            !Formats.playable(track.suffix) && Ffmpeg.available -> Ffmpeg.wrap(path)
            else -> path
        }
    }

    override fun coverUrl(coverId: String, size: Int): String = File(coverDir, "$coverId.jpg").toURI().toString()

    override fun favorites(): List<Track> {
        val ids = plays.getStringSet("favoriten", emptySet()).orEmpty()
        return load().filter { it.id in ids }.map { it.copy(favorite = true) }
    }
    override fun setFavorite(track: Track, on: Boolean): Boolean {
        val ids = plays.getStringSet("favoriten", emptySet()).orEmpty().toMutableSet()
        if (on) ids += track.id else ids -= track.id
        plays.edit().putStringSet("favoriten", ids).apply(); return true
    }
    override fun discover(count: Int): List<Track> {
        val heard = plays.all.keys.filter { it.startsWith("zuletzt_") }.map { it.removePrefix("zuletzt_") }.toSet()
        return load().filter { it.albumId !in heard }.shuffled().take(count)
    }

    /** Autoplay ∞ on the phone: more by the same artist, then the rest of the folders, shuffled. */
    override fun similar(track: Track, count: Int): List<Track> {
        val all = load().filter { it.id != track.id }
        val same = all.filter { albumArtist(it) == albumArtist(track) }.shuffled()
        return (same + all.shuffled()).distinctBy { it.id }.take(count)
    }

    override fun played(track: Track) {
        val id = track.albumId ?: return
        plays.edit().putLong("zuletzt_$id", System.currentTimeMillis()).putInt("anzahl_$id", plays.getInt("anzahl_$id", 0) + 1).apply()
    }
}
