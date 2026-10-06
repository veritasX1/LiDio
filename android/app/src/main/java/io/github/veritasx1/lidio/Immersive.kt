package io.github.veritasx1.lidio

import android.app.Activity
import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import io.github.veritasx1.lidio.i18n.tr
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * Album and playlist pages like Apple Music in iOS 26.4 (Olaf 05.10.2026, "den modernen Apple Music Modus"): the cover fills
 * the top edge to edge, fades at its foot into a colour taken from the picture, the name stands on it in white, and the
 * list of titles lies on that colour. Only in the modern look; the classic look keeps the white page.
 */
object PageColor {
    /** The colour at the cover's foot (where it meets the list), dark enough for white writing. */
    fun of(bitmap: Bitmap): Color {
        val small = Bitmap.createScaledBitmap(bitmap, 16, 16, true)
        var r = 0f; var g = 0f; var b = 0f
        for (x in 0 until 16) for (y in 12 until 16) { val p = small.getPixel(x, y); r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255 }
        val n = 64f * 255f
        return readable(Color(r / n, g / n, b / n))
    }

    /** White writing needs a dark ground: a light colour is darkened until it carries it (the hue stays). */
    fun readable(c: Color): Color {
        val light = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
        val f = if (light > 0.22f) 0.22f / light else 1f
        return Color(c.red * f * 0.92f, c.green * f * 0.92f, c.blue * f * 0.92f)
    }

    /** The page's colours on that ground: white writing, white buttons. */
    fun palette(ground: Color) = DarkPalette.copy(background = ground, grouped = ground, card = Color.White.copy(alpha = 0.08f).compositeOver(ground),
        elevated = Color.White.copy(alpha = 0.14f).compositeOver(ground), label = Color.White, secondary = Color.White.copy(alpha = 0.68f),
        tertiary = Color.White.copy(alpha = 0.38f), separator = Color.White.copy(alpha = 0.16f), fill = Color.White, tint = Color.White)

    private fun Color.compositeOver(under: Color) = Color(red * alpha + under.red * (1 - alpha), green * alpha + under.green * (1 - alpha),
        blue * alpha + under.blue * (1 - alpha))

    /** Before the picture is there: a neutral dark grey (no flash of white). */
    val waiting = Color(0xFF2C2C2E)
}

/** The page in the cover's colour; the status bar writes white on it while the page is open. */
@Composable
fun ImmersivePage(ground: Color?, content: @Composable () -> Unit) {
    val colour by animateColorAsState(ground ?: PageColor.waiting, tween(350), label = "Seitenfarbe")
    val activity = LocalContext.current as? Activity
    DisposableEffect(activity) {
        val bars = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val before = bars?.isAppearanceLightStatusBars
        bars?.isAppearanceLightStatusBars = false
        onDispose { if (before != null) bars.isAppearanceLightStatusBars = before }
    }
    CompositionLocalProvider(LocalPalette provides PageColor.palette(colour)) {
        Box(Modifier.fillMaxSize().background(colour)) { content() }
    }
}

/** The big cover at the top with the name and the line below in white, fading into the page's colour. */
@Composable
fun ImmersiveHeader(cover: String?, title: String, subtitle: String?, meta: String?, ground: Color?,
                    onSubtitle: (() -> Unit)? = null, fallback: (() -> String?)? = null, onGround: (Color) -> Unit) {
    val ink = Ink
    Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
        Cover(cover, Modifier.fillMaxSize(), 0.dp, fallback = fallback, onTint = { groundOf(cover, it, onGround) })
        val fade = ground ?: PageColor.waiting
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.28f), 0.18f to Color.Transparent,
            0.55f to Color.Transparent, 1f to fade)))
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Label(title, 24f, 700, Color.White, lines = 2, align = TextAlign.Center, shadow = OnArt)
            subtitle?.let {
                Label(it, 20f, 500, Color.White.copy(alpha = 0.85f), lines = 1, shadow = OnArt,
                    modifier = if (onSubtitle != null) Modifier.clickable(onClick = onSubtitle) else Modifier)
            }
            meta?.takeIf { it.isNotBlank() }?.let { Label(it, 13f, 600, ink.secondary, Modifier.padding(top = 4.dp)) }
        }
    }
}

/** A page with a back bar: classic – the bar above the page; modern – the page in the cover's colour, the glass bar floating on it. */
@Composable
fun PageFrame(state: AppState, modern: Boolean, ground: Color?, trailing: (@Composable () -> Unit)?, content: @Composable () -> Unit) {
    if (!modern) {
        Column(Modifier.fillMaxSize()) { NavBar(state, trailing = trailing); content() }
        return
    }
    ImmersivePage(ground) {
        content()
        NavBar(state, clear = true, trailing = trailing)
    }
}

/** The colour comes from the picture's foot (Cover hands the average; the picture itself is in the cache). */
private fun groundOf(url: String?, average: Color, onGround: (Color) -> Unit) =
    url?.let { Covers.cached(it) }?.let { onGround(PageColor.of(it)) } ?: onGround(PageColor.readable(average))

/** An artist's page top like Apple Music: their picture over the whole width, the name big in white at its foot, the play button beside it. */
@Composable
fun ArtistHero(picture: String?, name: String, ground: Color?, onPlay: (() -> Unit)?, onGround: (Color) -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
        Cover(picture, Modifier.fillMaxSize(), 0.dp, onTint = { groundOf(picture, it, onGround) })
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.28f), 0.18f to Color.Transparent,
            0.6f to Color.Transparent, 1f to (ground ?: PageColor.waiting))))
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Label(name, 34f, 800, Color.White, lines = 2, modifier = Modifier.weight(1f), shadow = OnArt)
            onPlay?.let { play ->
                Box(Modifier.padding(start = 12.dp).size(52.dp).clip(CircleShape).background(Color(0xFFFF2D55))
                    .clickable(role = Role.Button, onClickLabel = tr("Wiedergabe"), onClick = play), contentAlignment = Alignment.Center) {
                    SymbolIcon(Symbol.Play, Color.White, 24.dp, filled = true)
                }
            }
        }
    }
}
