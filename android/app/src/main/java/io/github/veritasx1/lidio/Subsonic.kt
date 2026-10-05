package io.github.veritasx1.lidio

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** Plain HTTP for the servers: JSON in, German errors out. No library – Android's own HttpURLConnection. */
object Http {
    fun get(url: String, headers: Map<String, String> = emptyMap()): String = request("GET", url, headers, null)

    fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): String = request("POST", url, headers, body)
    fun text(url: String, body: String, headers: Map<String, String> = emptyMap()): String = request("POST", url, headers, body, "text/plain")
    fun send(method: String, url: String, body: String?, type: String, headers: Map<String, String> = emptyMap()): String = request(method, url, headers, body, type)

    /** A form post (application/x-www-form-urlencoded), e.g. an OAuth token request. */
    fun form(url: String, body: String, headers: Map<String, String> = emptyMap()): String =
        request("POST", url, headers, body, "application/x-www-form-urlencoded")

    private fun request(method: String, url: String, headers: Map<String, String>, body: String?, type: String = "application/json"): String {
        val connection = try { URL(url).openConnection() as HttpURLConnection }
            catch (e: Exception) { throw ServerError("Die Adresse ist ungültig.", e) }
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", type)
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            if (code == 401 || code == 403) throw ServerError("Anmeldung abgelehnt – Benutzername oder Passwort stimmen nicht.")
            if (code !in 200..299) throw ServerError("Der Server antwortet mit Fehler $code.")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: ServerError) {
            throw e
        } catch (e: java.net.UnknownHostException) {
            throw ServerError("Server nicht gefunden – Adresse prüfen.", e)
        } catch (e: java.net.SocketTimeoutException) {
            throw ServerError("Der Server antwortet nicht.", e)
        } catch (e: java.io.IOException) {
            throw ServerError("Keine Verbindung zum Server.", e)
        } finally {
            connection.disconnect()
        }
    }

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

/** The key as written – or with a small first letter (Jellyfin 12 answers some calls in camelCase, Emby in PascalCase). */
fun JSONObject.key(key: String): String = if (has(key)) key else key.replaceFirstChar { it.lowercase() }.takeIf { has(it) } ?: key
fun JSONObject.str(key: String): String? = key(key).let { k -> if (has(k) && !isNull(k)) optString(k).takeIf { it.isNotEmpty() } else null }
fun JSONObject.int(key: String): Int? = key(key).let { k -> if (has(k) && !isNull(k)) optInt(k) else null }
fun JSONObject.arr(key: String): JSONArray? = optJSONArray(key(key))
fun JSONObject.obj(key: String): JSONObject? = optJSONObject(key(key))
fun JSONObject.long(key: String): Long = optLong(key(key))
fun JSONObject.id(): String = str("Id") ?: throw ServerError("Unerwartete Antwort vom Server.")
fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

