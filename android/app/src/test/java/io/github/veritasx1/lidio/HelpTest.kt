package io.github.veritasx1.lidio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Card 5ef5e3c8: the guide names exactly the connections this variant makes. */
class HelpTest {
    private val privacy = Guide.privacy().joinToString(" ")

    @Test fun privacyMatchesTheVariant() {
        assertTrue(privacy.contains("eigenen Server"))
        assertTrue(privacy.contains("skins.webamp.org"))
        assertTrue(privacy.contains("lrclib.net"))
        if (Variant.PRIVATE) {
            assertTrue(privacy.contains(Variant.MUSIC)); assertTrue(privacy.contains(Variant.text("datenschutz")))
        } else {
            assertTrue(privacy.contains("nur wenn du „Liedtexte aus dem Netz“ einschaltest"))
        }
    }

    @Test fun chapters() {
        val titles = Guide.chapters().map { it.title }
        assertEquals("Datenschutz", titles.last())
        assertEquals(Variant.PRIVATE, titles.any { it.startsWith("Aus dem Netz") })
        assertTrue(Guide.chapters().all { it.paragraphs.isNotEmpty() })
    }
}
