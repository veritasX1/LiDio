package io.github.veritasx1.lidio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Apple's system colours (iOS), light and dark – LiDio's tint is systemPink, the colour of music on the iPhone. */
data class Palette(
    val dark: Boolean,
    val background: Color, val grouped: Color, val card: Color, val elevated: Color,
    val label: Color, val secondary: Color, val tertiary: Color, val separator: Color, val fill: Color,
    val tint: Color, val bar: Color,
)

val LightPalette = Palette(false, Color.White, Color(0xFFF2F2F7), Color.White, Color(0xFFF2F2F7),
    Color.Black, Color(0x993C3C43), Color(0x4D3C3C43), Color(0x4A3C3C43), Color(0x33787880),
    Color(0xFFFF2D55), Color(0xF2F9F9F9))
val DarkPalette = Palette(true, Color.Black, Color.Black, Color(0xFF1C1C1E), Color(0xFF2C2C2E),
    Color.White, Color(0x99EBEBF5), Color(0x4DEBEBF5), Color(0x99545458), Color(0x5C787880),
    Color(0xFFFF375F), Color(0xF2161616))

val LocalPalette = staticCompositionLocalOf { LightPalette }
val Ink: Palette @Composable get() = LocalPalette.current

@Composable
fun LiDioTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPalette provides if (dark) DarkPalette else LightPalette, content = content)
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Inter = FontFamily(listOf(400, 500, 600, 700, 800).map { weight ->
    Font(R.font.inter, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
})

/** The HIG text styles: Large Title 34, Title 2 22, Headline 17 semibold, Body 17, Subheadline 15, Footnote 13, Caption 12. */
fun style(size: Float, weight: Int = 400, color: Color = Color.Unspecified, tabular: Boolean = false) = TextStyle(
    fontFamily = Inter, fontSize = size.sp, fontWeight = FontWeight(weight), color = color,
    fontFeatureSettings = if (tabular) "tnum" else null, letterSpacing = if (size >= 28) (-0.6).sp else if (size >= 20) (-0.3).sp else (-0.1).sp)

@Composable
fun Label(text: String, size: Float = 17f, weight: Int = 400, color: Color = Ink.label, modifier: Modifier = Modifier,
          lines: Int = 1, tabular: Boolean = false, align: androidx.compose.ui.text.style.TextAlign? = null) {
    BasicText(text, modifier, style = style(size, weight, color, tabular).let { if (align != null) it.copy(textAlign = align) else it },
        maxLines = lines, overflow = TextOverflow.Ellipsis)
}

/** A row in an iOS list: optional leading view, title (and subtitle), trailing view; hairline below from the text on. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ListRow(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null, leading: (@Composable () -> Unit)? = null,
            trailing: (@Composable () -> Unit)? = null, titleColor: Color = Ink.label, separator: Boolean = true, height: Dp = 44.dp,
            inset: Dp = 16.dp, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null) {
    val ink = Ink
    Row(modifier.fillMaxWidth().then(if (onClick != null) Modifier.combinedClickable(role = Role.Button, onClick = onClick,
            onLongClick = onLongClick, onLongClickLabel = if (onLongClick != null) "Mehr" else null) else Modifier)
        .padding(start = inset), verticalAlignment = Alignment.CenterVertically) {
        leading?.let { it(); Box(Modifier.width(12.dp)) }
        Box(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth().padding(end = 16.dp).padding(vertical = 6.dp).height(height - 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label(title, color = titleColor)
                    subtitle?.let { Label(it, 13f, color = ink.secondary) }
                }
                trailing?.invoke()
            }
            if (separator) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(0.5.dp).background(ink.separator))
        }
    }
}

@Composable
fun Chevron() = SymbolIcon(Symbol.ChevronRight, Ink.tertiary, 14.dp, weight = 2.2f)

/** iOS slider: thin track, filled part in `fill`, round thumb (or no thumb, like the Now Playing scrubber). */
@Composable
fun IosSlider(value: Float, onChange: (Float) -> Unit, description: String, modifier: Modifier = Modifier, fill: Color = Ink.label,
              track: Color = Ink.fill, thumb: Boolean = true, onRelease: () -> Unit = {}) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val release by rememberUpdatedState(onRelease)
    Box(modifier.fillMaxWidth().height(if (thumb) 44.dp else 28.dp)
        .semantics {
            contentDescription = description
            progressBarRangeInfo = ProgressBarRangeInfo(current, 0f..1f)
            setProgress { target -> change(target.coerceIn(0f, 1f)); release(); true }
        }
        .pointerInput(Unit) { detectTapGestures { change((it.x / size.width).coerceIn(0f, 1f)); release() } }
        .pointerInput(Unit) {
            detectDragGestures(onDragEnd = { release() }, onDragCancel = { release() }) { pointer, _ ->
                pointer.consume(); change((pointer.position.x / size.width).coerceIn(0f, 1f))
            }
        }) {
        Canvas(Modifier.fillMaxWidth().height(if (thumb) 44.dp else 28.dp)) {
            val knob = if (thumb) 13.dp.toPx() else 0f
            val y = size.height / 2
            val left = knob; val right = size.width - knob
            val x = left + (right - left) * current
            val h = if (thumb) 4.dp.toPx() else 7.dp.toPx()
            drawRoundRect(track, Offset(left, y - h / 2), Size(right - left, h), CornerRadius(h / 2))
            drawRoundRect(fill, Offset(left, y - h / 2), Size((x - left).coerceAtLeast(0f), h), CornerRadius(h / 2))
            if (thumb) {
                drawCircle(Color(0x26000000), knob + 1.dp.toPx(), Offset(x, y + 1.5.dp.toPx()))
                drawCircle(Color.White, knob, Offset(x, y))
            }
        }
    }
}

/** iOS segmented control. */
@Composable
fun Segmented(labels: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val ink = Ink
    Row(modifier.clip(RoundedCornerShape(9.dp)).background(ink.fill).padding(2.dp)) {
        labels.forEachIndexed { index, label ->
            Box(Modifier.weight(1f).clip(RoundedCornerShape(7.dp))
                .background(if (index == selected) (if (ink.dark) Color(0xFF636366) else Color.White) else Color.Transparent)
                .clickable(role = Role.Tab) { onSelect(index) }.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                Label(label, 13f, if (index == selected) 600 else 500)
            }
        }
    }
}

/** iOS switch: green when on. */
@Composable
fun IosSwitch(on: Boolean, description: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Box(Modifier.size(51.dp, 31.dp).clip(RoundedCornerShape(50)).background(if (on) Color(0xFF34C759) else Ink.fill)
        .clickable(enabled = enabled, role = Role.Switch) { onChange(!on) }.semantics { contentDescription = description }
        .padding(2.dp), contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.size(27.dp).clip(RoundedCornerShape(50)).background(Color.White))
    }
}

/** Apple Music's capsule buttons under an album ("Wiedergabe", "Zufall"): grey fill, tint text and symbol. */
@Composable
fun Capsule(symbol: Symbol, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val ink = Ink
    Row(modifier.clip(RoundedCornerShape(10.dp)).background(ink.fill.copy(alpha = if (ink.dark) 0.36f else 0.12f))
        .clickable(role = Role.Button, onClick = onClick).padding(vertical = 13.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically) {
        SymbolIcon(symbol, ink.tint, 18.dp, filled = true)
        Box(Modifier.width(6.dp))
        Label(label, 17f, 600, ink.tint)
    }
}

@Composable
fun SectionHeader(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(text, 22f, 700, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) Label(action, 17f, 400, Ink.tint, Modifier.clickable(role = Role.Button, onClick = onAction))
    }
}
