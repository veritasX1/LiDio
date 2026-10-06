package io.github.veritasx1.lidio

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate

/** LiDio's icon (card 0fc6d317): a light face like LiCal's and the pair of beamed notes in a colour gradient – coral → violet,
 *  teal → indigo for the private variant. The same drawing as the launcher icon (tools/make_icons.py, 108-unit canvas). */
fun DrawScope.drawLiDioIcon() {
    val u = size.minDimension / 108f
    drawRect(Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFECECEF))))
    val (top, bottom) = if (Variant.PRIVATE) Color(0xFF1FC8B4) to Color(0xFF3A3FD9) else Color(0xFFFF4F6D) to Color(0xFF7B3FE4)
    // Notes scaled 1.12 about the middle, like the launcher icon.
    fun at(x: Float, y: Float) = Offset((54f + (x - 54f) * 1.12f) * u, (54f + (y - 53f) * 1.12f) * u)
    val k = 1.12f * u
    val note = Brush.linearGradient(listOf(top, bottom), at(44f, 28f), at(74f, 80f))
    for ((x, y) in listOf(39f to 71f, 64f to 65f)) rotate(-20f, at(x, y)) { drawOval(note, at(x - 9.4f, y - 6.8f), Size(18.8f * k, 13.6f * k)) }
    drawRect(note, at(44.2f, 37f), Size(5f * k, 34f * k))
    drawRect(note, at(69.2f, 31f), Size(5f * k, 34f * k))
    drawPath(Path().apply { val a = at(44.2f, 37f); val b = at(74.2f, 28.6f); val c = at(74.2f, 38.6f); val d = at(44.2f, 47f)
        moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close() }, note)
}
