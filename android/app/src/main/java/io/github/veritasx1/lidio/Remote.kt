package io.github.veritasx1.lidio

import io.github.veritasx1.lidio.i18n.tr

import android.util.Base64
import org.json.JSONObject

/** Where a playlist comes from – shown as a small mark next to it (card 561fa339). */
enum class Source(val label: String) { Server(tr("Server")), Deezer("Deezer"), Spotify("Spotify") }

/** A public playlist elsewhere: shown, and on "Übertragen" compared with the own server like an imported list. */
data class RemoteList(val source: Source, val id: String, val name: String, val owner: String = "", val count: Int = 0, val cover: String? = null)

interface PublicPlaylists {
    val source: Source
    fun search(query: String): List<RemoteList>
    fun tracks(id: String): List<Wanted>
}

/** Deezer's public API – no account, no key. */
class DeezerPlaylists(private val base: String = "https://api.deezer.com") : PublicPlaylists {
    override val source = Source.Deezer

    private fun get(url: String): JSONObject {
        val json = JSONObject(Http.get(url))
        json.optJSONObject("error")?.let { throw ServerError(tr("Deezer: {name}", "name" to (it.optString("message").ifEmpty { "Fehler" }))) }
        return json
    }

    override fun search(query: String): List<RemoteList> =
        get("$base/search/playlist?q=${Http.encode(query)}&limit=25").optJSONArray("data").objects().map { p ->
            RemoteList(Source.Deezer, p.optString("id"), p.optString("title"), p.optJSONObject("user")?.optString("name") ?: "",
                p.optInt("nb_tracks"), p.str("picture_xl") ?: p.str("picture_medium"))
        }

    /** Name, owner and picture of one playlist (for a pasted link). */
    fun info(id: String): RemoteList = get("$base/playlist/${Http.encode(id)}").let { p ->
        RemoteList(Source.Deezer, id, p.optString("title"), p.optJSONObject("creator")?.optString("name") ?: "", p.optInt("nb_tracks"),
            p.str("picture_xl") ?: p.str("picture_medium"))
    }

    /** All titles, page by page ("next"). */
    override fun tracks(id: String): List<Wanted> {
        val out = mutableListOf<Wanted>()
        var url: String? = "$base/playlist/${Http.encode(id)}/tracks?limit=500"
        var pages = 0
        while (url != null && pages++ < 20) {
            val page = get(url)
            page.optJSONArray("data").objects().forEach { t ->
                out += Wanted(t.optString("title"), t.optJSONObject("artist")?.optString("name") ?: "", t.optJSONObject("album")?.optString("title") ?: "",
                    t.optInt("duration"), t.optJSONObject("album")?.str("cover_medium"))
            }
            url = page.str("next")
        }
        return out
    }
}

/** Spotify's Web API with the user's own app (client id + secret from developer.spotify.com, entered in the settings –
 *  nothing of the kind ships in LiDio's source). Since 11/2024 Spotify's own editorial playlists are closed to new apps;
 *  playlists made by users still work. */
class SpotifyPlaylists(private val clientId: String, private val secret: String, private val api: String = "https://api.spotify.com",
                       private val accounts: String = "https://accounts.spotify.com") : PublicPlaylists {
    override val source = Source.Spotify
    private var token: String? = null

    private fun bearer(): String = token ?: run {
        val basic = Base64.encodeToString("$clientId:$secret".toByteArray(), Base64.NO_WRAP)
        val reply = JSONObject(Http.form("$accounts/api/token", "grant_type=client_credentials", mapOf("Authorization" to "Basic $basic")))
        (reply.str("access_token") ?: throw ServerError(tr("Spotify hat die Client-ID abgelehnt."))).also { token = it }
    }

    private fun get(url: String) = JSONObject(Http.get(url, mapOf("Authorization" to "Bearer ${bearer()}")))

    override fun search(query: String): List<RemoteList> =
        get("$api/v1/search?type=playlist&limit=25&q=${Http.encode(query)}").optJSONObject("playlists")?.optJSONArray("items").objects()
            .map { p ->
                RemoteList(Source.Spotify, p.optString("id"), p.optString("name"), p.optJSONObject("owner")?.optString("display_name") ?: "",
                    p.optJSONObject("tracks")?.optInt("total") ?: 0, p.optJSONArray("images").objects().firstOrNull()?.str("url"))
            }

    override fun tracks(id: String): List<Wanted> {
        val out = mutableListOf<Wanted>()
        var url: String? = "$api/v1/playlists/${Http.encode(id)}/tracks?limit=100&fields=" +
            Http.encode("items(track(name,duration_ms,artists(name),album(name))),next")
        var pages = 0
        while (url != null && pages++ < 50) {
            val page = get(url)
            page.optJSONArray("items").objects().mapNotNull { it.optJSONObject("track") }.forEach { t ->
                val artists = t.optJSONArray("artists").objects().joinToString(", ") { it.optString("name") }
                out += Wanted(t.optString("name"), artists, t.optJSONObject("album")?.optString("name") ?: "", t.optInt("duration_ms") / 1000)
            }
            url = page.str("next")
        }
        return out
    }
}

