package io.github.veritasx1.lidio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Card 561fa339: Deezer and Spotify playlists against canned answers, and playlist links. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteTest {
    private val answers = mutableMapOf<String, String>()
    private lateinit var http: TinyHttp
    private val base get() = "http://127.0.0.1:${http.port}"

    @Before fun start() { http = TinyHttp { path -> answers.entries.firstOrNull { path.endsWith(it.key) }?.value } }
    @After fun stop() = http.close()

    @Test
    fun deezerSearchAndPages() {
        answers["/search/playlist"] = """{"data":[{"id":908622995,"title":"Testtöne Mix","nb_tracks":3,"picture_medium":"http://bild","user":{"name":"Olaf"}}],"total":1}"""
        answers["/playlist/908622995/tracks"] = """{"data":[{"title":"Quinte","duration":15,"artist":{"name":"Die Testtöne"},"album":{"title":"Sinus & Söhne"}},
            {"title":"Spirale","duration":14,"artist":{"name":"Kreis Quartett"},"album":{"title":"Rundgang"}}],"next":"BASE_URL/playlist/908622995/tracks2"}"""
        answers["/playlist/908622995/tracks2"] = """{"data":[{"title":"Rosa","duration":14,"artist":{"name":"Anna Analog"},"album":{"title":"Rauschen"}}]}"""
        answers.replaceAll { _, v -> v.replace("BASE_URL", base) }
        val deezer = DeezerPlaylists(base)
        val found = deezer.search("testtöne")
        assertEquals(RemoteList(Source.Deezer, "908622995", "Testtöne Mix", "Olaf", 3, "http://bild"), found.single())
        assertEquals(listOf("Quinte", "Spirale", "Rosa"), deezer.tracks("908622995").map { it.title })
        assertTrue(http.seen.first().first.contains("q=testt%C3%B6ne"))
    }

    @Test
    fun spotifyClientCredentials() {
        answers["/api/token"] = """{"access_token":"abc","token_type":"Bearer","expires_in":3600}"""
        answers["/v1/search"] = """{"playlists":{"items":[{"id":"37i9","name":"Töne","owner":{"display_name":"anna"},"tracks":{"total":1},"images":[{"url":"http://img"}]}]}}"""
        answers["/v1/playlists/37i9/tracks"] = """{"items":[{"track":{"name":"Moll","duration_ms":14000,"artists":[{"name":"Die Testtöne"},{"name":"Anna Analog"}],"album":{"name":"Zweite Welle"}}}],"next":null}"""
        val spotify = SpotifyPlaylists("id", "geheim", base, base)
        assertEquals("Töne", spotify.search("töne").single().name)
        assertEquals(listOf(Wanted("Moll", "Die Testtöne, Anna Analog", "Zweite Welle", 14)), spotify.tracks("37i9"))
        val token = http.seen.first()
        assertTrue(token.second.entries.any { it.key.equals("Authorization", true) && it.value == "Basic aWQ6Z2VoZWlt" })   // base64("id:geheim")
        assertTrue(http.seen.drop(1).all { s -> s.second.entries.any { it.key.equals("Authorization", true) && it.value == "Bearer abc" } })
        assertEquals(2, http.seen.count { it.first.startsWith("/v1/") })   // the token is fetched once
    }

    @Test
    fun playlistLinks() {
        assertEquals(Source.Deezer to "908622995", Links.parse("Hör mal: https://www.deezer.com/de/playlist/908622995?utm=x"))
        assertEquals(Source.Spotify to "37i9dQZF1DXcBWIGoYBM5M", Links.parse("https://open.spotify.com/intl-de/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc"))
        assertEquals(Source.Spotify to "4rOoJ6Egrf8K2IrywzwOMk", Links.parse("spotify:playlist:4rOoJ6Egrf8K2IrywzwOMk"))
        assertEquals(null, Links.parse("Die Testtöne – Quinte"))
    }

    @Test
    fun deezerIsOffUntilSwitchedOn() {
        val settings = Settings(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        assertEquals(emptyList<PublicPlaylists>(), settings.publicSources())
        settings.deezer = true
        assertEquals(listOf(Source.Deezer), settings.publicSources().map { it.source })
        settings.deezer = false
    }
}
