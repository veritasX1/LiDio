package io.github.veritasx1.lidio

import java.text.Normalizer

/** One line of an imported list: what the list says (title, artist, maybe album and length). */
data class Wanted(val title: String, val artist: String = "", val album: String = "", val seconds: Int = 0,
                  /** The cover the source already knows (Deezer gives every title its album cover) – shown at once. */
                  val cover: String? = null) {
    override fun toString() = if (artist.isEmpty()) title else "$artist – $title"
}

/** Reads playlists in the usual formats (card 4): M3U/M3U8, PLS, XSPF, CSV (e.g. Exportify), and plain text
 *  ("Interpret – Titel", one per line). Paths in M3U/PLS without #EXTINF give the file name as the title. */
object Lists {
    fun read(text: String, name: String = ""): List<Wanted> {
        val clean = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val lower = name.lowercase()
        return when {
            lower.endsWith(".xspf") || clean.trimStart().startsWith("<?xml") || clean.contains("<playlist") && clean.contains("<trackList") -> xspf(clean)
            lower.endsWith(".pls") || clean.trimStart().startsWith("[playlist]", ignoreCase = true) -> pls(clean)
            lower.endsWith(".m3u") || lower.endsWith(".m3u8") || clean.trimStart().startsWith("#EXTM3U") -> m3u(clean)
            lower.endsWith(".csv") || looksLikeCsv(clean) -> csv(clean)
            else -> text(clean)
        }.filter { it.title.isNotBlank() }
    }

    private fun m3u(text: String): List<Wanted> {
        val out = mutableListOf<Wanted>()
        var info: Wanted? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF", true) -> {
                    val body = line.substringAfter(':')
                    val seconds = body.substringBefore(',').trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
                    info = split(body.substringAfter(',', "")).copy(seconds = seconds)
                }
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> { out += info ?: fromPath(line); info = null }
            }
        }
        return out
    }

    private fun pls(text: String): List<Wanted> {
        val files = mutableMapOf<Int, String>(); val titles = mutableMapOf<Int, String>(); val lengths = mutableMapOf<Int, Int>()
        Regex("""^(File|Title|Length)(\d+)=(.*)$""", RegexOption.IGNORE_CASE).let { pattern ->
            text.lines().mapNotNull { pattern.find(it.trim()) }.forEach { m ->
                val n = m.groupValues[2].toInt(); val v = m.groupValues[3].trim()
                when (m.groupValues[1].lowercase()) { "file" -> files[n] = v; "title" -> titles[n] = v; "length" -> lengths[n] = v.toIntOrNull() ?: 0 }
            }
        }
        return (files.keys + titles.keys).toSortedSet().map { n ->
            (titles[n]?.let(::split) ?: fromPath(files[n] ?: "")).copy(seconds = lengths[n]?.coerceAtLeast(0) ?: 0)
        }
    }

    private fun xspf(text: String): List<Wanted> = Regex("""<track>(.*?)</track>""", RegexOption.DOT_MATCHES_ALL).findAll(text).map { m ->
        fun tag(name: String) = Regex("""<$name>(.*?)</$name>""", RegexOption.DOT_MATCHES_ALL).find(m.groupValues[1])?.groupValues?.get(1)?.let(::unescape)?.trim() ?: ""
        val title = tag("title").ifEmpty { fromPath(tag("location")).title }
        Wanted(title, tag("creator"), tag("album"), (tag("duration").toLongOrNull() ?: 0L).div(1000).toInt())
    }.toList()

    private fun looksLikeCsv(text: String): Boolean {
        val head = text.lineSequence().firstOrNull()?.lowercase() ?: return false
        return (head.contains(",") || head.contains(";")) && (head.contains("track") || head.contains("title") || head.contains("titel")) &&
            (head.contains("artist") || head.contains("interpret"))
    }

    /** CSV with a header row; the columns are found by name (Exportify: "Track Name", "Artist Name(s)", "Album Name", "Duration (ms)"). */
    private fun csv(text: String): List<Wanted> {
        val rows = text.lines().filter { it.isNotBlank() }
        if (rows.isEmpty()) return emptyList()
        val separator = if (rows[0].count { it == ';' } > rows[0].count { it == ',' }) ';' else ','
        val head = cells(rows[0], separator).map { it.lowercase() }
        fun column(vararg names: String) = head.indexOfFirst { h -> names.any { h.contains(it) } }
        val title = column("track name", "title", "titel", "name"); val artist = column("artist", "interpret", "künstler")
        val album = column("album"); val ms = column("duration (ms)", "dauer (ms)")
        return rows.drop(1).map { row ->
            val c = cells(row, separator)
            fun at(i: Int) = c.getOrNull(i)?.trim() ?: ""
            Wanted(at(title), at(artist).replace(";", ", "), at(album), ((at(ms).toLongOrNull() ?: 0L) / 1000).toInt())
        }
    }

    private fun cells(line: String, separator: Char): List<String> {
        val out = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && line.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == separator && !quoted -> { out += cell.toString(); cell.clear() }
                else -> cell.append(ch)
            }
            i++
        }
        out += cell.toString()
        return out
    }

    private fun text(text: String): List<Wanted> = text.lines().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
        .map { line -> split(line.replace(Regex("""^\s*\d{1,3}[.)]\s+"""), "")) }   // "1. Artist – Title"

    /** "Artist – Title" (dash, en dash, em dash or tab); without a separator the whole line is the title. */
    fun split(line: String): Wanted {
        val parts = line.split(Regex("""\s+[-–—]\s+|\t""" ), limit = 2)
        return if (parts.size == 2) Wanted(parts[1].trim(), parts[0].trim()) else Wanted(line.trim())
    }

    private fun fromPath(path: String): Wanted {
        val file = path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
        return split(java.net.URLDecoder.decode(file.replace("+", "%2B"), "UTF-8").replace(Regex("""^\d{1,3}[ ._-]+"""), ""))
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
}

