package io.github.veritasx1.lidio

import org.junit.Assert.assertEquals
import org.junit.Test

/** Home and away: which address LiDio uses. */
class ReachTest {
    private val emby = Account("1", ServerKind.Emby, "http://192.168.1.20:8096", "olaf", "t", external = "https://musik.example.de")

    @Test
    fun wlanFirstThenAway() {
        assertEquals("http://192.168.1.20:8096", Reach.best(emby) { _, a -> a.startsWith("http://192") })   // at home
        assertEquals("https://musik.example.de", Reach.best(emby) { _, a -> a.startsWith("https") })        // away
        assertEquals("http://192.168.1.20:8096", Reach.best(emby) { _, _ -> false })                     // nothing answers
        assertEquals("http://x", Reach.best(emby.copy(address = "http://x", external = "")) { _, _ -> false }) // no second address: never asked
    }

    @Test
    fun addressesCompareLikeTheStreams() {
        assertEquals("http://192.168.1.20:8096", Reach.normal(" 192.168.1.20:8096/ "))
        assertEquals("https://retrotech.goip.de", Reach.normal("https://retrotech.goip.de/"))
    }
}
