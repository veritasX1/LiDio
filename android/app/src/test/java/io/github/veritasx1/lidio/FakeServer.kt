package io.github.veritasx1.lidio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

/** A server in memory with LiDio's own test music (the same names as the generated test library). */
class FakeServer : MusicServer {
    override val kind = ServerKind.Navidrome
    private val colours = mapOf("a1" to 0xFFFA2D48.toInt(), "a2" to 0xFFFF9500.toInt(), "a3" to 0xFF34C759.toInt(), "a4" to 0xFF007AFF.toInt(), "a5" to 0xFFAF52DE.toInt())
    val albums = listOf(
        Album("a1", "Sinus & Söhne", "Die Testtöne", "r1", 2021, "a1", 6, 93, "Testmusik"),
        Album("a2", "Zweite Welle", "Die Testtöne", "r1", 2023, "a2", 5, 75, "Testmusik"),
        Album("a3", "Rundgang", "Kreis Quartett", "r2", 2019, "a3", 7, 119, "Testmusik"),
        Album("a4", "Rauschen", "Anna Analog", "r3", 2024, "a4", 3, 42, "Testmusik"),
        Album("a5", "Sampler Nr. 1", "Verschiedene", "r4", 2020, "a5", 4, 58, "Testmusik", compilation = true))
    private val names = mapOf("a1" to listOf("Erster Ton", "Kammerton A", "Quinte", "Oktave", "Schwebung", "Abendlied"),
        "a2" to listOf("Morgen", "Dur", "Moll", "Sekunde", "Terz"), "a3" to listOf("Kreis", "Spirale", "Bogen", "Welle", "Kurve", "Ellipse", "Zirkel"),
        "a4" to listOf("Weißes Rauschen", "Rosa", "Brumm"), "a5" to listOf("Mix eins", "Mix zwei", "Mix drei", "Mix vier"))
    val tracks = albums.flatMap { album ->
        names.getValue(album.id).mapIndexed { i, title ->
            Track("${album.id}t${i + 1}", title, if (album.compilation) listOf("Die Testtöne", "Kreis Quartett", "Anna Analog")[i % 3] else album.artist,
                album.title, album.id, 12 + i, i + 1, 1, album.year, album.id)
        }
    }

    init {
        colours.forEach { (id, colour) ->
            Covers.fixed[coverUrl(id, 0)] = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).apply {
                val canvas = Canvas(this); canvas.drawColor(colour)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = 0xFFFFFFFF.toInt() }
                for (r in 0 until 5) canvas.drawCircle(150f, 150f, 110f - r * 14f, paint)
            }
        }
    }

    override fun check() = "Navidrome (Test)"
    override fun artists() = listOf(Artist("r3", "Anna Analog", 1), Artist("r1", "Die Testtöne", 2), Artist("r2", "Kreis Quartett", 1), Artist("r4", "Verschiedene", 1))
    override fun artist(id: String) = artists().first { it.id == id } to albums.filter { it.artistId == id }
    override fun albums(order: AlbumOrder, size: Int, offset: Int) = when (order) {
        AlbumOrder.Alphabetical -> albums.sortedBy { it.title }
        AlbumOrder.Recent -> albums.take(2)
        else -> albums
    }.drop(offset).take(size)
    override fun album(id: String) = albums.first { it.id == id } to tracks.filter { it.albumId == id }
    override fun tracks(size: Int, offset: Int) = tracks.sortedBy { it.title }.drop(offset).take(size)
    override fun playlists() = listOf(Playlist("p1", "Lieblingstöne", 4, 60, "a3"))
    override fun playlist(id: String) = playlists().first() to tracks.filter { it.title.length < 6 }.take(4)
    override fun search(query: String) = SearchResult(artists().filter { it.name.contains(query, true) },
        albums.filter { it.title.contains(query, true) },
        // Like real servers: titles are found by their title, artist or album.
        tracks.filter { t -> listOf(t.title, t.artist, t.album, "${t.artist} ${t.title}").any { it.contains(query, true) } })
    override fun streamUrl(track: Track, maxBitrate: Int) = "fake://stream/${track.id}"
    val created = mutableListOf<Pair<String, List<Track>>>()
    override fun createPlaylist(name: String, tracks: List<Track>) = Playlist("neu${created.size}", name, tracks.size).also { created += name to tracks }
    // Size is left out on purpose: every size of a cover is the same test picture.
    override fun coverUrl(coverId: String, size: Int) = "fake://cover/$coverId"
}
