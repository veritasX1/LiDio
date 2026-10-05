package io.github.veritasx1.lidio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Card 7ac89c11: .lrc files read and written, the current line while playing. */
class LyricsTest {
    private val lrc = """
        [ar:Daft Punk]
        [ti:Instant Crush]
        [00:26.31] I didn't want to be the one to forget
        [00:30.70] I thought of everything I'd never regret
        [00:34.9]A little time with you
        [01:02.05][02:10.00] Chorus
    """.trimIndent()

    @Test fun parsesTimesAndRepeats() {
        val lines = Lyrics.parse(lrc)
        assertEquals(5, lines.size)                      // tags left out, the chorus twice
        assertEquals(26_310L, lines[0].ms); assertEquals("I didn't want to be the one to forget", lines[0].text)
        assertEquals(34_900L, lines[2].ms)
        assertEquals(listOf(62_050L, 130_000L), lines.filter { it.text == "Chorus" }.map { it.ms })
    }

    @Test fun currentLine() {
        val lines = Lyrics.parse(lrc)
        assertEquals(-1, Lyrics.current(lines, 10_000))
        assertEquals(0, Lyrics.current(lines, 27_000))
        assertEquals(1, Lyrics.current(lines, 30_500))   // a quarter second early, like Apple
        assertEquals(4, Lyrics.current(lines, 200_000))
    }

    @Test fun plainTextAndBackToLrc() {
        val plain = Lyrics.parse("Erste Zeile\n\nZweite Zeile")
        assertEquals(2, plain.size); assertNull(plain[0].ms)
        val again = Lyrics.parse(Lyrics.toLrc(Lyrics.parse(lrc)))
        assertEquals(Lyrics.parse(lrc).map { it.ms }, again.map { it.ms })
    }

    @Test fun srtFromEmby() {
        val srt = "\uFEFF1\n00:00:00,030 --> 00:00:07,310\nOoh-oh, oh-oh\n\n2\n00:00:07,310 --> 00:00:14,570\nNow listen to me baby\n\n3\n01:02:03,5 --> 01:02:04,000\nLang\n"
        val lines = Lyrics.fromSrt(srt)
        assertEquals(listOf(30L, 7_310L, 3_723_500L), lines.map { it.ms })
        assertEquals("Now listen to me baby", lines[1].text)
    }
}
