package io.github.veritasx1.lidio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp

/** A Mixtape without an own cover (Olaf 06.10.2026, design A "Klassisch schwarz"): a drawn compact cassette, its name written by
 *  hand on the label. Proportions measured on Olaf's photo of a real tape (100 × 64 mm): the reels at half height, 42 mm
 *  apart; the guide rollers near the lower corners, the drive holes at a third and two thirds of the head opening. Light mode: black cassette on a warm paper ground; dark mode: a dark
 *  ground, the cassette lifted by a soft rim and a slightly dimmed label. Covers address it as "mixtape:<name>". */
@Composable
fun MixtapeArt(name: String, modifier: Modifier = Modifier) {
    val dark = Ink.dark
    val measurer = rememberTextMeasurer()
    val hand = remember { FontFamily(Font(R.font.caveat, FontWeight.SemiBold)) }
    Canvas(modifier.fillMaxSize()) {
        val u = size.minDimension / 400f
        fun r(x: Float, y: Float, w: Float, h: Float, rad: Float, c: Color, stroke: Float = 0f) =
            if (stroke > 0f) drawRoundRect(c, Offset(x * u, y * u), Size(w * u, h * u), CornerRadius(rad * u), style = Stroke(stroke * u))
            else drawRoundRect(c, Offset(x * u, y * u), Size(w * u, h * u), CornerRadius(rad * u))
        val ground = if (dark) listOf(Color(0xFF2A2724), Color(0xFF161514)) else listOf(Color(0xFFECE7DC), Color(0xFFD9D2C3))
        drawRect(Brush.linearGradient(ground, Offset.Zero, Offset(size.width, size.height)))
        val body = if (dark) Color(0xFF232325) else Color(0xFF1C1C1E)
        val label = if (dark) Color(0xFFDCD3BF) else Color(0xFFF3ECDC)
        val stripe = Color(0xFFE8501F)
        val ink = Color(0xFF1D2A6B)
        // The cassette: 320 × 205 units (≈ 100 × 64 mm), tilted a little like a tape lying on a table.
        translate(0f, 52f * u) { rotate(-4f, Offset(200f * u, 150f * u)) {
            // shadow, body (dark mode: a faint light rim so it stands off the dark ground)
            r(44f, 48f, 320f, 205f, 14f, Color.Black.copy(alpha = if (dark) 0.5f else 0.22f))
            r(40f, 42f, 320f, 205f, 14f, body)
            if (dark) r(40f, 42f, 320f, 205f, 14f, Color.White.copy(alpha = 0.12f), stroke = 1.5f)
            // screws: four corners and one in the middle below the window (as on Olaf's photo of a real tape)
            for ((x, y) in listOf(52f to 54f, 348f to 54f, 52f to 235f, 348f to 235f, 200f to 204f)) drawCircle(Color(0xFF55555A), 3.2f * u, Offset(x * u, y * u))
            // label around the window: the name on top, the orange stripe, lines below the window
            r(56f, 54f, 288f, 140f, 6f, label)
            drawRect(stripe, Offset(56f * u, 100f * u), Size(288f * u, 6f * u))
            for (i in 0..2) drawRect(Color(0xFF2A2A2A).copy(alpha = 0.55f), Offset(66f * u, (171f + i * 8f) * u), Size(268f * u, 1.5f * u))
            // the name, handwritten
            val style = TextStyle(fontFamily = hand, fontWeight = FontWeight.SemiBold, color = ink, textAlign = TextAlign.Center,
                fontSize = ((if (name.length > 16) 30f else 38f) * u / (density * fontScale)).sp)
            val text = measurer.measure(name, style, maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = (264f * u).toInt()))
            rotate(-2f, Offset(200f * u, 80f * u)) { drawText(text, topLeft = Offset(200f * u - text.size.width / 2f, 96f * u - text.size.height)) }
            // window with the two reels at half height – centres 136 units apart (42 mm of 100)
            r(98f, 117f, 204f, 46f, 23f, Color(0xFF141416)); r(98f, 117f, 204f, 46f, 23f, Color.Black, stroke = 1.5f)
            r(170f, 128f, 60f, 24f, 3f, Color(0xFF2C2C30))
            for (x in listOf(132f, 268f)) {
                drawCircle(Color(0xFFD6C8A8), 16f * u, Offset(x * u, 140f * u)); drawCircle(Color(0xFF8A7A58), 16f * u, Offset(x * u, 140f * u), style = Stroke(1.5f * u))
                drawCircle(Color(0xFF3A3326), 6.5f * u, Offset(x * u, 140f * u))
                for (k in 0 until 6) { val a = Math.toRadians(k * 60.0); drawCircle(Color(0xFF3A3326), 1.5f * u, Offset((x + 10f * Math.cos(a).toFloat()) * u, (140f + 10f * Math.sin(a).toFloat()) * u)) }
            }
            // head opening: a flat trapezoid over the lower middle, two drive holes at a third and two thirds, the pad between
            val head = Path().apply { moveTo(104f * u, 247f * u); lineTo(118f * u, 212f * u); lineTo(282f * u, 212f * u); lineTo(296f * u, 247f * u); close() }
            drawPath(head, Color.White.copy(alpha = 0.06f)); drawPath(head, Color.White.copy(alpha = 0.14f), style = Stroke(1.2f * u))
            for (x in listOf(150f, 250f)) drawCircle(Color.Black.copy(alpha = 0.85f), 6.5f * u, Offset(x * u, 232f * u))
            for (x in listOf(126f, 274f)) drawCircle(Color.Black.copy(alpha = 0.85f), 3.5f * u, Offset(x * u, 236f * u))
            r(187f, 224f, 26f, 14f, 2f, Color.Black.copy(alpha = 0.6f))
            // the tape guide rollers near the lower corners
            for (x in listOf(74f, 326f)) {
                drawCircle(Color.Black.copy(alpha = 0.8f), 10f * u, Offset(x * u, 226f * u))
                drawCircle(Color(0xFF9A9AA0), 6f * u, Offset(x * u, 226f * u)); drawCircle(Color(0xFF3A3A3E), 2.5f * u, Offset(x * u, 226f * u))
            }
        } }
    }
}
