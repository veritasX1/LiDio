package io.github.veritasx1.lidio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Card 2: the three servers' APIs against canned answers – login, headers, paths and reading the replies. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerApiTest {
    private val answers = mutableMapOf<String, String>()
    private lateinit var http: TinyHttp
    private val seen get() = http.seen
    private val base get() = "http://127.0.0.1:${http.port}"

    @Before
    fun start() { http = TinyHttp { path -> answers.entries.firstOrNull { path.endsWith(it.key) }?.value } }

    @After
    fun stop() = http.close()

    @Test
    fun navidromeWithToken() {
        answers["/rest/ping"] = """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.64.2"}}"""
        answers["/rest/getAlbum"] = """{"subsonic-response":{"status":"ok","album":{"id":"al1","name":"Sinus & Söhne","artist":"Die Testtöne","artistId":"ar1",
            "year":2021,"coverArt":"al-1","songCount":2,"duration":27,"song":[
            {"id":"s1","title":"Erster Ton","artist":"Die Testtöne","album":"Sinus & Söhne","albumId":"al1","duration":13,"track":1,"coverArt":"al-1"},
            {"id":"s2","title":"Kammerton A","artist":"Die Testtöne","album":"Sinus & Söhne","albumId":"al1","duration":14,"track":2}]}}}"""
        val server = SubsonicServer(base, "test", "geheim", salt = "abc")
        assertEquals("Navidrome 0.64.2", server.check())
        val (album, tracks) = server.album("al1")
        assertEquals("Sinus & Söhne", album.title); assertEquals(2021, album.year)
        assertEquals(listOf("Erster Ton", "Kammerton A"), tracks.map { it.title })
        val url = seen.first().first
        assertTrue(url.contains("t=" + SubsonicServer.md5("geheimabc")) && url.contains("s=abc"))
        assertFalse("Passwort im Klartext", seen.any { it.first.contains("geheim") })
    }

    @Test
    fun navidromeWrongPassword() {
        answers["/rest/ping"] = """{"subsonic-response":{"status":"failed","error":{"code":40,"message":"Wrong username or password"}}}"""
        val error = runCatching { SubsonicServer(base, "test", "falsch").check() }.exceptionOrNull()
        assertEquals("Anmeldung abgelehnt – Benutzername oder Passwort stimmen nicht.", error?.message)
    }

    @Test
    fun embyLoginAndAlbum() {
        answers["/emby/Users/AuthenticateByName"] = """{"User":{"Id":"u1","Name":"olaf"},"AccessToken":"tok-1"}"""
        answers["/emby/Users/u1/Items/al1"] = """{"Id":"al1","Name":"Rundgang","AlbumArtist":"Kreis Quartett","AlbumArtists":[{"Name":"Kreis Quartett","Id":"ar2"}],
            "ProductionYear":2019,"ImageTags":{"Primary":"x"},"ChildCount":2,"Genres":["Testmusik"]}"""
        answers["/emby/Users/u1/Items"] = """{"Items":[
            {"Id":"t1","Name":"Kreis","Artists":["Kreis Quartett"],"Album":"Rundgang","AlbumId":"al1","AlbumPrimaryImageTag":"x","RunTimeTicks":120000000,"IndexNumber":1},
            {"Id":"t2","Name":"Spirale","Artists":["Kreis Quartett"],"Album":"Rundgang","AlbumId":"al1","RunTimeTicks":130000000,"IndexNumber":2}],"TotalRecordCount":2}"""
        val (userId, token) = MediaBrowserServer.login(ServerKind.Emby, base, "olaf", "pw", "geraet-1")
        assertEquals("u1" to "tok-1", userId to token)
        val login = seen.first()
        assertTrue(login.first.startsWith("/emby/Users/AuthenticateByName"))
        assertTrue(login.second.entries.any { it.key.equals("X-Emby-Authorization", true) && it.value.contains("DeviceId=\"geraet-1\"") })

        val server = MediaBrowserServer(ServerKind.Emby, base, userId, token, "geraet-1")
        val (album, tracks) = server.album("al1")
        assertEquals("Rundgang", album.title); assertEquals("Kreis Quartett", album.artist); assertEquals("ar2", album.artistId)
        assertEquals(listOf(12, 13), tracks.map { it.duration })
        assertEquals("al1", tracks[0].coverId)   // the album's picture for a track without its own
        assertTrue(seen.last().second.entries.any { it.key.equals("X-Emby-Token", true) && it.value == "tok-1" })
        assertTrue(server.streamUrl(tracks[0]).contains("/emby/Audio/t1/universal?") && server.streamUrl(tracks[0]).contains("api_key=tok-1"))
        assertTrue(server.streamUrl(tracks[0], 192).contains("MaxStreamingBitrate=192000"))
    }

    @Test
    fun jellyfinCamelCaseAndApiKey() {
        answers["/Users/u9/Items"] = """{"items":[{"id":"t1","name":"Welle","artists":["Kreis Quartett"],"album":"Rundgang","albumId":"al1",
            "imageTags":{"Primary":"y"},"runTimeTicks":150000000,"indexNumber":4}],"totalRecordCount":1}"""
        val server = MediaBrowserServer(ServerKind.Jellyfin, base, "u9", "tok-9", "geraet-9")
        val tracks = server.tracks()
        assertEquals("Welle", tracks.single().title); assertEquals(15, tracks.single().duration); assertEquals("t1", tracks.single().coverId)
        assertTrue(seen.last().first.startsWith("/Users/u9/Items"))
        assertTrue(seen.last().second.entries.any { it.key.equals("Authorization", true) && it.value.contains("Token=\"tok-9\"") })
        assertTrue(server.streamUrl(tracks[0]).contains("ApiKey=tok-9")); assertFalse(server.streamUrl(tracks[0]).contains("api_key"))
        assertTrue(server.coverUrl("t1", 300).contains("ApiKey=tok-9"))
    }

    @Test
    fun unreachableServerSaysSo() {
        val error = runCatching { SubsonicServer("http://127.0.0.1:1", "a", "b").check() }.exceptionOrNull()
        assertEquals("Keine Verbindung zum Server.", error?.message)
    }

    @Test
    fun playlistsAreCreatedInChunks() {
        answers["/rest/createPlaylist"] = """{"subsonic-response":{"status":"ok","playlist":{"id":"pl1","name":"Import"}}}"""
        answers["/rest/updatePlaylist"] = """{"subsonic-response":{"status":"ok"}}"""
        val tracks = (1..150).map { Track("s$it", "T$it", "A", "") }
        val made = SubsonicServer(base, "test", "pw").createPlaylist("Import", tracks)
        assertEquals("pl1", made.id); assertEquals(150, made.trackCount)
        assertEquals(100, Regex("songId=").findAll(seen[0].first).count())
        assertEquals(50, Regex("songIdToAdd=").findAll(seen[1].first).count())

        answers["/emby/Playlists"] = """{"Id":"epl"}"""
        answers["/emby/Playlists/epl/Items"] = ""
        val emby = MediaBrowserServer(ServerKind.Emby, base, "u1", "tok").createPlaylist("Import", tracks)
        assertEquals("epl", emby.id)
        assertTrue(seen.any { it.first.startsWith("/emby/Playlists?Name=Import") && it.first.contains("MediaType=Audio") })
        assertTrue(seen.any { it.first.startsWith("/emby/Playlists/epl/Items?Ids=s101,") })
    }

    @Test
    fun formatsThePhoneCantOpenComeConverted() {
        val server = SubsonicServer("http://x", "u", "p")
        assertFalse(server.streamUrl(Track("1", "a", "b", "", suffix = "flac")).contains("format="))
        assertTrue(server.streamUrl(Track("2", "a", "b", "", suffix = "wma")).contains("format=mp3"))
        assertTrue(server.streamUrl(Track("3", "a", "b", "", suffix = "APE"), 128).contains("maxBitRate=128"))
        assertTrue(server.streamUrl(Track("4", "a", "b", "", suffix = "wv")).contains("maxBitRate=320"))
        assertTrue(Formats.playable("Opus") && Formats.playable(null) && !Formats.playable("dsf"))
    }
}
