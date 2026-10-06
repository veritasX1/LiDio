package io.github.veritasx1.lidio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** LiDio's own symbols in the spirit of SF Symbols (which may not be used outside Apple's platforms): 24-unit grid,
 *  round caps, optionally filled like the "…fill" variants. */
enum class Symbol {
    Play, Pause, Forward, Backward, Shuffle, Repeat, RepeatOne, Queue, Search, Note, Playlists, Artists, Albums,
    Home, Sparkle, Library, Downloaded, Ellipsis, ChevronLeft, ChevronRight, ChevronDown, Gear, SpeakerLow, SpeakerHigh, Plus,
    Server, Close, Check, Import, Duplicate, Missing, Share, PlayNext, PlayLast, Bolt, Trash, Globe, Video, Quote, Infinity, Grip, Star, Pin, People, Sparkles, Phone, Cloud, CloudOff, ServerDown, PhoneUpload, Cassette, CassetteAdd,
}

@Composable
fun SymbolIcon(symbol: Symbol, color: Color, size: Dp = 22.dp, weight: Float = 1.8f, filled: Boolean = false, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawSymbol(symbol, color, weight, filled) }
}

fun DrawScope.drawSymbol(symbol: Symbol, color: Color, weight: Float = 1.8f, filled: Boolean = false) {
    val u = size.minDimension / 24f
    val stroke = Stroke(width = weight * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    fun shape(fill: Boolean = false, block: Path.() -> Unit) = drawPath(Path().apply(block), color, style = if (fill) Fill else stroke)
    fun Path.m(x: Float, y: Float) = moveTo(x * u, y * u)
    fun Path.l(x: Float, y: Float) = lineTo(x * u, y * u)
    fun Path.q(cx: Float, cy: Float, x: Float, y: Float) = quadraticTo(cx * u, cy * u, x * u, y * u)
    fun box(x: Float, y: Float, w: Float, h: Float, r: Float, fill: Boolean = false) =
        drawRoundRect(color, p(x, y), Size(w * u, h * u), CornerRadius(r * u), style = if (fill) Fill else stroke)
    fun triangle(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float) = drawPath(Path().apply {
        m(x0, y0); l(x1, y1); l(x2, y2); close()
    }, color, style = Fill).also { drawPath(Path().apply { m(x0, y0); l(x1, y1); l(x2, y2); close() }, color, style = Stroke(1.6f * u, join = StrokeJoin.Round)) }
    fun note(x: Float, y: Float, s: Float) {
        drawOval(color, p(x - 3.2f * s, y - 2.3f * s), Size(6f * s * u, 4.6f * s * u))
        shape { m(x + 2.6f * s, y); l(x + 2.6f * s, y - 13f * s); l(x + 7f * s, y - 14.5f * s) }
    }

    when (symbol) {
        Symbol.Play -> triangle(6.5f, 3.8f, 20.5f, 12f, 6.5f, 20.2f)
        Symbol.Pause -> { box(5.5f, 4f, 4.6f, 16f, 1.2f, fill = true); box(13.9f, 4f, 4.6f, 16f, 1.2f, fill = true) }
        Symbol.Forward -> { triangle(2.5f, 6f, 12f, 12f, 2.5f, 18f); triangle(12f, 6f, 21.5f, 12f, 12f, 18f) }
        Symbol.Backward -> { triangle(21.5f, 6f, 12f, 12f, 21.5f, 18f); triangle(12f, 6f, 2.5f, 12f, 12f, 18f) }
        Symbol.Shuffle -> {
            shape { m(3f, 7f); l(7f, 7f); q(10.5f, 7f, 12f, 12f); q(13.5f, 17f, 17f, 17f); l(20.5f, 17f) }
            shape { m(3f, 17f); l(7f, 17f); q(10.5f, 17f, 12f, 12f); q(13.5f, 7f, 17f, 7f); l(20.5f, 7f) }
            shape { m(18f, 4.5f); l(20.8f, 7f); l(18f, 9.5f); m(18f, 14.5f); l(20.8f, 17f); l(18f, 19.5f) }
        }
        Symbol.Repeat, Symbol.RepeatOne -> {
            shape { m(4f, 11f); l(4f, 9f); q(4f, 7f, 6f, 7f); l(19.5f, 7f); m(17f, 4.5f); l(19.8f, 7f); l(17f, 9.5f) }
            shape { m(20f, 13f); l(20f, 15f); q(20f, 17f, 18f, 17f); l(4.5f, 17f); m(7f, 14.5f); l(4.2f, 17f); l(7f, 19.5f) }
            if (symbol == Symbol.RepeatOne) shape { m(11f, 10.6f); l(12.4f, 9.6f); l(12.4f, 14.4f) }
        }
        Symbol.Queue -> {
            for (y in listOf(6f, 12f, 18f)) { drawCircle(color, 1.4f * u, p(4.5f, y)); shape { m(9f, y); l(20.5f, y) } }
        }
        Symbol.Search -> { drawCircle(color, 6.5f * u, p(10.5f, 10.5f), style = stroke); shape { m(15.3f, 15.3f); l(20.5f, 20.5f) } }
        Symbol.Note -> note(9f, 18f, 1f)
        Symbol.Playlists -> {
            for (y in listOf(5f, 10f, 15f)) shape { m(3f, y); l(if (y > 12f) 9.5f else 13f, y) }
            note(15.5f, 19f, 0.85f)
        }
        Symbol.Artists -> {
            // A microphone: head, handle.
            drawCircle(color, 4.3f * u, p(15f, 8.5f), style = if (filled) Fill else stroke)
            shape { m(12f, 11.8f); l(4.5f, 19.6f); m(6.4f, 17.6f); l(8.2f, 19.4f) }
        }
        Symbol.Albums -> {
            box(3.5f, 7f, 15f, 14f, 2.5f, fill = filled)
            shape { m(6.5f, 4.5f); l(18.5f, 4.5f); q(21f, 4.5f, 21f, 7f); l(21f, 17.5f) }
        }
        Symbol.Home -> {
            shape(filled) { m(3.5f, 11f); l(12f, 3.8f); l(20.5f, 11f); l(20.5f, 19f); q(20.5f, 20.5f, 19f, 20.5f); l(15f, 20.5f); l(15f, 14.5f); l(9f, 14.5f); l(9f, 20.5f); l(5f, 20.5f); q(3.5f, 20.5f, 3.5f, 19f); close() }
        }
        Symbol.Sparkle -> {
            // "Neu": a four-pointed sparkle with a small one beside it.
            shape(filled) { m(11f, 3f); q(11.8f, 10.2f, 19f, 11f); q(11.8f, 11.8f, 11f, 19f); q(10.2f, 11.8f, 3f, 11f); q(10.2f, 10.2f, 11f, 3f); close() }
            shape(true) { m(18.5f, 15.5f); q(18.8f, 18.2f, 21.5f, 18.5f); q(18.8f, 18.8f, 18.5f, 21.5f); q(18.2f, 18.8f, 15.5f, 18.5f); q(18.2f, 18.2f, 18.5f, 15.5f); close() }
        }
        Symbol.Library -> {
            // music library: three spines and a leaning one.
            for (x in listOf(4.5f, 9f)) box(x, 4f, 3f, 16.5f, 1f, fill = filled)
            shape(filled) { m(13.6f, 5.4f); l(16.4f, 4.6f); l(20.6f, 19.6f); l(17.8f, 20.4f); close() }
        }
        Symbol.Downloaded -> {
            drawCircle(color, 9.2f * u, p(12f, 12f), style = stroke)
            shape { m(12f, 7f); l(12f, 16.2f); m(8.4f, 12.8f); l(12f, 16.4f); l(15.6f, 12.8f) }
        }
        Symbol.Ellipsis -> for (x in listOf(5f, 12f, 19f)) drawCircle(color, 1.9f * u, p(x, 12f))
        Symbol.ChevronLeft -> shape { m(15f, 4.5f); l(7.5f, 12f); l(15f, 19.5f) }
        Symbol.ChevronRight -> shape { m(9f, 4.5f); l(16.5f, 12f); l(9f, 19.5f) }
        Symbol.ChevronDown -> shape { m(4.5f, 9f); l(12f, 16.5f); l(19.5f, 9f) }
        Symbol.Gear -> {
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                drawLine(color, p(12f + 6.4f * Math.cos(a).toFloat(), 12f + 6.4f * Math.sin(a).toFloat()),
                    p(12f + 9.4f * Math.cos(a).toFloat(), 12f + 9.4f * Math.sin(a).toFloat()), weight * 1.7f * u, StrokeCap.Round)
            }
            drawCircle(color, 6.6f * u, p(12f, 12f), style = stroke); drawCircle(color, 2.6f * u, p(12f, 12f), style = stroke)
        }
        Symbol.SpeakerLow, Symbol.SpeakerHigh -> {
            shape(true) { m(3f, 9.2f); l(7f, 9.2f); l(12f, 4.8f); l(12f, 19.2f); l(7f, 14.8f); l(3f, 14.8f); close() }
            shape { m(15f, 9.4f); q(16.6f, 12f, 15f, 14.6f) }
            if (symbol == Symbol.SpeakerHigh) { shape { m(17.6f, 7f); q(21f, 12f, 17.6f, 17f) } }
        }
        Symbol.Plus -> shape { m(12f, 4.5f); l(12f, 19.5f); m(4.5f, 12f); l(19.5f, 12f) }
        Symbol.Server -> {
            box(3.5f, 4.5f, 17f, 6.5f, 2f); box(3.5f, 13f, 17f, 6.5f, 2f)
            drawCircle(color, 1.1f * u, p(16.8f, 7.75f)); drawCircle(color, 1.1f * u, p(16.8f, 16.25f))
        }
        Symbol.Close -> shape { m(6f, 6f); l(18f, 18f); m(18f, 6f); l(6f, 18f) }
        Symbol.Check -> shape { m(5f, 12.5f); l(10f, 17.5f); l(19f, 6.5f) }
        Symbol.Import -> {
            shape { m(12f, 3.5f); l(12f, 14f); m(8f, 10.5f); l(12f, 14.5f); l(16f, 10.5f) }
            shape { m(4f, 14f); l(4f, 18.5f); q(4f, 20.5f, 6f, 20.5f); l(18f, 20.5f); q(20f, 20.5f, 20f, 18.5f); l(20f, 14f) }
        }
        Symbol.Duplicate -> { box(3.5f, 7.5f, 12f, 13f, 2f); shape { m(8.5f, 4.5f); l(18f, 4.5f); q(20.5f, 4.5f, 20.5f, 7f); l(20.5f, 16f) } }
        Symbol.Share -> {
            shape { m(12f, 14.5f); l(12f, 3.5f); m(8f, 7.5f); l(12f, 3.5f); l(16f, 7.5f) }
            shape { m(8.5f, 10f); l(6f, 10f); q(4f, 10f, 4f, 12f); l(4f, 18.5f); q(4f, 20.5f, 6f, 20.5f); l(18f, 20.5f); q(20f, 20.5f, 20f, 18.5f); l(20f, 12f); q(20f, 10f, 18f, 10f); l(15.5f, 10f) }
        }
        Symbol.PlayNext -> { triangle(3.5f, 4f, 10f, 8f, 3.5f, 12f); shape { m(13f, 8f); l(20.5f, 8f); m(4f, 16f); l(20.5f, 16f); m(4f, 20.5f); l(20.5f, 20.5f) } }
        Symbol.PlayLast -> { shape { m(4f, 4f); l(20.5f, 4f); m(4f, 8.5f); l(20.5f, 8.5f) }; triangle(3.5f, 12f, 10f, 16f, 3.5f, 20f); shape { m(13f, 16f); l(20.5f, 16f) } }
        Symbol.Globe -> {
            drawCircle(color, 8.5f * u, p(12f, 12f), style = stroke)
            drawOval(color, p(8.2f, 3.5f), Size(7.6f * u, 17f * u), style = stroke)
            shape { m(3.5f, 12f); l(20.5f, 12f); m(5f, 7.5f); l(19f, 7.5f); m(5f, 16.5f); l(19f, 16.5f) }
        }
        // Like SF Symbols' "play.rectangle": a screen with a play triangle.
        // Like SF Symbols' "quote.bubble": a speech bubble with a quotation mark – Apple's lyrics button.
        Symbol.Quote -> {
            shape { m(6f, 4f); l(18f, 4f); q(21f, 4f, 21f, 7f); l(21f, 14f); q(21f, 17f, 18f, 17f); l(10f, 17f); l(5.5f, 20.5f); l(6.5f, 17f)
                q(3f, 17f, 3f, 14f); l(3f, 7f); q(3f, 4f, 6f, 4f); close() }
            shape(true) { m(8f, 12.5f); q(8f, 9f, 11f, 8f); l(11f, 9.2f); q(9.6f, 9.8f, 9.6f, 10.6f); l(10.8f, 10.6f); l(10.8f, 12.8f); l(8f, 12.8f); close()
                m(12.6f, 12.5f); q(12.6f, 9f, 15.6f, 8f); l(15.6f, 9.2f); q(14.2f, 9.8f, 14.2f, 10.6f); l(15.4f, 10.6f); l(15.4f, 12.8f); l(12.6f, 12.8f); close() }
        }
        // SF Symbols "infinity": Autoplay.
        Symbol.Infinity -> shape { m(12f, 12f); q(9f, 7.5f, 6.5f, 7.5f); q(3f, 7.5f, 3f, 12f); q(3f, 16.5f, 6.5f, 16.5f); q(9f, 16.5f, 12f, 12f)
            q(15f, 7.5f, 17.5f, 7.5f); q(21f, 7.5f, 21f, 12f); q(21f, 16.5f, 17.5f, 16.5f); q(15f, 16.5f, 12f, 12f) }
        // "line.3.horizontal": the handle to drag a title in the queue.
        Symbol.Grip -> shape { m(4f, 8f); l(20f, 8f); m(4f, 12f); l(20f, 12f); m(4f, 16f); l(20f, 16f) }
        // "star" / "star.fill": favourite.
        Symbol.Star -> shape(filled) { m(12f, 3f); l(14.6f, 8.9f); l(21f, 9.5f); l(16.1f, 13.7f); l(17.6f, 20f); l(12f, 16.6f); l(6.4f, 20f)
            l(7.9f, 13.7f); l(3f, 9.5f); l(9.4f, 8.9f); close() }
        // "pin": a pushpin.
        Symbol.Pin -> { shape(filled) { m(9f, 3.5f); l(15f, 3.5f); l(14f, 9.5f); l(17.5f, 13f); l(6.5f, 13f); l(10f, 9.5f); close() }; shape { m(12f, 13f); l(12f, 20.5f) } }
        // "person.2": sharing.
        Symbol.People -> { drawCircle(color, 3.2f * u, p(9f, 8f), style = stroke); shape { m(3f, 19.5f); q(3f, 13.5f, 9f, 13.5f); q(15f, 13.5f, 15f, 19.5f) }
            drawCircle(color, 2.6f * u, p(16.5f, 8.5f), style = stroke); shape { m(16.5f, 13.6f); q(21f, 13.8f, 21f, 19f) } }
        // "sparkles": the visualisation.
        Symbol.Sparkles -> { shape(true) { m(10f, 3f); q(11f, 9f, 17f, 10f); q(11f, 11f, 10f, 17f); q(9f, 11f, 3f, 10f); q(9f, 9f, 10f, 3f); close() }
            shape(true) { m(18f, 14f); q(18.5f, 17f, 21f, 17.5f); q(18.5f, 18f, 18f, 21f); q(17.5f, 18f, 15f, 17.5f); q(17.5f, 17f, 18f, 14f); close() } }
        // "iphone": a phone with its speaker slot.
        Symbol.Phone -> { box(6.5f, 2.5f, 11f, 19f, 2.6f); shape { m(10.5f, 5f); l(13.5f, 5f) } }
        // Card e1f44cfb (Olaf: "ein Handy mit Pfeil nach oben"): from this phone up to the server.
        // Card c9b15c67: the Mixtape – a compact cassette (body, two reels, the window at the bottom).
        Symbol.Cassette -> { box(2.5f, 5.5f, 19f, 13f, 2f); drawCircle(color, 1.9f * u, p(8.5f, 11f), style = stroke); drawCircle(color, 1.9f * u, p(15.5f, 11f), style = stroke)
            shape { m(10.4f, 11f); l(13.6f, 11f); m(7f, 18.5f); l(8.5f, 15.5f); l(15.5f, 15.5f); l(17f, 18.5f) } }
        // Olaf 06.10.2026: putting a title onto a Mixtape – the cassette with an arrow into it.
        Symbol.CassetteAdd -> { box(2f, 8.5f, 20f, 13f, 2f); shape { m(4.5f, 11.3f); l(19.5f, 11.3f) }
            box(7f, 12.8f, 10f, 3.8f, 1.9f)
            drawCircle(color, 1.1f * u, p(9.3f, 14.7f), style = stroke); drawCircle(color, 1.1f * u, p(14.7f, 14.7f), style = stroke)
            shape { m(6.5f, 21.5f); l(7.8f, 18.8f); l(16.2f, 18.8f); l(17.5f, 21.5f) }
            shape { m(12f, 1.2f); l(12f, 6.8f); m(9.5f, 4.4f); l(12f, 6.9f); l(14.5f, 4.4f) } }
        Symbol.PhoneUpload -> { box(6.5f, 2.5f, 11f, 19f, 2.6f); shape { m(12f, 17f); l(12f, 8f); m(9f, 11f); l(12f, 8f); l(15f, 11f) } }
        // "cloud": only out there in the internet (Olaf 05.10.2026).
        Symbol.Cloud -> shape { m(7f, 18.5f); q(3f, 18.5f, 3f, 14.8f); q(3f, 11.2f, 7f, 11f); q(7.6f, 6f, 12.4f, 6f); q(16.6f, 6f, 17.6f, 10.2f)
            q(21f, 10.6f, 21f, 14.4f); q(21f, 18.5f, 17f, 18.5f); close() }
        // "icloud.slash": nowhere to be had – neither here nor loadable (Olaf 05.10.2026).
        Symbol.CloudOff -> { shape { m(7f, 18.5f); q(3f, 18.5f, 3f, 14.8f); q(3f, 11.2f, 7f, 11f); q(7.6f, 6f, 12.4f, 6f); q(16.6f, 6f, 17.6f, 10.2f)
            q(21f, 10.6f, 21f, 14.4f); q(21f, 18.5f, 17f, 18.5f); close() }; shape { m(4f, 4f); l(20f, 21f) } }
        // Onto the own server: the server with an arrow coming down (Olaf 05.10.2026: "Downloadsymbol mit Server").
        Symbol.ServerDown -> { box(3.5f, 12f, 17f, 4.2f, 1.4f); box(3.5f, 17.3f, 17f, 4.2f, 1.4f)
            drawCircle(color, 0.9f * u, p(17f, 14.1f)); drawCircle(color, 0.9f * u, p(17f, 19.4f))
            shape { m(12f, 2f); l(12f, 9.5f); m(8.8f, 6.6f); l(12f, 9.8f); l(15.2f, 6.6f) } }
        Symbol.Video -> { box(2.5f, 5f, 19f, 14f, 3.5f); triangle(10f, 9f, 15.5f, 12f, 10f, 15f) }
        Symbol.Trash -> shape { m(4f, 6.5f); l(20f, 6.5f); m(9.5f, 6.5f); l(9.5f, 4.5f); q(9.5f, 3.5f, 10.5f, 3.5f); l(13.5f, 3.5f)
            q(14.5f, 3.5f, 14.5f, 4.5f); l(14.5f, 6.5f); m(6f, 6.5f); l(7f, 19f); q(7.2f, 20.5f, 8.7f, 20.5f); l(15.3f, 20.5f)
            q(16.8f, 20.5f, 17f, 19f); l(18f, 6.5f); m(10f, 10f); l(10.3f, 17f); m(14f, 10f); l(13.7f, 17f) }
        // Like SF Symbols' "bolt" – and a nod to Winamp's lightning bolt.
        Symbol.Bolt -> shape(filled) { m(13.6f, 2.5f); l(4.8f, 13.6f); l(11.2f, 13.6f); l(10.2f, 21.5f); l(19.2f, 10.2f); l(12.8f, 10.2f); close() }
        Symbol.Missing -> {
            drawCircle(color, 9.2f * u, p(12f, 12f), style = stroke)
            shape { m(12f, 7f); l(12f, 13f) }; drawCircle(color, 1.2f * u, p(12f, 16.6f))
        }
    }
}
