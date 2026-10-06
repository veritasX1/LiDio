package io.github.veritasx1.lidio

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Card „Lied teilen“: the link and what the receiving LiDio does with it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun clean() { context.getSharedPreferences("konten", 0).edit().clear().commit(); context.getSharedPreferences("serverkennung", 0).edit().clear().commit() }

    @Test
    fun linkRoundTrip() {
        val shared = Shared("ae0c2c1c", "t42", "Weißes Rauschen & mehr", "Anna Analog", "Rauschen #1")
        val link = shared.link()
        assertTrue(link.startsWith("https://lisoftware.de/lidio/t#"))
        assertEquals(shared, Shared.parse(Uri.parse(link)))
        assertTrue("Text: $link", shared.text().contains("„Weißes Rauschen & mehr“ von Anna Analog"))
        // The details are in the fragment: a browser never sends them to the web server.
        assertEquals("/lidio/t", Uri.parse(link).path)
    }

    @Test
    fun otherLinksAreNotOurs() {
        assertNull(Shared.parse(Uri.parse("https://example.com/t#n=x")))
        assertNull(Shared.parse(Uri.parse("https://lisoft.goip.de/lidio/")))
        assertEquals("Quinte", Shared.parse(Uri.parse("lidio://t#n=Quinte&a=Die%20Testt%C3%B6ne"))?.title)
    }

    @Test
    fun sameServerPlaysThatTitle() = runBlocking {
        val server = FakeServer()
        val accounts = Accounts(context)
        accounts.save(Account("a1", ServerKind.Navidrome, "http://test", "u", "p"))
        val state = AppState(accounts, Playback(context)) { server }.apply { probe = { _, _ -> true } }
        val id = state.shareId(accounts.active()!!, server)
        state.openShared(Shared(id, "a3t2", "Spirale", "Kreis Quartett"))
        assertEquals(true, state.nowPlaying)
        assertEquals(null, state.notice)
    }

    @Test
    fun otherServerFindsTheSongByName() = runBlocking {
        val server = FakeServer()
        val accounts = Accounts(context)
        accounts.save(Account("a1", ServerKind.Navidrome, "http://test", "u", "p"))
        val state = AppState(accounts, Playback(context)) { server }.apply { probe = { _, _ -> true } }
        state.openShared(Shared("fremder-server", "x9", "Spirale", "Kreis Quartett"))
        assertEquals(true, state.nowPlaying)
        state.nowPlaying = false
        state.openShared(Shared("fremder-server", "x9", "Bohemian Rhapsody", "Queen"))
        assertEquals(false, state.nowPlaying)
        assertEquals("„Bohemian Rhapsody“ von Queen ist nicht in deiner Mediathek.", state.notice)
    }
}
