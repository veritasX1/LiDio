package io.github.veritasx1.lidio

import org.json.JSONObject

/** Emby and Jellyfin – Jellyfin began as a fork of Emby, so both speak the same "MediaBrowser" API. Differences: Emby lives
 *  under /emby and takes the token as X-Emby-Token; Jellyfin under / with "Authorization: MediaBrowser … Token=". */
class MediaBrowserServer(override val kind: ServerKind, address: String, private val userId: String, private val token: String,
                         private val deviceId: String = DEVICE) : MusicServer {
    private val base = root(kind, address)

    private val headers get() = auth(kind, deviceId, token)
    /** The token in an address (stream, cover): Emby takes api_key; Jellyfin 12 refuses it (401) and wants ApiKey. */
    private val keyParam = if (kind == ServerKind.Emby) "api_key" else "ApiKey"

    private fun get(path: String, vararg params: Pair<String, Any?>): JSONObject {
        val query = params.filter { it.second != null }.joinToString("&") { (k, v) -> "$k=${Http.encode(v.toString())}" }
        return JSONObject(Http.get("$base$path${if (query.isEmpty()) "" else "?$query"}", headers))
    }

    private fun items(vararg params: Pair<String, Any?>): List<JSONObject> =
        get("/Users/$userId/Items", "Recursive" to true, *(if (params.any { it.first == "Fields" }) params else arrayOf("Fields" to FIELDS, *params)))
            .arr("Items").objects()

    override fun check(): String {
        val info = get("/System/Info")
        return "${kind.label} ${info.str("Version") ?: ""}".trim()
    }

    override fun artists(): List<Artist> =
        get("/Artists/AlbumArtists", "UserId" to userId, "Recursive" to true, "SortBy" to "SortName", "Fields" to "ChildCount")
            .arr("Items").objects().map(::artist)

    override fun artist(id: String): Pair<Artist, List<Album>> {
        val artist = artist(get("/Users/$userId/Items/$id"))
        val albums = items("IncludeItemTypes" to "MusicAlbum", "Fields" to ALBUM_FIELDS, "AlbumArtistIds" to id, "SortBy" to "ProductionYear,SortName",
            "SortOrder" to "Descending").map(::album)
        return artist to albums
    }

    override fun albums(order: AlbumOrder, size: Int, offset: Int): List<Album> {
        val (sort, direction) = when (order) {
            AlbumOrder.Newest -> "DateCreated" to "Descending"
            AlbumOrder.Recent -> "DatePlayed" to "Descending"
            AlbumOrder.Alphabetical -> "SortName" to "Ascending"
            AlbumOrder.Frequent -> "PlayCount" to "Descending"
            AlbumOrder.Artist -> "AlbumArtist,SortName" to "Ascending"
            AlbumOrder.Year -> "ProductionYear,SortName" to "Descending"
        }
        return items("IncludeItemTypes" to "MusicAlbum", "Fields" to ALBUM_FIELDS, "SortBy" to sort, "SortOrder" to direction, "Limit" to size, "StartIndex" to offset)
            .map(::album)
    }

    override fun album(id: String): Pair<Album, List<Track>> {
        val album = album(get("/Users/$userId/Items/$id"))
        val tracks = items("ParentId" to id, "IncludeItemTypes" to "Audio", "SortBy" to "ParentIndexNumber,IndexNumber,SortName").map(::track)
        return album.copy(trackCount = tracks.size, duration = tracks.sumOf { it.duration }) to tracks
    }

    override fun tracks(size: Int, offset: Int): List<Track> =
        items("IncludeItemTypes" to "Audio", "SortBy" to "SortName", "Limit" to size, "StartIndex" to offset).map(::track)

    /** Card 5902afb5: the playlists without their title count – on Olaf's Emby (95 playlists, 10 000 entries) "ChildCount" took
     *  12.6 s, without it 50 ms. Apple's list shows only cover and name anyway; the count is on the playlist's own page. */
    override fun playlists(): List<Playlist> {
        val folder = runCatching { get("/Users/$userId/Views").arr("Items").objects().firstOrNull { it.str("CollectionType") == "playlists" }?.id() }.getOrNull()
        val items = if (folder != null) get("/Users/$userId/Items", "ParentId" to folder, "SortBy" to "SortName", "Fields" to "MediaType")
            else get("/Users/$userId/Items", "Recursive" to true, "IncludeItemTypes" to "Playlist", "SortBy" to "SortName", "Fields" to "MediaType")
        return items.arr("Items").objects().filter { it.str("MediaType") != "Video" && (it.str("Type") ?: "Playlist") == "Playlist" }.map(::playlist)
    }

    override fun playlist(id: String): Pair<Playlist, List<Track>> {
        val playlist = playlist(get("/Users/$userId/Items/$id"))
        val tracks = get("/Playlists/$id/Items", "UserId" to userId, "Fields" to FIELDS).arr("Items").objects().map(::track)
        return playlist.copy(trackCount = tracks.size, duration = tracks.sumOf { it.duration }) to tracks
    }

    override fun search(query: String): SearchResult {
        val artists = get("/Artists/AlbumArtists", "UserId" to userId, "SearchTerm" to query, "Limit" to 10).arr("Items").objects().map(::artist)
        val albums = items("IncludeItemTypes" to "MusicAlbum", "Fields" to ALBUM_FIELDS, "SearchTerm" to query, "Limit" to 20).map(::album)
        val tracks = items("IncludeItemTypes" to "Audio", "SearchTerm" to query, "Limit" to 50).map(::track)
        return SearchResult(artists, albums, tracks)
    }

    /** The "universal" stream: the original file when the phone plays it, else the server converts (e.g. WMA → MP3). */
    override fun streamUrl(track: Track, maxBitrate: Int): String {
        val params = listOf("UserId" to userId, "DeviceId" to deviceId, keyParam to token,
            "Container" to "mp3,aac,m4a|aac,m4b|aac,flac,alac,m4a|alac,ogg,oga,opus,webm|opus,wav,mka",
            "TranscodingContainer" to "mp3", "TranscodingProtocol" to "http", "AudioCodec" to "mp3",
            "MaxStreamingBitrate" to (if (maxBitrate > 0) maxBitrate * 1000 else 140_000_000))
        return "$base/Audio/${track.id}/universal?" + params.joinToString("&") { (k, v) -> "$k=${Http.encode(v.toString())}" }
    }

    /** "id#tag": the picture's tag makes a new address when the picture changes (e.g. a Deezer cover set on the server), so the
     *  phone's cover store loads it again instead of showing the old one forever. */
    override fun coverUrl(coverId: String, size: Int): String {
        val id = coverId.substringBefore('#'); val tag = coverId.substringAfter('#', "")
        return "$base/Items/$id/Images/Primary?maxHeight=$size&maxWidth=$size&quality=90" + (if (tag.isNotEmpty()) "&tag=$tag" else "") +
            "&$keyParam=${Http.encode(token)}"
    }

    override fun track(id: String): Track? = runCatching { track(get("/Users/$userId/Items/$id", "Fields" to FIELDS)) }.getOrNull()

    override fun serverId(): String = runCatching { JSONObject(Http.get("$base/System/Info/Public")).str("Id") ?: "" }.getOrDefault("")

    override fun createPlaylist(name: String, tracks: List<Track>): Playlist {
        val chunks = tracks.map { it.id }.chunked(100)
        val first = chunks.firstOrNull().orEmpty()
        val query = listOf("Name" to name, "Ids" to first.joinToString(","), "UserId" to userId, "MediaType" to "Audio")
            .joinToString("&") { (k, v) -> "$k=${Http.encode(v)}" }
        val body = JSONObject().put("Name", name).put("Ids", org.json.JSONArray(first)).put("UserId", userId).put("MediaType", "Audio").toString()
        val id = JSONObject(Http.post("$base/Playlists?$query", body, headers)).str("Id") ?: throw ServerError("Die Playlist wurde nicht angelegt.")
        chunks.drop(1).forEach { chunk -> Http.post("$base/Playlists/$id/Items?Ids=${chunk.joinToString(",")}&UserId=$userId", "{}", headers) }
        return Playlist(id, name, tracks.size, tracks.sumOf { it.duration })
    }

    /** Emby often has the cover in the titles (embedded) but none on the album – then the first title with one. */
    override fun albumCoverFallback(albumId: String, size: Int): String? = runCatching {
        items("ParentId" to albumId, "IncludeItemTypes" to "Audio", "Limit" to 5, "Fields" to "PrimaryImageAspectRatio")
            .firstOrNull { it.obj("ImageTags")?.has("Primary") == true }?.let { coverUrl(it.id(), size) }
    }.getOrNull()

    /** Jellyfin 10.9+: /Audio/{id}/Lyrics (start in 100-ns ticks). Emby: an .lrc beside the file shows up as a subtitle stream. */
    override fun lyrics(track: Track): String? {
        if (kind == ServerKind.Jellyfin) {
            val reply = runCatching { get("/Audio/${track.id}/Lyrics") }.getOrNull() ?: return null
            val lines = reply.arr("Lyrics").objects().map { LyricLine(if (it.has("Start") && !it.isNull("Start")) it.optLong("Start") / 10_000 else null, it.optString("Text")) }
            return if (lines.isEmpty()) null else Lyrics.toLrc(lines)
        }
        val item = runCatching { get("/Users/$userId/Items/${track.id}", "Fields" to "MediaStreams,MediaSources") }.getOrNull() ?: return null
        val source = item.arr("MediaSources").objects().firstOrNull() ?: return null
        val stream = (source.arr("MediaStreams").objects() + item.arr("MediaStreams").objects())
            .firstOrNull { it.optString("Type") == "Subtitle" || it.optString("Type") == "Lyrics" } ?: return null
        // Emby hands an .lrc out empty as "Stream.lrc" – as SRT it keeps the times (checked on Olaf's Emby 05.10.2026); plain text as last resort.
        val stream0 = "$base/Videos/${track.id}/${source.optString("Id")}/Subtitles/${stream.optInt("Index")}/Stream"
        runCatching { Http.get("$stream0.srt", headers) }.getOrNull()?.let(Lyrics::fromSrt)?.takeIf { it.isNotEmpty() }?.let { return Lyrics.toLrc(it) }
        return runCatching { Http.get("$stream0.txt", headers) }.getOrNull()?.removePrefix("\uFEFF")?.takeIf { it.isNotBlank() }
    }

    /** Jellyfin keeps an uploaded text as .lrc beside the file (needs the right to manage lyrics); Emby has no upload. */
    override fun saveLyrics(track: Track, lrc: String): Boolean = kind == ServerKind.Jellyfin &&
        runCatching { Http.text("$base/Audio/${track.id}/Lyrics?fileName=${Http.encode(track.title)}.lrc", lrc, headers) }.isSuccess

    override fun played(track: Track) { runCatching { Http.post("$base/Users/$userId/PlayedItems/${track.id}", "{}", headers) } }

    private fun artist(j: JSONObject) = Artist(j.id(), j.str("Name") ?: "Unbekannt", (j.int("ChildCount") ?: 0),
        if (j.obj("ImageTags")?.has("Primary") == true) j.id() else null)

    private fun album(j: JSONObject): Album {
        val artist = j.arr("AlbumArtists").objects().firstOrNull()
        return Album(j.id(), j.str("Name") ?: "Unbekanntes Album", j.str("AlbumArtist") ?: artist?.str("Name") ?: "Unbekannt",
            artist?.str("Id"), j.int("ProductionYear"), if (j.obj("ImageTags")?.has("Primary") == true) j.id() else null,
            (j.int("ChildCount") ?: 0), ticks(j), j.arr("Genres")?.optString(0)?.takeIf { it.isNotEmpty() },
            (j.str("AlbumArtist") ?: "").equals("Various Artists", true) || (j.str("AlbumArtist") ?: "").equals("Verschiedene Interpreten", true))
    }

    private fun track(j: JSONObject): Track {
        val artists = j.arr("Artists")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val cover = when {
            j.obj("ImageTags")?.has("Primary") == true -> j.id()
            j.str("AlbumPrimaryImageTag") != null -> j.str("AlbumId")
            else -> null
        }
        val source = j.arr("MediaSources").objects().firstOrNull()
        return Track(j.id(), j.str("Name") ?: "Unbekannt", artists.joinToString(", ").ifEmpty { j.str("AlbumArtist") ?: "Unbekannt" },
            j.str("Album") ?: "", j.str("AlbumId"), ticks(j), j.int("IndexNumber"), j.int("ParentIndexNumber"), j.int("ProductionYear"),
            cover, j.str("Path"), source?.long("Size") ?: 0, j.str("Container"),
            favorite = j.obj("UserData")?.optBoolean("IsFavorite"))
    }

    override fun setFavorite(track: Track, on: Boolean): Boolean = runCatching {
        Http.send(if (on) "POST" else "DELETE", "$base/Users/$userId/FavoriteItems/${track.id}", if (on) "" else null, "application/json", headers)
    }.isSuccess

    override fun favorites(): List<Track> =
        items("IncludeItemTypes" to "Audio", "Filters" to "IsFavorite", "SortBy" to "DatePlayed,SortName", "SortOrder" to "Descending").map(::track)

    /** Emby's and Jellyfin's "Instant Mix": similar titles from the own library. */
    override fun similar(track: Track, count: Int): List<Track> =
        get("/Items/${track.id}/InstantMix", "UserId" to userId, "Limit" to count, "Fields" to FIELDS).arr("Items").objects().map(::track)
            .filter { it.id != track.id }

    private fun playlist(j: JSONObject) = Playlist(j.id(), j.str("Name") ?: "Playlist", (j.int("ChildCount") ?: 0), ticks(j),
        j.obj("ImageTags")?.str("Primary")?.let { tag -> "${j.id()}#$tag" }, MissingNote.read(j.str("Overview")),
        MissingNote.origin(j.str("Overview")))

    override fun discover(count: Int): List<Track> =
        items("IncludeItemTypes" to "Audio", "Filters" to "IsUnplayed", "SortBy" to "Random", "Limit" to count).map(::track)

    /** Emby lists the genres most used first – the first 24. */
    override fun genres(): List<Genre> =
        get("/MusicGenres", "UserId" to userId, "Recursive" to true, "Limit" to 24).arr("Items").objects().map { Genre(it.id(), it.str("Name") ?: "") }

    override fun genreAlbums(genre: Genre): List<Album> =
        items("IncludeItemTypes" to "MusicAlbum", "Fields" to ALBUM_FIELDS, "GenreIds" to genre.id, "SortBy" to "DateCreated", "SortOrder" to "Descending", "Limit" to 200).map(::album)

    override fun addToPlaylist(playlistId: String, tracks: List<Track>): Boolean = runCatching {
        tracks.map { it.id }.chunked(100).forEach { Http.post("$base/Playlists/$playlistId/Items?Ids=${it.joinToString(",")}&UserId=$userId", "{}", headers) }
    }.isSuccess

    /** Emby 4.9: per-item access (None/Read/Write/Manage…); Jellyfin 10.9: the playlist's Shares + OpenAccess. */
    override fun sharing(playlistId: String): Sharing? = runCatching {
        if (kind == ServerKind.Emby) {
            val all = get("/Users/ItemAccess", "ItemId" to playlistId).arr("Items").objects()
            val me = all.firstOrNull { it.id() == userId }?.optString("UserItemShareLevel") ?: "None"
            val users = all.filter { it.id() != userId }.map { u ->
                ShareUser(u.id(), u.str("Name") ?: "?", when (u.optString("UserItemShareLevel")) { "Read" -> "read"; "None", "" -> "none"; else -> "write" })
            }
            Sharing(users, everyone = users.isNotEmpty() && users.all { it.level != "none" }, perUser = true, canManage = me.startsWith("Manage"))
        } else {
            val p = get("/Playlists/$playlistId")
            val shares = p.arr("Shares").objects().associate { it.str("UserId").orEmpty() to (if (it.optBoolean("CanEdit")) "write" else "read") }
            val people = runCatching { JSONObject("{\"Items\":" + Http.get("$base/Users", headers) + "}").arr("Items").objects() }.getOrNull()
                ?: JSONObject("{\"Items\":" + Http.get("$base/Users/Public", headers) + "}").arr("Items").objects()
            Sharing(people.filter { it.id() != userId }.map { ShareUser(it.id(), it.str("Name") ?: "?", shares[it.id()] ?: "none") },
                everyone = p.optBoolean("OpenAccess"), perUser = true)
        }
    }.getOrNull()

    override fun share(playlistId: String, users: Map<String, String>, everyone: Boolean): Boolean = runCatching {
        if (kind == ServerKind.Emby) {
            // "Everyone": every other user may listen (those who may change it keep that).
            val wanted = if (everyone) users.mapValues { (_, l) -> if (l == "none") "read" else l } else users
            wanted.entries.groupBy { it.value }.forEach { (level, list) ->
                val body = JSONObject().put("ItemIds", org.json.JSONArray(listOf(playlistId))).put("UserIds", org.json.JSONArray(list.map { it.key }))
                    .put("ItemAccess", when (level) { "read" -> "Read"; "write" -> "Write"; else -> "None" })
                Http.post("$base/Items/Access", body.toString(), headers)
            }
        } else {
            val body = JSONObject().put("OpenAccess", everyone).put("Users", org.json.JSONArray(users.filter { it.value != "none" }.map { (id, l) ->
                JSONObject().put("UserId", id).put("CanEdit", l == "write") }))
            Http.post("$base/Playlists/$playlistId", body.toString(), headers)
        }
    }.isSuccess

    /** Emby and Jellyfin take the picture base64-encoded. */
    override fun setPlaylistCover(playlistId: String, jpeg: ByteArray): Boolean = runCatching {
        Http.send("POST", "$base/Items/$playlistId/Images/Primary", android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP), "image/jpeg", headers)
    }.isSuccess

    /** The item as the server has it, the description changed, the field locked so a library scan keeps it. */
    override fun noteMissing(playlistId: String, missing: List<String>): Boolean = runCatching {
        val item = get("/Users/$userId/Items/$playlistId")
        item.put("Overview", MissingNote.write(item.str("Overview"), missing))
        val locked = item.optJSONArray("LockedFields") ?: org.json.JSONArray()
        if ((0 until locked.length()).none { locked.optString(it) == "Overview" }) locked.put("Overview")
        item.put("LockedFields", locked)
        Http.post("$base/Items/$playlistId", item.toString(), headers)
    }.isSuccess

    private fun ticks(j: JSONObject) = (j.long("RunTimeTicks") / 10_000_000L).toInt()

    companion object {
        /** Album lists need no title counts, paths or media data – with them Emby took 6.7 s for 826 albums, without 0.2 s (05.10.2026). */
        const val ALBUM_FIELDS = "ProductionYear,Genres,AlbumArtist,AlbumArtists,DateCreated"
        const val FIELDS = "ChildCount,Genres,ProductionYear,Path,MediaSources,DateCreated,AlbumArtist,AlbumArtists,Artists"
        /** Replaced by the app with this installation's own id (Accounts/App); servers list the device under it. */
        var DEVICE = "lidio-test"

        fun root(kind: ServerKind, address: String): String {
            val clean = address.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
            return if (kind == ServerKind.Emby && !clean.endsWith("/emby", true)) "$clean/emby" else clean
        }

        fun auth(kind: ServerKind, device: String, token: String?): Map<String, String> {
            val header = "MediaBrowser Client=\"LiDio\", Device=\"Android\", DeviceId=\"$device\", Version=\"0.1\"" +
                (token?.let { ", Token=\"$it\"" } ?: "")
            return if (kind == ServerKind.Emby) mapOf("X-Emby-Authorization" to header) + (token?.let { mapOf("X-Emby-Token" to it) } ?: emptyMap())
            else mapOf("Authorization" to header)
        }

        /** Signs in with name and password; returns the user's id and the access token (the password is not kept). */
        fun login(kind: ServerKind, address: String, user: String, password: String, device: String = DEVICE): Pair<String, String> {
            val body = JSONObject().put("Username", user).put("Pw", password).toString()
            val reply = JSONObject(Http.post("${root(kind, address)}/Users/AuthenticateByName", body, auth(kind, device, null)))
            val token = reply.str("AccessToken") ?: throw ServerError("Anmeldung abgelehnt.")
            return (reply.obj("User")?.id() ?: throw ServerError("Anmeldung abgelehnt.")) to token
        }
    }
}
