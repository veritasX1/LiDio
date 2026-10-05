package io.github.veritasx1.lidio

import io.github.veritasx1.lidio.i18n.tr

/** What a music server offers – the same few things for Navidrome, Jellyfin and Emby, so the screens never ask which one. */
data class Artist(val id: String, val name: String, val albumCount: Int = 0, val coverId: String? = null)

data class Album(val id: String, val title: String, val artist: String, val artistId: String? = null, val year: Int? = null,
                 val coverId: String? = null, val trackCount: Int = 0, val duration: Int = 0, val genre: String? = null,
                 val compilation: Boolean = false)

data class Track(val id: String, val title: String, val artist: String, val album: String, val albumId: String? = null,
                 val duration: Int = 0, val number: Int? = null, val disc: Int? = null, val year: Int? = null,
                 val coverId: String? = null, val path: String? = null, val size: Long = 0, val suffix: String? = null,
                 /** The cover's full address when only that is known (a track rebuilt from the player after a restart). */
                 val artUrl: String? = null,
                 /** The album's artist when it differs from the title's (compilations, featured artists). */
                 val albumArtist: String? = null,
                 /** Marked as a favourite on the server (the ★ in Now Playing); null = not known. */
                 val favorite: Boolean? = null)

data class Playlist(val id: String, val name: String, val trackCount: Int = 0, val duration: Int = 0, val coverId: String? = null,
                    /** Titles the playlist should have but the server lacks ("Interpret – Titel"), from its description. */
                    val missing: List<String> = emptyList(),
                    /** Where the playlist comes from (a Deezer/Spotify link in its description) – for "Playlist aktualisieren". */
                    val origin: String? = null)

/** What a playlist lacks is kept in its description on the server (Emby/Jellyfin "Overview", Navidrome "comment") – readable in
 *  every app, and LiDio privat offers it for loading (Olaf 05.10.2026). Block format: a heading line, then one "♪ " line each. */
object MissingNote {
    const val HEADING = "Fehlt auf dem Server (LiDio):"
    fun read(text: String?): List<String> = text?.substringAfter(HEADING, "")?.lines()?.map { it.trim() }
        ?.filter { it.startsWith("♪ ") }?.map { it.removePrefix("♪ ").trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    /** The description with the block replaced (or removed when nothing is missing). */
    fun write(text: String?, missing: List<String>): String {
        val rest = (text ?: "").substringBefore(HEADING).trim()
        return if (missing.isEmpty()) rest else (rest + "\n\n" + HEADING + "\n" + missing.joinToString("\n") { "♪ $it" }).trim()
    }
    /** "12 · Interpret – Titel": its place in the playlist (1 = first); older lines have none and go to the end. */
    fun position(line: String): Int? = Regex("""^(\d+) · """).find(line)?.groupValues?.get(1)?.toIntOrNull()
    fun label(line: String): String = line.replace(Regex("""^\d+ · """), "")
    fun wanted(line: String): Wanted = label(line).let { l -> if (" – " in l) l.split(" – ", limit = 2).let { Wanted(it[1], it[0]) } else Wanted(l) }

    const val ORIGIN = "Quelle: "
    fun origin(text: String?): String? = text?.lines()?.map { it.trim() }?.firstOrNull { it.startsWith(ORIGIN) }?.removePrefix(ORIGIN)?.trim()
    /** The description with a "Quelle:" line set (kept above the missing block). */
    fun withOrigin(text: String?, origin: String): String {
        val lines = (text ?: "").lines().filterNot { it.trim().startsWith(ORIGIN) }
        return (listOf(ORIGIN + origin) + lines).joinToString("\n").trim()
    }
}

/** Card f3cba42b: who may use a playlist. level: "none", "read" (listen), "write" (also change it). */
data class ShareUser(val id: String, val name: String, val level: String)
/** What a server can share: per user (Emby, Jellyfin) and/or "everyone on this server" (all three). */
data class Sharing(val users: List<ShareUser>, val everyone: Boolean, val perUser: Boolean, val canManage: Boolean = true)

/** A genre or mood for the search's start page (card 81b01b4a); colour: the source's own, else from the name. */
data class Genre(val id: String, val name: String, val color: Long? = null)

/** The search results, in Apple Music's order. */
data class SearchResult(val artists: List<Artist> = emptyList(), val albums: List<Album> = emptyList(), val tracks: List<Track> = emptyList())

/** How albums are listed (Apple Music: "Zuletzt hinzugefügt", alphabetical; plus "Zuletzt gespielt"). */
enum class AlbumOrder(val label: String) { Newest(tr("Zuletzt hinzugefügt")), Recent(tr("Zuletzt gespielt")), Alphabetical(tr("Titel")), Frequent(tr("Oft gespielt")),
    /** iOS's "Sortieren nach": also by artist and by year (card 81b01b4a). */
    Artist(tr("Interpret")), Year("Erscheinungsjahr") }

/** A music server: every call is made off the main thread and throws ServerError when it fails. */
interface MusicServer {
    val kind: ServerKind

