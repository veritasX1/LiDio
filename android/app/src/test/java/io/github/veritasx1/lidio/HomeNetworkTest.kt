package io.github.veritasx1.lidio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 05.10.2026: an address like "192.168.1.20:8096" became https – Emby at home answers only http, so everything went the long way round. */
class HomeNetworkTest {
    @Test fun homeAddressesSpeakHttp() {
        assertTrue(homeNetwork("192.168.1.20:8096"))
        assertTrue(homeNetwork("10.0.0.5:4533"))
        assertTrue(homeNetwork("172.20.1.2"))
        assertTrue(homeNetwork("musikpi.local:8096"))
        assertTrue(homeNetwork("raspberrypi:8096"))
        assertFalse(homeNetwork("music.example.org"))
        assertFalse(homeNetwork("172.40.1.2"))
    }
}