/** How a wanted title was found on the server. */
enum class Match { Found, Unsure, Missing }

data class Result(val wanted: Wanted, val match: Match, val track: Track?, val choices: List<Track> = emptyList())

/** Compares titles tolerantly: case, accents and umlauts, "(feat. …)", "- Remastered 2011", brackets, punctuation, small typos. */
object Matcher {
    private val noise = Regex("""\s*[(\[]\s*(feat\.?|ft\.?|featuring|with|mit|remaster(ed)?|live|radio edit|single version|album version|explicit|mono|stereo|\d{4} remaster(ed)?)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private val dashNoise = Regex("""\s+-\s+(remaster(ed)?|\d{4} remaster(ed)?|live|radio edit|single version|mono|stereo|bonus track)\b.*$""", RegexOption.IGNORE_CASE)

    fun normal(text: String): String {
        var s = text.lowercase().replace(noise, "").replace(dashNoise, "")
        s = s.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss").replace("&", " und ").replace(" and ", " und ")
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("""\p{M}"""), "")
        s = s.replace(Regex("""[^a-z0-9 ]"""), " ").replace(Regex("""\b(the|die|der|das)\b"""), " ")
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    /** 0…1, Levenshtein on the normalised strings. */
    fun similar(a: String, b: String): Double {
        val x = normal(a); val y = normal(b)
        if (x == y) return 1.0
        if (x.isEmpty() || y.isEmpty()) return 0.0
        val d = IntArray(y.length + 1) { it }
        for (i in 1..x.length) {
            var previous = d[0]; d[0] = i
            for (j in 1..y.length) {
                val cost = if (x[i - 1] == y[j - 1]) 0 else 1
                val keep = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, previous + cost); previous = keep
            }
        }
        return 1.0 - d[y.length].toDouble() / maxOf(x.length, y.length)
    }

    /** Artist agreement: any of the listed artists in the track's artist (lists often add or drop featured artists). */
    fun artistScore(wanted: String, have: String): Double {
        if (wanted.isBlank()) return 0.8
        val names = wanted.split(Regex(""",|&|\bfeat\.?|\bft\.?|\bx\b|/""", RegexOption.IGNORE_CASE)).map(::normal).filter { it.isNotEmpty() }
        val h = normal(have)
        return if (names.any { h.contains(it) || it.contains(h) && h.isNotEmpty() }) 1.0 else names.maxOfOrNull { similar(it, have) } ?: 0.0
    }

    fun score(w: Wanted, t: Track): Double {
        val title = similar(w.title, t.title)
        val artist = artistScore(w.artist, t.artist)
        val length = if (w.seconds > 0 && t.duration > 0 && kotlin.math.abs(w.seconds - t.duration) > 15) 0.85 else 1.0
        return (title * 0.65 + artist * 0.35) * length
    }

    /** Best candidate: sure from 0.9, unsure from 0.6 (the user decides), else missing. */
    fun judge(w: Wanted, candidates: List<Track>): Result {
        val ranked = candidates.distinctBy { it.id }.map { it to score(w, it) }.sortedByDescending { it.second }
        val best = ranked.firstOrNull() ?: return Result(w, Match.Missing, null)
        return when {
            best.second >= 0.9 -> Result(w, Match.Found, best.first)
            best.second >= 0.6 -> Result(w, Match.Unsure, best.first, ranked.take(5).map { it.first })
            else -> Result(w, Match.Missing, null, ranked.take(5).filter { it.second >= 0.5 }.map { it.first })
        }
    }

    /** Searches the server for each line (title first, then artist + title) and judges the hits. */
    fun run(server: MusicServer, wanted: List<Wanted>, progress: (Int) -> Unit = {}): List<Result> = wanted.mapIndexed { i, w ->
        progress(i)
        val hits = runCatching { server.search(normal(w.title).ifEmpty { w.title }).tracks }.getOrDefault(emptyList())
        var result = judge(w, hits)
        if (result.match != Match.Found && w.artist.isNotEmpty()) {
            // Servers search by parts of words – "Spiralen" doesn't find "Spirale". So also everything by the artist.
            val more = listOf("${w.artist} ${w.title}", w.artist.split(",").first().trim())
                .flatMap { q -> runCatching { server.search(q).tracks }.getOrDefault(emptyList()) }
            result = judge(w, hits + more)
        }
        result
    }

    /** The missing ones as text – one "Artist – Title" per line (e.g. for filling the gaps privately on the server). */
    fun missingText(results: List<Result>) = results.filter { it.match == Match.Missing }.joinToString("\n") { it.wanted.toString() }
}

/** Hides duplicates (setting): the same title by the same artist with about the same length, and albums of the same name
 *  and artist – the copy with more titles (or the first) stays. */
object Duplicates {
    /** One pass (card 5902afb5): titles grouped by normalised title + artist; within a group only lengths are compared. */
    fun tracks(list: List<Track>): List<Track> {
        val seen = HashMap<String, MutableList<Track>>()
        return list.filter { t ->
            val group = seen.getOrPut(Matcher.normal(t.title) + "|" + Matcher.normal(t.artist)) { mutableListOf() }
            // The same entry twice in a playlist (2Pac Top 30: California Love) counts as a duplicate too (Olaf 05.10.2026).
            if (group.any { it.id == t.id || it.duration == 0 || t.duration == 0 || kotlin.math.abs(it.duration - t.duration) <= 2 }) false
            else { group += t; true }
        }
    }

    fun same(a: Track, b: Track) = a.id != b.id && Matcher.normal(a.title) == Matcher.normal(b.title) &&
        Matcher.normal(a.artist) == Matcher.normal(b.artist) && (a.duration == 0 || b.duration == 0 || kotlin.math.abs(a.duration - b.duration) <= 2)

    fun albums(list: List<Album>): List<Album> = list.groupBy { Matcher.normal(it.title) + "|" + Matcher.normal(it.artist) }
        .values.map { group -> group.maxByOrNull { it.trackCount } ?: group.first() }
        .sortedBy { a -> list.indexOfFirst { it.id == a.id } }
}
