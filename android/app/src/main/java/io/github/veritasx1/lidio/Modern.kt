package io.github.veritasx1.lidio

import io.github.veritasx1.lidio.i18n.tr

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** The "Modern" look (card d894cc42; Olaf: "genauso wie die Musik-App unter iOS" – iOS 26): the tab bar and the mini player float
 *  as frosted glass over the content, which scrolls on beneath them; buttons in the bars are glass circles. The "Klassisch" look
 *  stays as it was. Everything else (lists, Now Playing's layout) is shared. */

/** Room the floating bars take at the bottom – lists end above it so the last row can be scrolled clear. */
val LocalBottomChrome = compositionLocalOf { 0.dp }
/** The glass blurs what lies behind it: the screens are the source. */
val LocalHaze = compositionLocalOf<HazeState?> { null }
val LocalModern = compositionLocalOf { false }

@Composable
fun chromePadding() = PaddingValues(bottom = LocalBottomChrome.current)

/** Liquid glass: the blurred background, a light tint, a thin bright rim and a soft shadow. */
@Composable
fun Modifier.glass(shape: Shape, elevation: Dp = 12.dp): Modifier {
    val ink = Ink
    val haze = LocalHaze.current
    val tint = if (ink.dark) Color(0xFF2C2C2E).copy(alpha = 0.55f) else Color.White.copy(alpha = 0.62f)
    val rim = Brush.verticalGradient(if (ink.dark) listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.05f))
        else listOf(Color.White.copy(alpha = 0.9f), Color.Black.copy(alpha = 0.06f)))
    return this.shadow(elevation, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
        .clip(shape)
        .then(if (haze != null) Modifier.hazeEffect(haze, HazeStyle(backgroundColor = ink.background, tint = HazeTint(tint), blurRadius = 22.dp, noiseFactor = 0f))
              else Modifier.background(tint))
        .border(0.75.dp, rim, shape)
}

/** A round glass button (iOS 26's back button and toolbar buttons). */
@Composable
fun GlassCircle(symbol: Symbol, description: String, size: Dp = 44.dp, onClick: () -> Unit) {
    Box(Modifier.size(size).glass(CircleShape, 6.dp).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center) {
        SymbolIcon(symbol, Ink.label, size * 0.45f, weight = 2.4f)
    }
}

/** Bottom of the "Modern" look: the mini player as a glass capsule, below it the tab bar as a glass pill and Search as its own
 *  glass circle beside it – like Music in iOS 26. */
@Composable
fun ModernChrome(state: AppState, server: MusicServer, modifier: Modifier = Modifier) {
    val ink = Ink
    val playback = state.playback
    val small = state.chromeSmall
    Column(modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(start = 16.dp, end = 16.dp, bottom = 6.dp)
        .animateContentSize(spring(dampingRatio = 0.8f, stiffness = 400f))) {
        val track = playback.current
        if (track != null && !small) {
            MiniCapsule(state, server, track, Modifier.fillMaxWidth().tourAnchor("miniplayer"))
            Spacer(Modifier.height(10.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (small) {
                // Shrunk: only the current tab, as a circle (in Search the search circle on the right is that already);
                // a tap brings the whole bar back.
                if (state.tab != Tab.Search) Box(Modifier.size(56.dp).glass(CircleShape).clickable(role = Role.Button) { state.chromeSmall = false }
                    .semantics { contentDescription = tr("{label} – Leiste zeigen", "label" to state.tab.label) }, contentAlignment = Alignment.Center) {
                    SymbolIcon(state.tab.symbol, ink.tint, 24.dp, filled = true)
                }
                if (state.tab != Tab.Search) Spacer(Modifier.width(10.dp))
                if (track != null) MiniCapsule(state, server, track, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
            } else {
                Row(Modifier.weight(1f).height(62.dp).glass(RoundedCornerShape(31.dp)).padding(4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Tab.entries.filter { it != Tab.Search }.forEach { tab -> ModernTab(state, tab, Modifier.weight(1f).tourAnchor(if (tab == Tab.Start) "start" else "mediathek")) }
                }
                Spacer(Modifier.width(12.dp))
            }
            // Search sits apart, a circle of its own (iOS 26).
            val on = state.tab == Tab.Search
            val size = if (small) 56.dp else 62.dp
            Box(Modifier.size(size).tourAnchor("suchen").glass(CircleShape).clickable(role = Role.Tab) { select(state, Tab.Search) }
                .semantics { contentDescription = Tab.Search.label }, contentAlignment = Alignment.Center) {
                SymbolIcon(Symbol.Search, if (on) ink.tint else ink.label, 26.dp, weight = 2.2f)
            }
        }
    }
}

/** The mini player as a glass capsule: art, title and artist, play/pause, next; a tap opens Now Playing. */
@Composable
private fun MiniCapsule(state: AppState, server: MusicServer, track: Track, modifier: Modifier) {
    val ink = Ink
    val playback = state.playback
    Row(modifier.height(56.dp).glass(RoundedCornerShape(28.dp))
        .clickable(onClickLabel = tr("Wiedergabe öffnen")) { state.nowPlaying = true }.padding(start = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Cover(server.cover(track, 120), Modifier.size(40.dp), 20.dp)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Label(track.title, 15f, 600)
            Label(track.artist, 13f, color = ink.secondary)
        }
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) { playback.toggle() }
            .semantics { contentDescription = if (playback.playing) tr("Pause") else tr("Wiedergabe") }, contentAlignment = Alignment.Center) {
            SymbolIcon(if (playback.playing) Symbol.Pause else Symbol.Play, ink.label, 22.dp, filled = true)
        }
        if (!state.chromeSmall) Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) { playback.next() }
            .semantics { contentDescription = tr("Nächster Titel") }, contentAlignment = Alignment.Center) {
            SymbolIcon(Symbol.Forward, ink.label, 24.dp, filled = true)
        }
    }
}

@Composable
private fun ModernTab(state: AppState, tab: Tab, modifier: Modifier) {
    val ink = Ink
    val on = tab == state.tab
    Column(modifier.height(54.dp).clip(RoundedCornerShape(27.dp))
        .background(if (on) (if (ink.dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.06f)) else Color.Transparent)
        .clickable(role = Role.Tab) { select(state, tab) }.semantics { contentDescription = tab.label },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        SymbolIcon(tab.symbol, if (on) ink.tint else ink.label, 24.dp, filled = on)
        Label(tab.label, 10f, 600, if (on) ink.tint else ink.label, Modifier.padding(top = 2.dp))
    }
}

/** Tapping the tab you're on goes back to its start (iOS). */
fun select(state: AppState, tab: Tab) { if (tab == state.tab) state.stacks.remove(tab) else state.tab = tab }

/** The height the floating bars need (without the system's navigation bar, which ModernChrome adds itself). */
fun chromeHeight(playing: Boolean): Dp = 62.dp + 6.dp + 16.dp + if (playing) 66.dp else 0.dp