    /** Checks address and login; returns the server's own name/version for the account list. */
    fun check(): String
    fun artists(): List<Artist>
    fun artist(id: String): Pair<Artist, List<Album>>
    fun albums(order: AlbumOrder, size: Int = 60, offset: Int = 0): List<Album>
    fun album(id: String): Pair<Album, List<Track>>
    fun tracks(size: Int = 500, offset: Int = 0): List<Track>
    fun playlists(): List<Playlist>
    fun playlist(id: String): Pair<Playlist, List<Track>>
    fun search(query: String): SearchResult
    /** Playlists for a search (card 324583b9); a server's own by name, the web source's from its search. */
    /** "Neu entdecken" (Start → Mixe für dich): titles not played yet, at random. */
    fun discover(count: Int = 50): List<Track> = emptyList()

    /** Genres/moods for the search's start page, most used first. */
    fun genres(): List<Genre> = emptyList()
    /** A genre's albums (own library) – or, for the web source's moods, its playlists. */
    fun genreAlbums(genre: Genre): List<Album> = emptyList()
    fun genrePlaylists(genre: Genre): List<Playlist> = emptyList()
    fun searchPlaylists(query: String): List<Playlist> = playlists().filter { Matcher.normal(it.name).contains(Matcher.normal(query)) }

    /** Where the player streams the track from (address with login, so ExoPlayer needs no extra headers). */
    fun streamUrl(track: Track, maxBitrate: Int = 0): String
    fun coverUrl(coverId: String, size: Int): String

    /** An album without its own picture: the picture of one of its titles (embedded cover), when there is one. */
    fun albumCoverFallback(albumId: String, size: Int): String? = null

    /** One title by its id (a shared link), or null when the server doesn't know it. */
    fun track(id: String): Track? = null

    /** Who this server is – the same for its WLAN and its outside address (shared links find the right server). */
    fun serverId(): String = ""

    /** Creates a playlist on the server from these titles (card 4: imported lists). */
    fun createPlaylist(name: String, tracks: List<Track>): Playlist
    /** Notes in the playlist's description what it lacks (see MissingNote); false where the server can't. */
    fun noteMissing(playlistId: String, missing: List<String>): Boolean = false
    /** Gives a playlist its picture (JPEG); false where the server can't (Navidrome). */
    fun setPlaylistCover(playlistId: String, jpeg: ByteArray): Boolean = false
    /** Card f3cba42b: the playlist's sharing (null = this server can't share). */
    fun sharing(playlistId: String): Sharing? = null
    /** Sets who may use it: per user (id → level) and "everyone". */
    fun share(playlistId: String, users: Map<String, String>, everyone: Boolean): Boolean = false

    /** "Zur Playlist hinzufügen" (card 81b01b4a); false where the server can't. */
    fun addToPlaylist(playlistId: String, tracks: List<Track>): Boolean = false

    /** The song's text as the server has it (.lrc beside the file, embedded …), as LRC or plain text; null when none (card 7ac89c11). */
    fun lyrics(track: Track): String? = null
    /** Keeps a found text on the server, where the server allows it. */
    fun saveLyrics(track: Track, lrc: String): Boolean = false

    /** The ★ (card 81b01b4a): keeps a title as favourite on the server. False where it can't. */
    fun setFavorite(track: Track, on: Boolean): Boolean = false
    /** The favourite titles ("Lieblingstitel" in the Mediathek). */
    fun favorites(): List<Track> = emptyList()
    /** Autoplay ∞: titles like this one, to go on with when the queue ends. */
    fun similar(track: Track, count: Int = 30): List<Track> = emptyList()

    /** Tells the server a track was played (its "zuletzt gespielt" and play counts). Best effort. */
    fun played(track: Track) {}
}

enum class ServerKind(val label: String) { Navidrome("Navidrome"), Jellyfin("Jellyfin"), Emby("Emby"), Local(tr("Auf diesem Gerät")),
    /** The web source as a source of its own (cards 6c9ba022, d1f83bf9) – only where the variant has one. */
    Web(Variant.MUSIC) }

class ServerError(message: String, cause: Throwable? = null) : Exception(message, cause)

/** "3:07" or "1:02:45". */
fun duration(seconds: Int): String {
    val h = seconds / 3600; val m = seconds % 3600 / 60; val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** "12 Titel, 48 Minuten" like Apple Music under an album. */
fun summary(tracks: Int, seconds: Int): String {
    val minutes = (seconds + 30) / 60
    val time = when {
        minutes >= 60 -> tr("{value} Std. {value2} Min.", "value" to (minutes / 60), "value2" to (minutes % 60))
        minutes == 1 -> tr("1 Minute")
        else -> tr("{minutes} Minuten", "minutes" to minutes)
    }
    return tr("{tracks} Titel, {time}", "tracks" to tracks, "time" to time)
}

/** File types LiDio opens on the phone itself (Media3's extractors; decoding by the phone or FFmpeg). Everything else –
 *  WMA, APE, WavPack, DSD, Musepack … – the server converts before sending (card b697496c). */
object Formats {
    val PLAYABLE = setOf("mp3", "m4a", "m4b", "mp4", "aac", "adts", "flac", "ogg", "oga", "opus", "wav", "mka", "webm", "ac3", "eac3", "ec3",
        "dts", "amr", "awb", "3gp", "alac")
    fun playable(suffix: String?) = suffix.isNullOrEmpty() || suffix.lowercase() in PLAYABLE
}
