package io.github.veritasx1.lidio

import android.content.Context
import androidx.compose.ui.graphics.Color

/** The public LiDio: music from the own server and the own folders; from the internet only public Deezer and Spotify
 *  playlists to compare with the own library (card 1843f577). */
object Variant {
    const val PRIVATE = false
    const val MUSIC = ""
    fun engine(context: Context): WebEngine? = null
    fun webServer(context: Context): MusicServer? = null

    fun source(s: WebSource): SourceInfo = when (s) {
        WebSource.All -> SourceInfo("Alle Quellen", Color(0xFF8E8E93))
        WebSource.Deezer -> SourceInfo("Deezer", Color(0xFFA238FF), listOf("deezer.com", "deezer.page.link"))
        WebSource.Spotify -> SourceInfo("Spotify", Color(0xFF1DB954), listOf("open.spotify.com", "spotify.link"))
        else -> SourceInfo("Link", Color(0xFF8E8E93))
    }

    fun key(url: String): String = url.substringBefore('?').trimEnd('/')
    fun watch(id: String): String = id
    fun text(key: String): String = ""
    fun helpChapter(): Chapter? = null
}
