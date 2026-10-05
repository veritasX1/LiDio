package io.github.veritasx1.lidio

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate

/** LiDio's icon: a coral-to-violet ground and a white pair of beamed notes – the same drawing as the launcher icon
 *  (res/drawable/ic_launcher_*.xml, 108-unit canvas). */
fun DrawScope.drawLiDioIcon() {
    val u = size.minDimension / 108f
    drawRect(Brush.verticalGradient(listOf(Color(0xFFFF4F6D), Color(0xFF7B3FE4))))
    fun at(x: Float, y: Float) = Offset(x * u, y * u)
    val white = Color.White
    for ((x, y) in listOf(39f to 71f, 64f to 65f)) rotate(-20f, at(x, y)) { drawOval(white, at(x - 9f, y - 6.5f), Size(18f * u, 13f * u)) }
    drawRect(white, at(44.5f, 37f), Size(4.4f * u, 34f * u))
    drawRect(white, at(69.5f, 31f), Size(4.4f * u, 34f * u))
    drawPath(Path().apply { moveTo(44.5f * u, 37f * u); lineTo(73.9f * u, 29f * u); lineTo(73.9f * u, 38f * u); lineTo(44.5f * u, 46f * u); close() }, white)
}