/** Navidrome (and every Subsonic server) through the Subsonic API with token login – the password never travels in the clear. */
class SubsonicServer(base: String, private val user: String, private val password: String,
                     private val salt: String = randomSalt()) : MusicServer {
    override val kind = ServerKind.Navidrome
    private val base = base.trimEnd('/')
    private val token = md5(password + salt)

    private fun url(method: String, vararg params: Pair<String, Any?>): String {
        val query = (listOf("u" to user, "t" to token, "s" to salt, "v" to "1.16.1", "c" to "LiDio", "f" to "json") + params)
            .filter { it.second != null }.joinToString("&") { (k, v) -> "$k=${Http.encode(v.toString())}" }
        return "$base/rest/$method?$query"
    }

    private fun call(method: String, vararg params: Pair<String, Any?>): JSONObject {
        val reply = JSONObject(Http.get(url(method, *params))).getJSONObject("subsonic-response")
        if (reply.optString("status") != "ok") {
            val error = reply.optJSONObject("error")
            throw when (error?.optInt("code")) {
                40, 41 -> ServerError("Anmeldung abgelehnt – Benutzername oder Passwort stimmen nicht.")
                70 -> ServerError("Nicht gefunden.")
                else -> ServerError(error?.optString("message")?.takeIf { it.isNotEmpty() } ?: "Der Server hat abgelehnt.")
            }
        }
        return reply
    }

    override fun check(): String {
        val reply = call("ping")
        return listOfNotNull(reply.str("type")?.replaceFirstChar { it.uppercase() }, reply.str("serverVersion") ?: reply.str("version")).joinToString(" ")
    }

    override fun artists(): List<Artist> =
        call("getArtists").getJSONObject("artists").optJSONArray("index").objects()
            .flatMap { it.optJSONArray("artist").objects() }.map(::artist)

    override fun artist(id: String): Pair<Artist, List<Album>> {
        val json = call("getArtist", "id" to id).getJSONObject("artist")
        return artist(json) to json.optJSONArray("album").objects().map(::album)
    }

    override fun albums(order: AlbumOrder, size: Int, offset: Int): List<Album> {
        val type = when (order) {
            AlbumOrder.Newest -> "newest"; AlbumOrder.Recent -> "recent"
            AlbumOrder.Alphabetical -> "alphabeticalByName"; AlbumOrder.Frequent -> "frequent"
            AlbumOrder.Artist -> "alphabeticalByArtist"; AlbumOrder.Year -> "byYear"
        }
        // "byYear" wants a range – newest first.
        val years = if (order == AlbumOrder.Year) arrayOf("fromYear" to 3000, "toYear" to 0) else emptyArray()
        return call("getAlbumList2", "type" to type, "size" to size, "offset" to offset, *years)
            .getJSONObject("albumList2").optJSONArray("album").objects().map(::album)
    }

    override fun album(id: String): Pair<Album, List<Track>> {
        val json = call("getAlbum", "id" to id).getJSONObject("album")
        return album(json) to json.optJSONArray("song").objects().map(::track)
    }

    override fun tracks(size: Int, offset: Int): List<Track> =
        call("search3", "query" to "", "songCount" to size, "songOffset" to offset, "artistCount" to 0, "albumCount" to 0)
            .getJSONObject("searchResult3").optJSONArray("song").objects().map(::track)

    override fun playlists(): List<Playlist> =
        call("getPlaylists").getJSONObject("playlists").optJSONArray("playlist").objects().map(::playlist)

    override fun playlist(id: String): Pair<Playlist, List<Track>> {
        val json = call("getPlaylist", "id" to id).getJSONObject("playlist")
        return playlist(json) to json.optJSONArray("entry").objects().map(::track)
    }

    override fun search(query: String): SearchResult {
        val json = call("search3", "query" to query, "artistCount" to 10, "albumCount" to 20, "songCount" to 50).getJSONObject("searchResult3")
        return SearchResult(json.optJSONArray("artist").objects().map(::artist), json.optJSONArray("album").objects().map(::album),
            json.optJSONArray("song").objects().map(::track))
    }

    /** Formats the phone can't open (WMA, APE, WavPack, DSD …) come converted to MP3 – Navidrome uses ffmpeg for that. */
    override fun streamUrl(track: Track, maxBitrate: Int): String =
        if (Formats.playable(track.suffix)) url("stream", "id" to track.id, "maxBitRate" to maxBitrate.takeIf { it > 0 })
        else url("stream", "id" to track.id, "format" to "mp3", "maxBitRate" to (if (maxBitrate in 1..319) maxBitrate else 320))

    override fun coverUrl(coverId: String, size: Int): String = url("getCoverArt", "id" to coverId, "size" to size)

    override fun played(track: Track) { runCatching { call("scrobble", "id" to track.id, "submission" to true) } }

    /** Navidrome (OpenSubsonic): synchronised lines from .lrc files or tags; older servers the plain text by artist and title. */
    override fun lyrics(track: Track): String? {
        runCatching { call("getLyricsBySongId", "id" to track.id) }.getOrNull()?.optJSONObject("lyricsList")?.arr("structuredLyrics")?.objects()
            ?.sortedByDescending { it.optBoolean("synced") }?.firstOrNull()?.let { l ->
                val lines = l.arr("line").objects().map { LyricLine(if (l.optBoolean("synced")) it.optLong("start") else null, it.optString("value")) }
                if (lines.isNotEmpty()) return Lyrics.toLrc(lines)
            }
        return runCatching { call("getLyrics", "artist" to track.artist, "title" to track.title) }.getOrNull()
            ?.optJSONObject("lyrics")?.str("value")
    }

    override fun track(id: String): Track? = runCatching { track(call("getSong", "id" to id).getJSONObject("song")) }.getOrNull()

    /** Subsonic has no server id: the address stands in (both of an account's addresses are checked on the receiving side). */
    override fun serverId(): String = "nd-" + md5(Reach.normal(base)).take(16)

    override fun createPlaylist(name: String, tracks: List<Track>): Playlist {
        val chunks = tracks.map { it.id }.chunked(100)
        val reply = call("createPlaylist", "name" to name, *(chunks.firstOrNull() ?: emptyList()).map { "songId" to it }.toTypedArray())
        val id = reply.optJSONObject("playlist")?.str("id") ?: playlists().lastOrNull { it.name == name }?.id
            ?: throw ServerError("Die Playlist wurde nicht angelegt.")
        chunks.drop(1).forEach { chunk -> call("updatePlaylist", "playlistId" to id, *chunk.map { "songIdToAdd" to it }.toTypedArray()) }
        return Playlist(id, name, tracks.size, tracks.sumOf { it.duration })
    }

    private fun artist(j: JSONObject) = Artist(j.getString("id"), j.str("name") ?: "Unbekannt", j.optInt("albumCount"), j.str("coverArt"))

    private fun album(j: JSONObject) = Album(j.getString("id"), j.str("name") ?: j.str("title") ?: "Unbekanntes Album",
        j.str("artist") ?: "Unbekannt", j.str("artistId"), j.int("year")?.takeIf { it > 0 }, j.str("coverArt"),
        j.optInt("songCount"), j.optInt("duration"), j.str("genre"), j.optBoolean("isCompilation"))

    private fun track(j: JSONObject) = Track(j.getString("id"), j.str("title") ?: "Unbekannt", j.str("artist") ?: "Unbekannt",
        j.str("album") ?: "", j.str("albumId"), j.optInt("duration"), j.int("track"), j.int("discNumber"), j.int("year")?.takeIf { it > 0 },
        j.str("coverArt"), j.str("path"), j.optLong("size"), j.str("suffix"), favorite = j.has("starred"))

    override fun setFavorite(track: Track, on: Boolean): Boolean = runCatching { call(if (on) "star" else "unstar", "id" to track.id) }.isSuccess

    override fun favorites(): List<Track> = call("getStarred2").optJSONObject("starred2")?.optJSONArray("song").objects().map(::track)

    /** Navidrome: similar titles (Last.fm, if set up there), else more by the same artist. */
    override fun similar(track: Track, count: Int): List<Track> =
        runCatching { call("getSimilarSongs2", "id" to track.id, "count" to count).optJSONObject("similarSongs2")?.optJSONArray("song").objects().map(::track) }
            .getOrNull()?.takeIf { it.isNotEmpty() }
            ?: runCatching { call("getRandomSongs", "size" to count).optJSONObject("randomSongs")?.optJSONArray("song").objects().map(::track) }.getOrDefault(emptyList())

    private fun playlist(j: JSONObject) = Playlist(j.getString("id"), j.str("name") ?: "Playlist", j.optInt("songCount"), j.optInt("duration"), j.str("coverArt"),
        MissingNote.read(j.str("comment")), MissingNote.origin(j.str("comment")))

    override fun discover(count: Int): List<Track> =
        call("getRandomSongs", "size" to count).optJSONObject("randomSongs")?.optJSONArray("song").objects().map(::track)

    override fun genres(): List<Genre> = call("getGenres").optJSONObject("genres")?.optJSONArray("genre").objects()
        .sortedByDescending { it.optInt("albumCount") }.take(24).map { Genre(it.optString("value"), it.optString("value")) }

    override fun genreAlbums(genre: Genre): List<Album> =
        call("getAlbumList2", "type" to "byGenre", "genre" to genre.id, "size" to 200).getJSONObject("albumList2").optJSONArray("album").objects().map(::album)

    /** Navidrome knows only "public" (everyone on this server may listen). */
    override fun sharing(playlistId: String): Sharing? = runCatching {
        val p = call("getPlaylist", "id" to playlistId).getJSONObject("playlist")
        Sharing(emptyList(), everyone = p.optBoolean("public"), perUser = false)
    }.getOrNull()
    override fun share(playlistId: String, users: Map<String, String>, everyone: Boolean): Boolean =
        runCatching { call("updatePlaylist", "playlistId" to playlistId, "public" to everyone) }.isSuccess

    override fun addToPlaylist(playlistId: String, tracks: List<Track>): Boolean = runCatching {
        tracks.map { it.id }.chunked(100).forEach { chunk -> call("updatePlaylist", "playlistId" to playlistId, *chunk.map { "songIdToAdd" to it }.toTypedArray()) }
    }.isSuccess

    override fun noteMissing(playlistId: String, missing: List<String>): Boolean = runCatching {
        val old = call("getPlaylist", "id" to playlistId).getJSONObject("playlist").str("comment")
        call("updatePlaylist", "playlistId" to playlistId, "comment" to MissingNote.write(old, missing))
    }.isSuccess

    companion object {
        fun md5(text: String): String = MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        fun randomSalt(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
    }
}
