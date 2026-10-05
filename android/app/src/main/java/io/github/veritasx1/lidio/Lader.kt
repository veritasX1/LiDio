package io.github.veritasx1.lidio

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Card a984b806 / 81da3351: "Auf den Server laden" – LiDio privat asks the LiDio-Lader on the own server (Pi) to load a title
 *  straight into the music library and, from a playlist, to put it in its place there. The Lader checks the Emby key itself.
 *  Address: the Emby address with port 8097 at home, "/lidio-lader" behind the outside address. */
object Lader {
    /** Per page address: the job id and its state ("wartet", "laden", "einlesen", "fertig", "fehler") with a note. */
    val jobs = mutableStateMapOf<String, Pair<String, String>>()
    private val ids = mutableMapOf<String, String>()
    @Volatile private var reachable: Pair<String, Boolean>? = null

    private fun base(account: Account, address: String): String? {
        if (account.kind != ServerKind.Emby && account.kind != ServerKind.Jellyfin) return null
        val a = Reach.normal(address)
        return if (Regex(""":8096/?$""").containsMatchIn(a)) a.replace(Regex(""":8096/?$"""), ":8097") else "$a/lidio-lader"
    }

    private fun request(url: String, account: Account, body: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 15000
        c.setRequestProperty("X-Emby-Token", account.secret); c.setRequestProperty("X-LiDio-User", account.userId)
        if (body != null) { c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
        try {
            if (c.responseCode !in 200..299) throw ServerError(runCatching { JSONObject(c.errorStream.bufferedReader().readText()).optString("error") }.getOrNull() ?: "Lader: Fehler ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    /** Is a Lader there (asked once per address; blocking)? */
    fun available(account: Account?, address: String): Boolean {
        if (!Variant.PRIVATE || account == null) return false
        val b = base(account, address) ?: return false
        reachable?.let { if (it.first == b) return it.second }
        val ok = runCatching { request("$b/ping", account); true }.getOrDefault(false)
        reachable = b to ok
        return ok
    }

    /** Hands a title to the Lader (blocking); afterwards its state is followed with poll(). */
    fun load(account: Account, address: String, hit: WebHit, playlistId: String? = null, line: String? = null, position: Int? = null) {
        val b = base(account, address) ?: throw ServerError("Nur mit Emby oder Jellyfin.")
        val w = line?.let { MissingNote.wanted(it) }
        val body = JSONObject().put("url", hit.url).put("artist", w?.artist?.ifEmpty { null } ?: hit.artist).put("title", w?.title ?: hit.title)
            .put("album", hit.album ?: "").put("playlistId", playlistId).put("line", line).put("position", position)
        val id = JSONObject(request("$b/laden", account, body.toString())).getString("id")
        ids[hit.key] = id; jobs[hit.key] = "wartet" to ""
    }

    /** Asks for the states of running jobs (blocking). */
    fun poll(account: Account, address: String) {
        val b = base(account, address) ?: return
        val open = ids.filter { (k, _) -> jobs[k]?.first !in setOf("fertig", "fehler") }
        if (open.isEmpty()) return
        val reply = runCatching { JSONObject(request("$b/status?ids=${open.values.joinToString(",")}", account)) }.getOrNull() ?: return
        open.forEach { (k, id) -> reply.optJSONObject(id)?.let { jobs[k] = it.optString("state") to it.optString("note") } }
    }
}
