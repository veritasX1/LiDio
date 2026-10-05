package io.github.veritasx1.lidio

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Card 3: keys, the offline index and which copy a title plays from. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val track = Track("t1", "Moll", "Die Testtöne", "Zweite Welle", "al2", 14, 3, 1, 2023, "al2")

    @Before
    fun clean() { context.getSharedPreferences("offline", 0).edit().clear().commit() }

    @Test
    fun titleSurvivesTheIndex() {
        assertEquals(track, Index.decode(Index.encode(track)))
    }

    @Test
    fun keysSeparateServersAndQualities() {
        assertEquals("d:acc1:t1", Offline.downloadKey("acc1", track))
        assertEquals("s:acc1:t1:192", Offline.streamKey("acc1", track, 192))
        // The same title id on another server is another file.
        assertEquals(false, Offline.downloadKey("acc1", track) == Offline.downloadKey("acc2", track))
    }

    @Test
    fun aDownloadIsPreferredOverAHeardCopy() {
        assertNull(Offline.stored(context, "acc1", track))
        Index.add(context, "s:acc1:t1:0", Index.encode(track).put("uri", "http://heard").toString(), Index.HEARD)
        assertEquals("s:acc1:t1:0" to "http://heard", Offline.stored(context, "acc1", track))
        Index.add(context, "d:acc1:t1", Index.encode(track).put("uri", "http://download").toString(), Index.DOWNLOADED)
        assertEquals("d:acc1:t1", Offline.stored(context, "acc1", track)?.first)
        assertNull(Offline.stored(context, "acc2", track))
    }

    @Test
    fun sizes() {
        assertEquals("2 GB", Settings.size(2048)); assertEquals("512 MB", Settings.size(512))
        assertEquals("120 MB", mb(120L * 1048576)); assertEquals("1,5 GB", mb(1610612736L))
    }
}
