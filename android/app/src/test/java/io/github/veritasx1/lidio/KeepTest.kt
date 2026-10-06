package io.github.veritasx1.lidio

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Olaf 05.10.2026: what was not heard for the chosen time leaves the phone; favourites stay; per user. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeepTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun track(id: String) = Track(id, "Titel $id", "Interpret", "Album")

    private class Favourites(val ids: List<String>?) : MusicServer by FakeServer() {
        override fun favorites(): List<Track> = ids?.map { Track(it, it, "", "") } ?: throw ServerError("weg")
    }

    private fun heardEntry(account: String, id: String, daysAgo: Int) {
        val key = "s:$account:$id:0"
        Index.add(context, key, Index.encode(track(id)).toString(), Index.HEARD)
        // backdate the entry (the index keeps its entries in memory)
        Index.entry(context, key)!!.put("at", System.currentTimeMillis() - daysAgo * 86_400_000L)
    }

    @Test
    fun oldGoesFavouriteAndRecentStayPerUser() {
        Keep.setDays(context, "bea", 30)
        heardEntry("bea", "alt", 60)
        heardEntry("bea", "lieblingstitel", 60)
        heardEntry("bea", "kuerzlich", 60)
        heardEntry("carl", "carls", 60)
        Keep.heard(context, "bea", "kuerzlich")      // heard today by Bea
        Keep.heard(context, "carl", "alt")            // the other user's listening keeps nothing for Bea
        val gone = Keep.tidy(context, "bea", Favourites(listOf("lieblingstitel")), force = true)
        assertEquals(1, gone)
        assertNull(Index.entry(context, "s:bea:alt:0"))
        assertNotNull(Index.entry(context, "s:bea:lieblingstitel:0"))
        assertNotNull(Index.entry(context, "s:bea:kuerzlich:0"))
        assertNotNull(Index.entry(context, "s:carl:carls:0"))   // another user's titles are not touched
    }

    @Test
    fun nothingWithoutFavouritesOrWhenAlways() {
        heardEntry("bert", "alt", 400)
        Keep.setDays(context, "bert", 30)
        assertEquals(0, Keep.tidy(context, "bert", Favourites(null), force = true))   // favourites unknown → keep everything
        Keep.setDays(context, "bert", 0)
        assertEquals(0, Keep.tidy(context, "bert", Favourites(emptyList()), force = true))   // "Immer"
        assertNotNull(Index.entry(context, "s:bert:alt:0"))
    }
}
