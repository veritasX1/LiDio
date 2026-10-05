package io.github.veritasx1.lidio

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Card 2d0c9407: tags Android misreads, folder lists. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalTest {
    @Test
    fun brokenUmlautsAreRepaired() {
        assertEquals("Sinus & Söhne", repair("Sinus & SĂśhne"))          // UTF-8 read as ISO-8859-2 (seen on the moto g84)
        assertEquals("Weißes Rauschen", repair("WeiÃŸes Rauschen"))     // UTF-8 read as windows-1252
        assertEquals("Die Testtöne", repair("Die Testtöne"))            // correct text stays
        assertEquals("Café Ñandú", repair("Café Ñandú"))
        assertEquals(null, repair(null))
    }

    @Test
    fun foldersSurviveTheAccount() {
        val folders = listOf(android.net.Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic%2FLiDio-Test"))
        assertEquals(folders, LocalLibrary.decode(LocalLibrary.encode(folders)))
        assertEquals(emptyList<android.net.Uri>(), LocalLibrary.decode("kaputt"))
    }
}
