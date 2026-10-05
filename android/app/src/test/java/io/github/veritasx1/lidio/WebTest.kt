package io.github.veritasx1.lidio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Card 1843f577: links to public playlists, and the public variant offers no loading from the internet. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class WebTest {
    @Test fun playlistSites() {
        assertEquals(WebSource.Deezer, WebSource.of("https://www.deezer.com/de/playlist/123"))
        assertEquals(WebSource.Spotify, WebSource.of("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        assertEquals(WebSource.Other, WebSource.of("kein link"))
    }

    @Test fun links() {
        assertTrue(isLink("https://www.deezer.com/playlist/1")); assertTrue(isLink(" www.example.org/x"))
        assertFalse(isLink("yann tiersen"))
    }

    @Test fun publicVariantHasNoEngine() {
        // Runs for both variants; the public one must never offer loading from the internet.
        if (!Variant.PRIVATE) assertEquals(null, Variant.engine(androidx.test.core.app.ApplicationProvider.getApplicationContext()))
    }
}