/** LiDio privat (card 146a2d74): a Spotify playlist without any key – Spotify's embed page carries name, owner, picture and the
 *  titles (also Spotify's own editorial playlists, which its Web API closed to new apps; probably the first 100 titles only). */
object SpotifyEmbed {
    fun read(id: String): Pair<RemoteList, List<Wanted>> {
        val html = Http.get("https://open.spotify.com/embed/playlist/${Http.encode(id)}", mapOf("User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/130.0 Safari/537.36"))
        val data = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
            ?: throw ServerError(tr("Spotify hat die Playlist nicht gezeigt."))
        val e = JSONObject(data).optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state")?.optJSONObject("data")?.optJSONObject("entity")
            ?: throw ServerError(tr("Spotify hat die Playlist nicht gezeigt."))
        val tracks = e.optJSONArray("trackList").objects().map { t -> Wanted(t.optString("title"), t.optString("subtitle").replace("\u00a0", " "), "", t.optInt("duration") / 1000) }
        val cover = e.optJSONObject("coverArt")?.optJSONArray("sources").objects().maxByOrNull { it.optInt("width") }?.str("url")
        return RemoteList(Source.Spotify, id, e.optString("name").ifEmpty { e.optString("title") }, e.optString("subtitle"), tracks.size, cover) to tracks
    }
}

/** The titles of a public playlist: through the source the user switched on; in LiDio privat Spotify also without a key (embed
 *  page) and Deezer links also when Deezer search is off (a link is the user's own choice). */
object RemoteTracks {
    fun load(settings: Settings, list: RemoteList): Pair<RemoteList, List<Wanted>> {
        val source = settings.publicSources().firstOrNull { it.source == list.source }
        source?.let { s -> runCatching { s.tracks(list.id) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return list to it } }
        if (Variant.PRIVATE) when (list.source) {
            Source.Spotify -> return SpotifyEmbed.read(list.id).let { (meta, t) -> (if (list.name.isEmpty()) meta else list.copy(cover = list.cover ?: meta.cover)) to t }
            Source.Deezer -> {
                val d = DeezerPlaylists()
                val meta = if (list.name.isEmpty()) runCatching { d.info(list.id) }.getOrNull() ?: list else list
                return meta to d.tracks(list.id)
            }
            else -> {}
        }
        throw ServerError(if (source == null) tr("{label} ist ausgeschaltet (Mediathek → Server).", "label" to list.source.label) else tr("{label} hat die Playlist nicht geliefert.", "label" to list.source.label))
    }
}

/** Playlist links pasted anywhere: deezer.com/…/playlist/123, link.deezer.com is not resolved; open.spotify.com/playlist/ID. */
object Links {
    fun parse(text: String): Pair<Source, String>? {
        Regex("""deezer\.com/(?:[a-z]{2}/)?playlist/(\d+)""").find(text)?.let { return Source.Deezer to it.groupValues[1] }
        Regex("""open\.spotify\.com/(?:intl-[a-z]+/)?playlist/([A-Za-z0-9]{22})""").find(text)?.let { return Source.Spotify to it.groupValues[1] }
        Regex("""spotify:playlist:([A-Za-z0-9]{22})""").find(text)?.let { return Source.Spotify to it.groupValues[1] }
        return null
    }
}
