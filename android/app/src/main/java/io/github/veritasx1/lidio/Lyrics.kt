package io.github.veritasx1.lidio

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** One line of a song's text; the time (ms) when the text is synchronised ("[01:23.45] …" in an .lrc file). */
data class LyricLine(val ms: Long?, val text: String)

/** Lyrics (card 7ac89c11; Olaf: load them by themselves, keep them on the server; when nothing is found the listener must not
 *  notice anything). Order: the phone's own copy → the server (Jellyfin, Emby, Navidrome) → LRCLIB (free, no account; in the
 *  public LiDio only when switched on). What came from LRCLIB goes back to the server where it can take it (Jellyfin) and is
 *  kept on the phone. "Nothing found" is remembered for a week, so no title is asked about again and again. */
object Lyrics {
    private val time = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parse(text: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        text.lines().forEach { raw ->
            val stamps = time.findAll(raw).toList()
            val words = raw.replace(time, "").trim()
            if (stamps.isEmpty()) { if (raw.isNotBlank() && !Regex("""^\[[a-z]+:.*]$""").matches(raw.trim())) lines += LyricLine(null, raw.trim()) }
            else stamps.forEach { m ->
                val frac = m.groupValues[3]
                val ms = (m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()) * 1000 +
                    (if (frac.isEmpty()) 0 else frac.padEnd(3, '0').take(3).toLong())
                lines += LyricLine(ms, words)
            }
        }
        return if (lines.any { it.ms != null }) lines.filter { it.ms != null }.sortedBy { it.ms } else lines
    }

    /** SubRip ("1 / 00:00:07,310 --> 00:00:14,570 / text") – how Emby hands out a song's .lrc. */
    fun fromSrt(text: String): List<LyricLine> = text.removePrefix("\uFEFF").replace("\r", "").split(Regex("""\n\s*\n""")).mapNotNull { block ->
        val lines = block.trim().lines()
        val i = lines.indexOfFirst { "-->" in it }.takeIf { it >= 0 } ?: return@mapNotNull null
        val m = Regex("""(\d+):(\d{2}):(\d{2})[,.](\d{1,3})""").find(lines[i]) ?: return@mapNotNull null
        val (h, min, s, ms) = m.destructured
        LyricLine(((h.toLong() * 60 + min.toLong()) * 60 + s.toLong()) * 1000 + ms.padEnd(3, '0').toLong(), lines.drop(i + 1).joinToString(" ").trim())
    }

    fun toLrc(lines: List<LyricLine>): String = lines.joinToString("\n") { l ->
        l.ms?.let { "[%02d:%02d.%02d] ".format(it / 60000, it / 1000 % 60, it % 1000 / 10) + l.text } ?: l.text
    }

    /** The line playing at this position (synchronised texts only). */
    fun current(lines: List<LyricLine>, positionMs: Long): Int = lines.indexOfLast { (it.ms ?: Long.MAX_VALUE) <= positionMs + 250 }

    private fun dir(context: Context) = File(context.filesDir, "liedtexte").apply { mkdirs() }
    private fun key(track: Track) = SubsonicServer.md5("${Matcher.normal(track.artist)}|${Matcher.normal(track.title)}").take(24)

    /** Blocking – off the main thread. Null when there is no text (instrumentals, nothing found, no network): quietly. */
    fun load(context: Context, server: MusicServer?, track: Track, internet: Boolean): List<LyricLine>? {
        val file = File(dir(context), key(track) + ".lrc")
        val none = File(dir(context), key(track) + ".keine")
        if (file.exists()) return parse(file.readText()).ifEmpty { null }
        if (none.exists() && System.currentTimeMillis() - none.lastModified() < 7 * 24 * 3600_000L && server == null) return null
        // 1. The server already has it (.lrc beside the file, embedded, or uploaded before).
        server?.let { s -> runCatching { s.lyrics(track) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { text ->
            file.writeText(text); return parse(text).ifEmpty { null } } }
        if (none.exists() && System.currentTimeMillis() - none.lastModified() < 7 * 24 * 3600_000L) return null
        if (!internet) return null
        // 2. LRCLIB – synchronised where it has it.
        val text = runCatching { lrclib(track) }.getOrNull()
        if (text.isNullOrBlank()) { runCatching { none.writeText("") }; return null }
        file.writeText(text)
        server?.let { s -> runCatching { s.saveLyrics(track, text) } }
        return parse(text).ifEmpty { null }
    }

    /** LRCLIB's exact match (artist, title, length), else its search. Instrumentals have no text. */
    fun lrclib(track: Track): String? {
        fun ask(url: String): String? {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 12000
            c.setRequestProperty("User-Agent", "LiDio (https://lisoft.goip.de)")
            return try { if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
        }
        fun pick(j: JSONObject): String? = if (j.optBoolean("instrumental")) null
            else j.str("syncedLyrics") ?: j.str("plainLyrics")
        val q = "artist_name=${Http.encode(track.artist)}&track_name=${Http.encode(track.title)}" +
            (if (track.album.isNotEmpty()) "&album_name=${Http.encode(track.album)}" else "") +
            (if (track.duration > 0) "&duration=${track.duration}" else "")
        ask("https://lrclib.net/api/get?$q")?.let { return pick(JSONObject(it)) }
        val found = ask("https://lrclib.net/api/search?track_name=${Http.encode(track.title)}&artist_name=${Http.encode(track.artist)}") ?: return null
        return org.json.JSONArray(found).objects()
            .filter { track.duration == 0 || kotlin.math.abs(it.optDouble("duration", 0.0) - track.duration) < 4 }
            .sortedByDescending { it.str("syncedLyrics") != null }.firstNotNullOfOrNull(::pick)
    }
}
