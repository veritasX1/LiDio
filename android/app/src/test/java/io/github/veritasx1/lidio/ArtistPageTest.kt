package io.github.veritasx1.lidio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Olaf 05.10.2026: a tap on the artist opens their page like in Apple Music – albums apart from singles & EPs, the text about them readable. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArtistPageTest {
    @Test fun singlesAndEpsStandApartFromAlbums() {
        val server = FakeServer()
        val page = server.artistPage("r3")
        assertEquals(listOf("a4"), page.singles.map { it.id })     // "Rauschen" has three titles: an EP
        assertEquals(emptyList<String>(), page.albums.map { it.id })
        assertEquals(listOf("a1", "a2"), server.artistPage("r1").albums.map { it.id })
    }

    @Test fun anArtistIsFoundByName() {
        assertEquals("r2", FakeServer().findArtist("Kreis Quartett"))
    }

    @Test fun serverTextBecomesPlainText() {
        assertEquals("Eine Band aus Köln.\nSeit 1999 & mehr.",
            plainText("Eine Band aus Köln.<br/>Seit 1999 &amp; mehr. <a href=\"https://www.last.fm/music/x\">Read more on Last.fm</a>"))
        assertNull(plainText("  <p></p> "))
        assertNull(plainText(null))
    }
}
