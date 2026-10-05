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
        /** Olaf 05.10.2026: the app pages live under lisoft.goip.de/<app> – no subdomain of its own. */
        const val HOST = "lisoft.goip.de"
        const val PATH = "/lidio/t"

        fun of(server: String, track: Track) = Shared(server, track.id, track.title, track.artist, track.album)

        /** Reads a link (https://lisoft.goip.de/lidio/t#… or lidio://t#…); null when it isn't one. */
        fun parse(uri: Uri): Shared? {
            val ok = (uri.scheme == "https" && uri.host == HOST && uri.path?.startsWith(PATH) == true) || (uri.scheme == "lidio" && uri.host == "t")
            if (!ok) return null
            val values = (uri.encodedFragment ?: return null).split("&").mapNotNull { part ->
                val (k, v) = part.split("=", limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                k to Uri.decode(v)
            }.toMap()
            val title = values["n"] ?: return null
            return Shared(values["s"] ?: "", values["t"] ?: "", title, values["a"] ?: "", values["al"] ?: "")
        }
    }
}
