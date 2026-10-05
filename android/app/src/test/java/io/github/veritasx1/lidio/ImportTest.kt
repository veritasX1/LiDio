package io.github.veritasx1.lidio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Card 4: reading lists in every format and comparing tolerantly. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportTest {
    @Test
    fun m3uWithAndWithoutInfo() {
        val list = Lists.read("#EXTM3U\n#EXTINF:187,Die Testtöne - Quinte\nmusik/03 Quinte.mp3\n/sd/Kreis%20Quartett%20-%20Spirale.flac\n", "liste.m3u8")
        assertEquals(listOf(Wanted("Quinte", "Die Testtöne", seconds = 187), Wanted("Spirale", "Kreis Quartett")), list)
    }

    @Test
    fun plsXspfCsvText() {
        assertEquals(listOf(Wanted("Moll", "Die Testtöne", seconds = 14)),
            Lists.read("[playlist]\nFile1=x.mp3\nTitle1=Die Testtöne - Moll\nLength1=14\nNumberOfEntries=1\n"))
        assertEquals(listOf(Wanted("Bogen", "Kreis Quartett", "Rundgang", 15)), Lists.read("""<?xml version="1.0"?><playlist version="1" xmlns="http://xspf.org/ns/0/"><trackList>
            <track><title>Bogen</title><creator>Kreis Quartett</creator><album>Rundgang</album><duration>15000</duration></track></trackList></playlist>"""))
        // Exportify (Spotify): quoted cells with commas, several artists separated by ";".
        assertEquals(listOf(Wanted("Mix eins", "Anna Analog, Kreis Quartett", "Sampler, Nr. 1", 13)), Lists.read(
            "\"Track URI\",\"Track Name\",\"Artist Name(s)\",\"Album Name\",\"Duration (ms)\"\n\"spotify:track:1\",\"Mix eins\",\"Anna Analog;Kreis Quartett\",\"Sampler, Nr. 1\",\"13000\"\n", "x.csv"))
        assertEquals(listOf(Wanted("Quinte", "Die Testtöne"), Wanted("Spirale", "Kreis Quartett"), Wanted("Nur ein Titel")),
            Lists.read("1. Die Testtöne – Quinte\nKreis Quartett\tSpirale\n# Kommentar\nNur ein Titel\n"))
    }

    @Test
    fun tolerantComparison() {
        assertEquals(Matcher.normal("Kammerton A"), Matcher.normal("kammerton a (Remastered 2011)"))
        assertEquals(Matcher.normal("Weisses Rauschen"), Matcher.normal("Weißes Rauschen"))
        assertEquals(Matcher.normal("Morgen"), Matcher.normal("Morgen (feat. Anna Analog)"))
        assertEquals(Matcher.normal("Abendlied"), Matcher.normal("Abendlied - Live"))
        assertTrue(Matcher.similar("Schwebung", "Schwebnug") > 0.75)   // typo
    }

    @Test
    fun judgeFoundUnsureMissing() {
        val server = FakeServer()
        fun judge(w: Wanted) = Matcher.judge(w, server.tracks)
        assertEquals(Match.Found, judge(Wanted("Quinte", "Die Testtöne")).match)
        assertEquals(Match.Found, judge(Wanted("Spirale (Remastered 2011)", "Kreis Quartett feat. Jemand")).match)
        assertEquals(Match.Unsure, judge(Wanted("Spiralen", "Ganz Andere")).match)   // close title, other artist
        assertEquals(Match.Missing, judge(Wanted("Bohemian Rhapsody", "Queen")).match)
        // A wildly different length lowers the score.
        assertTrue(Matcher.score(Wanted("Quinte", "Die Testtöne", seconds = 400), server.tracks[2]) < Matcher.score(Wanted("Quinte", "Die Testtöne"), server.tracks[2]))
    }

    @Test
    fun runAgainstTheServerAndListTheMissing() {
        val results = Matcher.run(FakeServer(), listOf(Wanted("Quinte", "Die Testtöne"), Wanted("Gibt es nicht", "Niemand")))
        assertEquals(listOf(Match.Found, Match.Missing), results.map { it.match })
        assertEquals("Niemand – Gibt es nicht", Matcher.missingText(results))
    }

    @Test
    fun duplicatesHidden() {
        val a = Track("1", "Kammerton A", "Die Testtöne", "Sinus & Söhne", duration = 14)
        val b = a.copy(id = "2", album = "Sinus & Söhne (Doppelt)", duration = 15)
        val c = a.copy(id = "3", title = "Kammerton B")
        assertEquals(listOf("1", "3"), Duplicates.tracks(listOf(a, b, c)).map { it.id })
        // the same playlist entry twice (2Pac Top 30: California Love)
        assertEquals(listOf("1", "3"), Duplicates.tracks(listOf(a, c, a)).map { it.id })
        val albums = listOf(Album("x", "Sinus & Söhne", "Die Testtöne", trackCount = 1), Album("y", "Sinus & Söhne", "Die Testtöne", trackCount = 6),
            Album("z", "Rundgang", "Kreis Quartett", trackCount = 7))
        assertEquals(listOf("y", "z"), Duplicates.albums(albums).map { it.id })
    }
}
