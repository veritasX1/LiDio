package io.github.veritasx1.lidio

import android.net.Uri

/** A shared title (card „Lied teilen“): an https link – Signal & Co. make only those clickable – whose details sit after
 *  the "#", which a browser never sends to the web server. Opened on a phone with LiDio, it plays the title: from the same
 *  server when the receiver has an account there, else the same song from the receiver's own library. */
data class Shared(val server: String, val id: String, val title: String, val artist: String, val album: String = "") {
    fun link(): String = "https://$HOST$PATH#" + listOf("s" to server, "t" to id, "n" to title, "a" to artist, "al" to album)
        .filter { it.second.isNotEmpty() }.joinToString("&") { (k, v) -> "$k=" + Uri.encode(v) }

    /** The text that goes with the link (Signal shows it above). */
    fun text(): String = "„$title“ von $artist – in LiDio anhören:\n${link()}"

    companion object {
        /** Olaf 05.10.2026: the app pages live under lisoftware.de/<app> – no subdomain of its own. Card a19168e0: links
         *  sent before then still say lisoft.goip.de, so both hosts are read (and both are App Links in the manifest). */
        const val HOST = "lisoftware.de"
        val HOSTS = setOf(HOST, "lisoft.goip.de")
        const val PATH = "/lidio/t"

        fun of(server: String, track: Track) = Shared(server, track.id, track.title, track.artist, track.album)

        /** Reads a link (https://lisoftware.de/lidio/t#…, older lisoft.goip.de, or lidio://t#…); null when it isn't one. */
        fun parse(uri: Uri): Shared? {
            val ok = (uri.scheme == "https" && uri.host in HOSTS && uri.path?.startsWith(PATH) == true) || (uri.scheme == "lidio" && uri.host == "t")
            if (!ok) return null
            val values = (uri.encodedFragment ?: return null).split("&").mapNotNull { part ->
                val (k, v) = part.split("=", limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                k to Uri.decode(v)
            }.toMap()
            if (values["k"] == "p") return null   // a playlist ("Mixtape") – SharedList reads it
            val title = values["n"] ?: return null
            return Shared(values["s"] ?: "", values["t"] ?: "", title, values["a"] ?: "", values["al"] ?: "")
        }
    }
}


/** Card c9b15c67 (Olaf 06.10.2026: „playlists als link versenden … das äquivalent zum früheren mixtape“): a playlist as a link.
 *  Everything sits after the "#" again – name and the titles (artist, title), packed (deflate + base64url), so even 100 titles
 *  make a link Signal takes. The receiver's LiDio opens the playlist itself on the same server, else rebuilds it from the own
 *  library (LiDio privat fills the gaps from the internet), with "Übertragen" to keep it. */
data class SharedList(val server: String, val id: String, val name: String, val items: List<Wanted>) {
    fun link(): String = "https://${Shared.HOST}${Shared.PATH}#" + listOf("k" to "p", "s" to server, "p" to id, "n" to name, "l" to pack(items))
        .filter { it.second.isNotEmpty() }.joinToString("&") { (k, v) -> "$k=" + Uri.encode(v) }

    fun text(): String = io.github.veritasx1.lidio.i18n.tr("Ein Mixtape für dich: „{name}“ – {n} Titel, in LiDio anhören:\n{link}",
        "name" to name, "n" to items.size, "link" to link())

    companion object {
        fun pack(items: List<Wanted>): String {
            val json = org.json.JSONArray(items.map { org.json.JSONArray(listOf(it.artist, it.title)) }).toString().toByteArray()
            val out = java.io.ByteArrayOutputStream()
            java.util.zip.DeflaterOutputStream(out, java.util.zip.Deflater(9)).use { it.write(json) }
            return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        }

        fun unpack(data: String): List<Wanted> = runCatching {
            val bytes = android.util.Base64.decode(data, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
            val json = java.util.zip.InflaterInputStream(bytes.inputStream()).readBytes().toString(Charsets.UTF_8)
            val a = org.json.JSONArray(json)
            (0 until a.length()).map { a.getJSONArray(it).let { e -> Wanted(e.optString(1), e.optString(0)) } }
        }.getOrDefault(emptyList())

        fun parse(uri: Uri): SharedList? {
            val ok = (uri.scheme == "https" && uri.host in Shared.HOSTS && uri.path?.startsWith(Shared.PATH) == true) || (uri.scheme == "lidio" && uri.host == "t")
            if (!ok) return null
            val values = (uri.encodedFragment ?: return null).split("&").mapNotNull { part ->
                val (k, v) = part.split("=", limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                k to Uri.decode(v)
            }.toMap()
            if (values["k"] != "p") return null
            val items = unpack(values["l"] ?: return null)
            return SharedList(values["s"] ?: "", values["p"] ?: "", values["n"] ?: "Mixtape", items).takeIf { items.isNotEmpty() }
        }
    }
}
