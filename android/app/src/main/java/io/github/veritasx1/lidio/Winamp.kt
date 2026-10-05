package io.github.veritasx1.lidio

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * Winamp mode (card f11d52ad) – the one place where LiDio leaves Apple's HIG on purpose: Winamp 2's main window
 * (always there, it has the controls), the equalizer (on and off) and the playlist, which grows towards the main
 * window when the equalizer is off. Any Winamp 2 skin (.wsz) draws it; LiDio's own "Graphit" skin is built in
 * (tools/make_skin.py), Winamp's original skin is Nullsoft's and doesn't ship.
 *
 * Everything is placed in the skin's own pixels and scaled with nearest neighbour, so pixel art stays sharp on
 * any screen; portrait stacks the windows, wide screens put the playlist beside them.
 */

/** A Winamp 2 skin: its bitmaps by sheet name ("main", "cbuttons" …), the visualizer's colours, the playlist's colours. */
class Skin(val name: String, private val sheets: Map<String, ImageBitmap>, val vis: List<Color>,
           val normal: Color, val current: Color, val normalBg: Color, val selectedBg: Color) {
    fun sheet(name: String): ImageBitmap? = sheets[name]
    fun has(name: String) = sheets.containsKey(name)

    companion object {
        private val SHEETS = listOf("main", "cbuttons", "titlebar", "numbers", "nums_ex", "text", "posbar", "volume", "balance",
            "shufrep", "monoster", "playpaus", "eqmain", "eq_ex", "pledit", "gen")
        private var builtIn: Skin? = null

        /** LiDio Graphit, from the app's assets. */
        fun builtIn(context: Context): Skin = builtIn ?: context.assets.open("skins/lidio.wsz").use { read("LiDio Graphit", it, null) }.also { builtIn = it }

        /** Reads a .wsz (a zip): names in any case and in any folder; what a skin lacks comes from `fallback`
         *  (balance from volume and the numbers from each other first, as Winamp does). */
        fun read(name: String, stream: InputStream, fallback: Skin?): Skin {
            val files = mutableMapOf<String, ByteArray>()
            ZipInputStream(stream).use { zip ->
                var total = 0L
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val base = entry.name.substringAfterLast('/').substringAfterLast('\\').lowercase()
                    val key = base.substringBeforeLast('.')
                    if (base.endsWith(".bmp") && key in SHEETS || base == "viscolor.txt" || base == "pledit.txt") {
                        val bytes = zip.readBytes(); total += bytes.size
                        if (total > 32L shl 20) break   // no skin is that big – guard against zip bombs
                        files.putIfAbsent(if (base.endsWith(".bmp")) key else base, bytes)
                    }
                }
            }
            val sheets = mutableMapOf<String, ImageBitmap>()
            for ((key, bytes) in files) if (key in SHEETS) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { sheets[key] = it.asImageBitmap() }
            }
            sheets["volume"]?.let { sheets.putIfAbsent("balance", it) }
            sheets["numbers"]?.let { sheets.putIfAbsent("nums_ex", it) }
            sheets["nums_ex"]?.let { sheets.putIfAbsent("numbers", it) }
            fallback?.sheets?.forEach { (k, v) -> sheets.putIfAbsent(k, v) }
            val vis = files["viscolor.txt"]?.let { parseVis(String(it, Charsets.ISO_8859_1)) }?.takeIf { it.size >= 18 } ?: fallback?.vis ?: List(24) { Color.Green }
            val pl = files["pledit.txt"]?.let { parsePledit(String(it, Charsets.ISO_8859_1)) } ?: emptyMap()
            fun colour(key: String, default: Color?) = pl[key.lowercase()]?.let { parseColour(it) } ?: default ?: Color.White
            return Skin(name, sheets, vis,
                colour("Normal", fallback?.normal ?: Color(0xFF00FF00)), colour("Current", fallback?.current ?: Color.White),
                colour("NormalBG", fallback?.normalBg ?: Color.Black), colour("SelectedBG", fallback?.selectedBg ?: Color(0xFF0000C6)))
        }

        fun parseVis(text: String): List<Color> = text.lines().mapNotNull { line ->
            val n = Regex("""\d+""").findAll(line.substringBefore("//")).map { it.value.toInt().coerceIn(0, 255) }.take(3).toList()
            if (n.size == 3) Color(n[0], n[1], n[2]) else null
        }.take(24)

        fun parsePledit(text: String): Map<String, String> = text.lines().mapNotNull { line ->
            val i = line.indexOf('='); if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
        }.toMap()

        fun parseColour(value: String): Color? {
            val hex = value.removePrefix("#").take(6)
            return hex.toLongOrNull(16)?.takeIf { hex.length == 6 }?.let { Color(0xFF000000 or it) }
        }
    }
}

/** What the Winamp windows remember: which are open, the equalizer's bands (−12…+12 dB), balance (−1…1). */
class WinampSettings(context: Context) {
    private val prefs = context.getSharedPreferences("winamp", Context.MODE_PRIVATE)
    var on by mutableStateOf(prefs.getBoolean("an", false))
    var eq by mutableStateOf(prefs.getBoolean("eq", true))
    var playlist by mutableStateOf(prefs.getBoolean("pl", true))
    /** Card ef3a3dfb: Milkdrop in the window below (instead of the playlist), like Winamp's docked Milkdrop. */
    var milk by mutableStateOf(prefs.getBoolean("milk", false))
    var eqOn by mutableStateOf(prefs.getBoolean("eqAn", false))
    var eqAuto by mutableStateOf(prefs.getBoolean("eqAuto", false))
    var preamp by mutableFloatStateOf(prefs.getFloat("pre", 0f))
    val bands = mutableStateListOf<Float>().apply { (0 until 10).forEach { add(prefs.getFloat("b$it", 0f)) } }
    var balance by mutableFloatStateOf(prefs.getFloat("balance", 0f))
    var remaining by mutableStateOf(prefs.getBoolean("rest", false))
    var skin by mutableStateOf(prefs.getString("skin", "") ?: "")
    /** Winamp on the lock screen (needs Android's "display over other apps"). */
    var lockScreen by mutableStateOf(prefs.getBoolean("sperr", false))
    /** The visualizer: 0 analyzer, 1 oscilloscope, 2 off – a tap on it goes round, as in Winamp. */
    var vis by mutableIntStateOf(prefs.getInt("vis", 0))
    /** Winamp 2's visualization options: analyzer 0 normal, 1 fire, 2 line; peaks; thick bands; scope 0 dots, 1 lines, 2 solid. */
    var visStyle by mutableIntStateOf(prefs.getInt("visStil", 0))
    var visPeaks by mutableStateOf(prefs.getBoolean("visSpitzen", true))
    var visThick by mutableStateOf(prefs.getBoolean("dickeBalken", true))
    var scopeStyle by mutableIntStateOf(prefs.getInt("oszi", 1))

    fun save() {
        prefs.edit().putBoolean("an", on).putBoolean("eq", eq).putBoolean("pl", playlist).putBoolean("milk", milk).putBoolean("eqAn", eqOn)
            .putBoolean("eqAuto", eqAuto).putFloat("pre", preamp).putFloat("balance", balance).putBoolean("rest", remaining)
            .putString("skin", skin).putInt("vis", vis).putBoolean("sperr", lockScreen).putInt("visStil", visStyle).putBoolean("visSpitzen", visPeaks)
            .putBoolean("dickeBalken", visThick).putInt("oszi", scopeStyle).apply { bands.forEachIndexed { i, v -> putFloat("b$i", v) } }.apply()
        Dsp.set(eqOn, preamp, bands.toList(), balance)
    }
}

// --- drawing in skin pixels -----------------------------------------------------------------------------------------

/** Draws a sprite into (x, y, w, h) of this canvas (pixels), nearest neighbour; parts outside a small sheet are left out. */
fun DrawScope.sprite(skin: Skin, name: String, x: Int, y: Int, w: Int, h: Int, srcW: Int? = null, srcH: Int? = null, srcX: Int = 0, srcY: Int = 0) {
    val s = Sprites.find(name) ?: return
    val image = skin.sheet(s.sheet) ?: return
    val sx = s.x + srcX; val sy = s.y + srcY
    val sw = minOf(srcW ?: s.w, image.width - sx); val sh = minOf(srcH ?: s.h, image.height - sy)
    if (sw <= 0 || sh <= 0 || w <= 0 || h <= 0) return
    drawImage(image, IntOffset(sx, sy), IntSize(sw, sh), IntOffset(x, y),
        IntSize((w.toLong() * sw / (srcW ?: s.w)).toInt().coerceAtLeast(1), (h.toLong() * sh / (srcH ?: s.h)).toInt().coerceAtLeast(1)),
        filterQuality = FilterQuality.None)
}

/** The scale: screen pixels per skin pixel; edges are rounded the same way everywhere, so neighbours never gap. */
class Scale(val s: Float) {
    fun px(v: Int) = (v * s).roundToInt()
    fun px(v: Float) = (v * s).roundToInt()
}

/** A sprite at skin position (x, y) inside a window drawn with this scale. */
fun DrawScope.at(skin: Skin, k: Scale, name: String, x: Int, y: Int) {
    val sp = Sprites.find(name) ?: return
    sprite(skin, name, k.px(x), k.px(y), k.px(x + sp.w) - k.px(x), k.px(y + sp.h) - k.px(y))
}

/** Text in the skin's own 5×6 font (text.bmp). */
fun DrawScope.skinText(skin: Skin, k: Scale, text: String, x: Int, y: Int, maxChars: Int = Int.MAX_VALUE, shift: Int = 0) {
    val image = skin.sheet("text") ?: return
    text.take(maxChars).forEachIndexed { i, raw ->
        val (row, col) = fontCell(raw) ?: return@forEachIndexed
        val cx = x + i * 5 - shift
        if (col * 5 + 5 > image.width || row * 6 + 6 > image.height) return@forEachIndexed
        drawImage(image, IntOffset(col * 5, row * 6), IntSize(5, 6), IntOffset(k.px(cx), k.px(y)),
            IntSize(k.px(cx + 5) - k.px(cx), k.px(y + 6) - k.px(y)), filterQuality = FilterQuality.None)
    }
}

/** The skin font has one case and few accents: ü → u, ß → s, the rest by the nearest letter, else a space. */
private fun fontCell(c: Char): Pair<Int, Int>? {
    val f = Sprites.FONT
    return f[c] ?: f[c.lowercaseChar()] ?: f[c.uppercaseChar()]
        ?: java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD).firstOrNull()?.let { f[it.lowercaseChar()] ?: f[it.uppercaseChar()] }
        ?: (if (c == 'ß') f['s'] else null) ?: f[' ']
}

/** Places a child at skin position (x, y), w × h skin pixels, with exact pixel edges. */
private fun Modifier.place(k: Scale, x: Int, y: Int, w: Int, h: Int) = layout { measurable, _ ->
    val x0 = k.px(x); val y0 = k.px(y)
    val p = measurable.measure(Constraints.fixed((k.px(x + w) - x0).coerceAtLeast(1), (k.px(y + h) - y0).coerceAtLeast(1)))
    layout(p.width, p.height) { p.place(0, 0) }
}.let { Modifier.offset { IntOffset(k.px(x), k.px(y)) }.then(it) }

/** A window of w × h skin pixels. */
private fun Modifier.window(k: Scale, w: Int, h: Int) = layout { measurable, _ ->
    val p = measurable.measure(Constraints.fixed(k.px(w), k.px(h)))
    layout(p.width, p.height) { p.place(0, 0) }
}

/** A Winamp button: draws `name` (or its pressed/selected variant) and reads to TalkBack as a button. */
@Composable
private fun SkinButton(skin: Skin, k: Scale, name: String, x: Int, y: Int, label: String, pressedName: String? = "${name}_ACTIVE",
                       state: String? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val sp = Sprites.find(name) ?: return
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    Canvas(Modifier.place(k, x, y, sp.w, sp.h)
        .combinedClickable(source, null, role = Role.Button, onClickLabel = null, onLongClick = onLongClick, onClick = onClick)
        .semantics { contentDescription = label; if (state != null) stateDescription = state }) {
        val shown = if (pressed && pressedName != null && Sprites.find(pressedName) != null) pressedName else name
        sprite(skin, shown, 0, 0, size.width.toInt(), size.height.toInt())
    }
}

/** A horizontal or vertical skin slider: the area takes taps and drags; value 0…1. */
private fun Modifier.slide(value: () -> Float, description: String, vertical: Boolean = false, steps: Int = 0, change: (Float) -> Unit,
                           release: () -> Unit = {}, active: (Boolean) -> Unit = {}) = this
    .semantics {
        contentDescription = description
        progressBarRangeInfo = ProgressBarRangeInfo(value(), 0f..1f, steps)
        setProgress { change(it.coerceIn(0f, 1f)); release(); true }
    }
    .then(drags(vertical, change, release, active))

private fun drags(vertical: Boolean, change: (Float) -> Unit, release: () -> Unit, active: (Boolean) -> Unit): Modifier =
    Modifier.pointerInput(Unit) {
        fun at(o: androidx.compose.ui.geometry.Offset) = if (vertical) 1f - o.y / size.height else o.x / size.width
        detectDragGestures(onDragStart = { active(true); change(at(it).coerceIn(0f, 1f)) },
            onDragEnd = { active(false); release() }, onDragCancel = { active(false); release() }) { p, _ ->
            p.consume(); change(at(p.position).coerceIn(0f, 1f))
        }
    }.pointerInput(Unit) {
        detectTapGestures { change((if (vertical) 1f - it.y / size.height else it.x / size.width).coerceIn(0f, 1f)); release() }
    }

// --- the screen -----------------------------------------------------------------------------------------------------

/** Where the windows go: one scale for main + equalizer, the playlist beside or below. */
data class WinampLayout(val main: Scale, val mainX: Int, val mainY: Int, val eqY: Int, val plScale: Scale,
                        val plX: Int, val plY: Int, val plW: Int, val plH: Int, val side: Boolean)

/** Portrait (and anything taller than wide enough): the windows fill the width, the playlist takes what's left below –
 *  all the way up to the main window when the equalizer is off. Wide: main and equalizer left, playlist right. */
fun winampLayout(width: Int, height: Int, eq: Boolean, playlist: Boolean): WinampLayout {
    val stack = if (eq) 232 else 116
    val tall = width / 275f
    val roomBelow = height - stack * tall
    if (!playlist || roomBelow >= 116 * tall * 0.75f || width <= height) {
        val k = Scale(minOf(tall, height / stack.toFloat()))
        val mainX = ((width - k.px(275)) / 2).coerceAtLeast(0)
        val plTop = k.px(stack)
        val plH = floor((height - plTop) / k.s).toInt()
        return WinampLayout(k, mainX, 0, k.px(116), k, mainX, plTop, 275, plH.coerceAtLeast(0), false)
    }
    val k = Scale(minOf(height / stack.toFloat(), width * 0.5f / 275f))
    val left = k.px(275)
    val restW = width - left
    val pk = Scale(minOf(k.s, restW / 275f))
    return WinampLayout(k, 0, ((height - k.px(stack)) / 2).coerceAtLeast(0), k.px(116), pk, left, 0,
        floor(restW / pk.s).toInt(), floor(height / pk.s).toInt(), true)
}

@Composable
fun WinampScreen(state: AppState, server: MusicServer, onLibrary: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val w = state.winamp
    val skin = remember(w.skin) { loadSkin(context, w.skin) }
    var menu by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.roundToPx() }
        val height = with(density) { maxHeight.roundToPx() }
        val l = winampLayout(width, height, w.eq, w.playlist)
        Box(Modifier.offset { IntOffset(l.mainX, l.mainY) }) {
            MainWindow(state, skin, l.main, onLibrary = onLibrary, onMenu = { menu = true })
        }
        if (w.eq) Box(Modifier.offset { IntOffset(l.mainX, l.mainY + l.eqY) }) { EqWindow(state, skin, l.main) }
        if (w.playlist && l.plH >= 58) Box(Modifier.offset { IntOffset(l.plX, l.plY) }) {
            if (w.milk && Milk.loaded) MilkWindow(state, with(density) { l.plScale.px(l.plW).toDp() }, with(density) { l.plScale.px(l.plH).toDp() }) { menu = true }
            else PlaylistWindow(state, skin, l.plScale, l.plW, l.plH, onAdd = onLibrary, onMenu = { menu = true })
        }
    }
    if (menu) WinampMenu(state, onLibrary = onLibrary) { menu = false }
}

internal fun loadSkin(context: Context, path: String): Skin {
    val base = Skin.builtIn(context)
    if (path.isEmpty()) return base
    val file = java.io.File(path)
    return runCatching { file.inputStream().use { Skin.read(file.nameWithoutExtension, it, base) } }.getOrDefault(base)
}

private fun clock(ms: Long): Pair<Int, Int> { val s = (ms / 1000).coerceAtLeast(0); return ((s / 60) % 100).toInt() to (s % 60).toInt() }

@Composable
internal fun MainWindow(state: AppState, skin: Skin, k: Scale, onLibrary: () -> Unit, onMenu: () -> Unit,
                        onBolt: (() -> Unit)? = { state.winamp.on = false; state.winamp.save() }) {
    val p = state.playback
    val w = state.winamp
    val track = p.current
    var seeking by remember { mutableStateOf<Float?>(null) }
    var marquee by remember { mutableIntStateOf(0) }
    val title = track?.let { t -> "${(p.index + 1)}. ${t.artist} - ${t.title} (${duration(t.duration)})" } ?: "LiDio"
    LaunchedEffect(title) {
        marquee = 0
        if (title.length > 30) while (true) { delay(220); marquee = (marquee + 1) % (title.length + 5) }
    }
    var visOptions by remember { mutableStateOf(false) }
    if (visOptions) VisOptions(w) { visOptions = false }
    var blink by remember { mutableStateOf(true) }
    LaunchedEffect(p.playing, track) { blink = true; if (!p.playing && track != null) while (true) { delay(1000); blink = !blink } }

    Box(Modifier.window(k, 275, 116)) {
        Canvas(Modifier.window(k, 275, 116)) {
            at(skin, k, "MAIN_WINDOW_BACKGROUND", 0, 0)
            at(skin, k, "MAIN_TITLE_BAR_SELECTED", 0, 0)
            at(skin, k, "MAIN_CLUTTER_BAR_BACKGROUND", 10, 22)
            at(skin, k, when { track == null -> "MAIN_STOPPED_INDICATOR"; p.playing -> "MAIN_PLAYING_INDICATOR"; else -> "MAIN_PAUSED_INDICATOR" }, 26, 28)
            at(skin, k, if (p.buffering) "MAIN_NOT_WORKING_INDICATOR" else "MAIN_WORKING_INDICATOR", 24, 28)
            // Time: elapsed or (tap) remaining; blinks while paused, as in Winamp.
            if (track != null && blink) {
                // A real nums_ex.bmp is 108 wide (digits, blank, minus); a skin without one has only numbers.bmp,
                // whose minus is a 5 × 1 line drawn left of the minutes.
                val ex = (skin.sheet("nums_ex")?.width ?: 0) >= 108
                val shown = if (w.remaining) (p.length - p.position).coerceAtLeast(0) else p.position
                val (m, s) = clock(shown)
                val suffix = if (ex) "_EX" else ""
                listOf(m / 10, m % 10, s / 10, s % 10).zip(listOf(48, 60, 78, 90)).forEach { (d, x) -> at(skin, k, "DIGIT_$d$suffix", x, 26) }
                if (w.remaining) at(skin, k, if (ex) "MINUS_SIGN_EX" else "MINUS_SIGN", if (ex) 38 else 38, if (ex) 26 else 32)
            }
            // Title running through, kbps / kHz, stereo.
            clipRect(k.px(111).toFloat(), k.px(24).toFloat(), k.px(265).toFloat(), k.px(30).toFloat()) {
                val text = if (title.length > 30) "$title  ***  $title" else title
                skinText(skin, k, text, 111, 24, shift = marquee * 5)
            }
            if (track != null) {
                val kbps = if (track.size > 0 && track.duration > 0) (track.size * 8 / track.duration / 1000).coerceIn(1, 999) else p.settings.bitrate().takeIf { it > 0 } ?: 320
                skinText(skin, k, kbps.toString().padStart(3), 111, 43, 3)
                skinText(skin, k, "44", 156, 43, 2)
                at(skin, k, "MAIN_STEREO_SELECTED", 239, 41)
                at(skin, k, "MAIN_MONO", 212, 41)
            } else { at(skin, k, "MAIN_STEREO", 239, 41); at(skin, k, "MAIN_MONO", 212, 41) }
            // Volume and balance: the background frame follows the value.
            val vf = (p.volume * 27).roundToInt().coerceIn(0, 27)
            sprite(skin, "MAIN_VOLUME_BACKGROUND", k.px(107), k.px(57), k.px(175) - k.px(107), k.px(70) - k.px(57), srcH = 13, srcY = vf * 15)
            at(skin, k, "MAIN_VOLUME_THUMB", 107 + (p.volume * 51).roundToInt(), 58)
            val bf = (abs(w.balance) * 27).roundToInt().coerceIn(0, 27)
            sprite(skin, "MAIN_BALANCE_BACKGROUND", k.px(177), k.px(57), k.px(215) - k.px(177), k.px(70) - k.px(57), srcH = 13, srcY = bf * 15)
            at(skin, k, "MAIN_BALANCE_THUMB", 177 + ((w.balance + 1) / 2 * 24).roundToInt(), 58)
            // Position.
            at(skin, k, "MAIN_POSITION_SLIDER_BACKGROUND", 16, 72)
            if (track != null && p.length > 0) {
                val f = seeking ?: (p.position.toFloat() / p.length).coerceIn(0f, 1f)
                at(skin, k, if (seeking != null) "MAIN_POSITION_SLIDER_THUMB_SELECTED" else "MAIN_POSITION_SLIDER_THUMB", 16 + (f * 219).roundToInt(), 72)
            }
        }
        Visualizer(skin, k, p.playing && p.controller != null, w, onOptions = { visOptions = true })   // previews have no sound
        // Title bar.
        SkinButton(skin, k, "MAIN_OPTIONS_BUTTON", 6, 3, "Optionen", "MAIN_OPTIONS_BUTTON_DEPRESSED", onClick = onMenu)
        SkinButton(skin, k, "MAIN_MINIMIZE_BUTTON", 244, 3, "Mediathek", "MAIN_MINIMIZE_BUTTON_DEPRESSED", onClick = onLibrary)
        SkinButton(skin, k, "MAIN_SHADE_BUTTON", 254, 3, "Fensteroptionen", "MAIN_SHADE_BUTTON_DEPRESSED", onClick = onMenu)
        SkinButton(skin, k, "MAIN_CLOSE_BUTTON", 264, 3, "Winamp-Ansicht schließen", "MAIN_CLOSE_BUTTON_DEPRESSED", onClick = onLibrary)
        // Time: tap switches elapsed / remaining.
        Box(Modifier.place(k, 36, 26, 63, 13).clickable(role = Role.Button, onClickLabel = "Restzeit umschalten") { w.remaining = !w.remaining; w.save() }
            .semantics { contentDescription = if (w.remaining) "Restzeit" else "Spielzeit" })
        // Volume, balance, position.
        Box(Modifier.place(k, 107, 57, 68, 13).slide({ p.volume }, "Lautstärke", change = { p.changeVolume(it) }))
        Box(Modifier.place(k, 177, 57, 38, 13).slide({ (w.balance + 1) / 2 }, "Balance", change = { v ->
            w.balance = (v * 2 - 1).let { if (abs(it) < 0.08f) 0f else it }; Dsp.set(w.eqOn, w.preamp, w.bands.toList(), w.balance) }, release = { w.save() }))
        Box(Modifier.place(k, 16, 72, 248, 10).slide({ if (p.length > 0) p.position.toFloat() / p.length else 0f }, "Position",
            change = { seeking = it }, release = { seeking?.let { f -> p.seek((f * p.length).toLong()) }; seeking = null }))
        // Windows.
        SkinButton(skin, k, if (w.eq) "MAIN_EQ_BUTTON_SELECTED" else "MAIN_EQ_BUTTON", 219, 58, "Equalizer",
            if (w.eq) "MAIN_EQ_BUTTON_DEPRESSED_SELECTED" else "MAIN_EQ_BUTTON_DEPRESSED", if (w.eq) "an" else "aus") { w.eq = !w.eq; w.save() }
        SkinButton(skin, k, if (w.playlist) "MAIN_PLAYLIST_BUTTON_SELECTED" else "MAIN_PLAYLIST_BUTTON", 242, 58, "Playlist",
            if (w.playlist) "MAIN_PLAYLIST_BUTTON_DEPRESSED_SELECTED" else "MAIN_PLAYLIST_BUTTON_DEPRESSED", if (w.playlist) "an" else "aus") { w.playlist = !w.playlist; w.save() }
        // Transport.
        SkinButton(skin, k, "MAIN_PREVIOUS_BUTTON", 16, 88, "Zurück") { p.previous() }
        SkinButton(skin, k, "MAIN_PLAY_BUTTON", 39, 88, "Wiedergabe") {
            p.controller?.run { if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) { seekTo(0); prepare() }; play() }; p.refresh()
        }
        SkinButton(skin, k, "MAIN_PAUSE_BUTTON", 62, 88, "Pause") { if (p.playing) p.toggle() else if (track != null) p.toggle() }
        SkinButton(skin, k, "MAIN_STOP_BUTTON", 85, 88, "Stopp") { p.controller?.run { pause(); seekTo(0) }; p.refresh() }
        SkinButton(skin, k, "MAIN_NEXT_BUTTON", 108, 88, "Weiter") { p.next() }
        // Winamp's lightning bolt (the "about" corner): back to LiDio's normal view.
        if (onBolt != null) Box(Modifier.place(k, 253, 91, 13, 15).clickable(role = Role.Button, onClick = onBolt)
            .semantics { contentDescription = "Zur normalen Ansicht" })
        SkinButton(skin, k, "MAIN_EJECT_BUTTON", 136, 89, "Titel aus der Mediathek wählen", onClick = onLibrary)
        val shuffle = p.shuffle; val repeat = p.repeat != Player.REPEAT_MODE_OFF
        SkinButton(skin, k, if (shuffle) "MAIN_SHUFFLE_BUTTON_SELECTED" else "MAIN_SHUFFLE_BUTTON", 164, 89, "Zufall",
            if (shuffle) "MAIN_SHUFFLE_BUTTON_SELECTED_DEPRESSED" else "MAIN_SHUFFLE_BUTTON_DEPRESSED", if (shuffle) "an" else "aus") { p.toggleShuffle() }
        SkinButton(skin, k, if (repeat) "MAIN_REPEAT_BUTTON_SELECTED" else "MAIN_REPEAT_BUTTON", 210, 89, "Wiederholen",
            if (repeat) "MAIN_REPEAT_BUTTON_SELECTED_DEPRESSED" else "MAIN_REPEAT_BUTTON_DEPRESSED", if (repeat) "an" else "aus") {
            // Winamp knows only on/off: all titles, or nothing.
            p.controller?.repeatMode = if (repeat) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL; p.refresh()
        }
    }
}

/** Winamp's visualizer at (24, 43), 76 × 16, in the skin's colours (viscolor.txt): 0 background, 1 dots,
 *  2–17 the analyzer from top to bottom, 18–22 the oscilloscope, 23 the peaks. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Visualizer(skin: Skin, k: Scale, playing: Boolean, w: WinampSettings, onOptions: () -> Unit) {
    // Winamp 2: thick bands are 19 bars of 3 pixels (and a gap), thin bands 75 bars of 1 pixel.
    val analyzer = remember(w.visThick) { Analyzer(if (w.visThick) 19 else 75) }
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(playing, w.vis, analyzer) {
        if (w.vis == 2) return@LaunchedEffect
        while (true) {
            androidx.compose.runtime.withFrameNanos { }
            analyzer.step(playing); frame++
            if (!playing && analyzer.quiet) break
        }
    }
    val c = skin.vis
    fun colour(i: Int) = c.getOrElse(i) { c.lastOrNull() ?: Color.Green }
    Canvas(Modifier.place(k, 24, 43, 76, 16)
        .combinedClickable(role = Role.Button, onClickLabel = "Anzeige wechseln", onLongClickLabel = "Optionen", onLongClick = onOptions) {
            w.vis = (w.vis + 1) % 3; w.save()
        }
        .semantics { contentDescription = "Visualisierung: " + listOf("Spektrum", "Oszilloskop", "aus")[w.vis] }) {
        frame   // read, so every frame draws
        if (w.vis == 2) return@Canvas
        val px = size.width / 76f; val py = size.height / 16f
        fun dot(x: Int, y: Int, w: Int, h: Int, colour: Color) = drawRect(colour, androidx.compose.ui.geometry.Offset(x * px, y * py),
            androidx.compose.ui.geometry.Size(w * px, h * py))
        dot(0, 0, 76, 16, colour(0))
        for (y in 1 until 16 step 2) for (x in 1 until 76 step 2) dot(x, y, 1, 1, colour(1))
        if (w.vis == 0) {
            val count = analyzer.bars.size
            val width = if (count == 19) 3 else 1; val pitch = if (count == 19) 4 else 1
            for (b in 0 until count) {
                val h = analyzer.bars[b].roundToInt()
                for (y in 16 - h until 16) {
                    // Normal: colour by height on screen. Fire: from each bar's top down. Line: the whole bar in its top's colour.
                    val index = when (w.visStyle) { 1 -> 2 + (y - (16 - h)); 2 -> 2 + (16 - h); else -> 2 + y }
                    dot(b * pitch, y, width, 1, colour(index.coerceIn(2, 17)))
                }
                val peak = analyzer.peaks[b].roundToInt()
                if (w.visPeaks && peak > 0) dot(b * pitch, (16 - peak).coerceIn(0, 15), width, 1, colour(23))
            }
        } else {
            var last = -1
            for (x in 0 until 76) {
                val y = (8 - analyzer.wave[x] * 8f).roundToInt().coerceIn(0, 15)
                val shade = { yy: Int -> colour(18 + (abs(yy - 8) / 2).coerceAtMost(4)) }
                when (w.scopeStyle) {
                    0 -> dot(x, y, 1, 1, shade(y))                                                      // dots
                    2 -> for (yy in minOf(8, y)..maxOf(8, y)) dot(x, yy, 1, 1, shade(yy))               // solid
                    else -> { val from = if (last < 0) y else last                                       // lines
                        for (yy in minOf(from, y)..maxOf(from, y)) dot(x, yy, 1, 1, shade(yy)) }
                }
                last = y
            }
        }
    }
}

/** Winamp's right-click "Visualization options", as an Apple sheet: one tap per choice. */
@Composable
private fun VisOptions(w: WinampSettings, onClose: () -> Unit) {
    val ink = Ink
    fun pick(title: String, labels: List<String>, selected: Int, set: (Int) -> Unit) = @Composable {
        Label(title, 13f, color = ink.secondary, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp))
        Segmented(labels, selected, Modifier.padding(horizontal = 12.dp)) { set(it); w.save() }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(Modifier.widthIn(max = 340.dp).clip(RoundedCornerShape(14.dp)).background(ink.elevated).padding(bottom = 8.dp)) {
            Label("Visualisierung", 17f, 600, modifier = Modifier.padding(start = 16.dp, top = 16.dp))
            pick("ANZEIGE", listOf("Spektrum", "Oszilloskop", "Aus"), w.vis) { w.vis = it }()
            pick("SPEKTRUM", listOf("Normal", "Fire", "Line"), w.visStyle) { w.visStyle = it }()
            pick("BALKEN", listOf("Dünn", "Dick"), if (w.visThick) 1 else 0) { w.visThick = it == 1 }()
            pick("OSZILLOSKOP", listOf("Dots", "Lines", "Solid"), w.scopeStyle) { w.scopeStyle = it }()
            ListRow("Spitzen", height = 48.dp, separator = false, modifier = Modifier.padding(top = 8.dp),
                trailing = { IosSwitch(w.visPeaks, "Spitzen") { w.visPeaks = it; w.save() } })
            ListRow("Fertig", titleColor = ink.tint, separator = false, onClick = onClose)
        }
    }
}

/** The ten bands' labels, Winamp's frequencies. */
val EQ_BANDS = listOf("60", "170", "310", "600", "1K", "3K", "6K", "12K", "14K", "16K")

@Composable
private fun EqWindow(state: AppState, skin: Skin, k: Scale) {
    val w = state.winamp
    var dragging by remember { mutableIntStateOf(-2) }
    Box(Modifier.window(k, 275, 116)) {
        Canvas(Modifier.window(k, 275, 116)) {
            at(skin, k, "EQ_WINDOW_BACKGROUND", 0, 0)
            at(skin, k, "EQ_TITLE_BAR_SELECTED", 0, 0)
            at(skin, k, "EQ_GRAPH_BACKGROUND", 86, 17)
            graph(skin, k, w)
            fun slider(x: Int, db: Float, index: Int) {
                val f = ((db + 12f) / 24f * 27f).roundToInt().coerceIn(0, 27)
                sprite(skin, "EQ_SLIDER_BACKGROUND", k.px(x), k.px(38), k.px(x + 14) - k.px(x), k.px(101) - k.px(38),
                    srcW = 14, srcH = 63, srcX = (f % 14) * 15, srcY = (f / 14) * 65)
                val ty = 38 + ((12f - db) / 24f * 51f).roundToInt()
                at(skin, k, if (dragging == index) "EQ_SLIDER_THUMB_SELECTED" else "EQ_SLIDER_THUMB", x + 1, ty)
            }
            slider(21, w.preamp, -1)
            w.bands.forEachIndexed { i, v -> slider(78 + i * 18, v, i) }
        }
        SkinButton(skin, k, if (w.eqOn) "EQ_ON_BUTTON_SELECTED" else "EQ_ON_BUTTON", 14, 18, "Equalizer an",
            if (w.eqOn) "EQ_ON_BUTTON_SELECTED_DEPRESSED" else "EQ_ON_BUTTON_DEPRESSED", if (w.eqOn) "an" else "aus") { w.eqOn = !w.eqOn; w.save() }
        SkinButton(skin, k, if (w.eqAuto) "EQ_AUTO_BUTTON_SELECTED" else "EQ_AUTO_BUTTON", 40, 18, "Automatisch",
            if (w.eqAuto) "EQ_AUTO_BUTTON_SELECTED_DEPRESSED" else "EQ_AUTO_BUTTON_DEPRESSED", if (w.eqAuto) "an" else "aus") { w.eqAuto = !w.eqAuto; w.save() }
        SkinButton(skin, k, "EQ_PRESETS_BUTTON", 217, 18, "Voreinstellungen: alles auf 0", "EQ_PRESETS_BUTTON_SELECTED") {
            w.preamp = 0f; for (i in w.bands.indices) w.bands[i] = 0f; w.save()
        }
        SkinButton(skin, k, "EQ_CLOSE_BUTTON", 264, 3, "Equalizer ausblenden", "EQ_CLOSE_BUTTON_ACTIVE") { w.eq = false; w.save() }
        fun db(v: Float) = ((v * 24f - 12f) * 2).roundToInt() / 2f   // half-dB steps; 0 snaps
        Box(Modifier.place(k, 21, 38, 14, 63).slide({ (w.preamp + 12f) / 24f }, "Vorverstärkung", vertical = true,
            change = { dragging = -1; w.preamp = db(it); Dsp.set(w.eqOn, w.preamp, w.bands.toList(), w.balance) }, release = { dragging = -2; w.save() }))
        for (i in 0 until 10) Box(Modifier.place(k, 78 + i * 18, 38, 14, 63).slide({ (w.bands[i] + 12f) / 24f }, "${EQ_BANDS[i]} Hz",
            vertical = true, change = { dragging = i; w.bands[i] = db(it); Dsp.set(w.eqOn, w.preamp, w.bands.toList(), w.balance) }, release = { dragging = -2; w.save() }))
    }
}

/** The little curve in the equalizer: Winamp colours it from the skin's 19 line colours (top to bottom). */
private fun DrawScope.graph(skin: Skin, k: Scale, w: WinampSettings) {
    val colours = Sprites.find("EQ_GRAPH_LINE_COLORS")?.let { s -> skin.sheet(s.sheet) }
    val pre = 86 + 0; val top = 17
    // Preamp line.
    val py = top + 9 - (w.preamp / 12f * 9f).roundToInt()
    at(skin, k, "EQ_PREAMP_LINE", pre, py.coerceIn(top, top + 18))
    val points = w.bands.mapIndexed { i, v -> (pre + 1 + i * 12.2f) to (top + 9f - v / 12f * 9f) }
    val cs = Sprites.find("EQ_GRAPH_LINE_COLORS")
    val map = colours?.let { img -> cs?.takeIf { it.x < img.width && it.y + 19 <= img.height }?.let { img.toPixelMap(it.x, it.y, 1, 19) } }
    for (x in 0 until 111) {
        val gx = pre + 1 + x
        val seg = points.indexOfLast { it.first <= gx }.coerceIn(0, points.size - 2)
        val (x0, y0) = points[seg]; val (x1, y1) = points[seg + 1]
        val t = ((gx - x0) / (x1 - x0)).coerceIn(0f, 1f)
        val y = (y0 + (y1 - y0) * (t * t * (3 - 2 * t))).roundToInt().coerceIn(top, top + 18)
        val c = map?.get(0, y - top) ?: Color.White
        drawRect(c, androidx.compose.ui.geometry.Offset(k.px(gx).toFloat(), k.px(y).toFloat()),
            androidx.compose.ui.geometry.Size((k.px(gx + 1) - k.px(gx)).toFloat(), (k.px(y + 1) - k.px(y)).toFloat()))
    }
}

@Composable
internal fun PlaylistWindow(state: AppState, skin: Skin, k: Scale, width: Int, height: Int, onAdd: () -> Unit, onMenu: () -> Unit,
                            locked: Boolean = false) {
    val p = state.playback
    val list = rememberLazyListState()
    var selected by remember { mutableIntStateOf(-1) }
    val queue = p.queue
    LaunchedEffect(p.index) { if (p.index in queue.indices && list.layoutInfo.visibleItemsInfo.none { it.index == p.index }) list.animateScrollToItem(p.index) }
    Box(Modifier.window(k, width, height)) {
        Canvas(Modifier.window(k, width, height)) {
            // Frame: corners, title in the middle, tiles between; the sides tile down; the bottom with its corners.
            at(skin, k, "PLAYLIST_TOP_LEFT_SELECTED", 0, 0)
            var x = 25
            val titleX = (width - 100) / 2
            while (x < width - 25) { at(skin, k, "PLAYLIST_TOP_TILE_SELECTED", x, 0); x += 25 }
            at(skin, k, "PLAYLIST_TITLE_BAR_SELECTED", titleX, 0)
            at(skin, k, "PLAYLIST_TOP_RIGHT_CORNER_SELECTED", width - 25, 0)
            var y = 20
            while (y < height - 38) {
                clipRect(0f, k.px(20).toFloat(), size.width, k.px(height - 38).toFloat()) {
                    at(skin, k, "PLAYLIST_LEFT_TILE", 0, y); at(skin, k, "PLAYLIST_RIGHT_TILE", width - 20, y)
                }
                y += 29
            }
            x = 125
            while (x < width - 150) { at(skin, k, "PLAYLIST_BOTTOM_TILE", x, height - 38); x += 25 }
            if (width >= 350) at(skin, k, "PLAYLIST_VISUALIZER_BACKGROUND", width - 225, height - 38)
            at(skin, k, "PLAYLIST_BOTTOM_LEFT_CORNER", 0, height - 38)
            at(skin, k, "PLAYLIST_BOTTOM_RIGHT_CORNER", width - 150, height - 38)
            drawRect(skin.normalBg, androidx.compose.ui.geometry.Offset(k.px(12).toFloat(), k.px(20).toFloat()),
                androidx.compose.ui.geometry.Size((k.px(width - 20) - k.px(12)).toFloat(), (k.px(height - 38) - k.px(20)).toFloat()))
            // Scroll handle in the right side.
            val rows = height - 58
            val visible = list.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
            val f = if (queue.size > visible) list.firstVisibleItemIndex.toFloat() / (queue.size - visible) else 0f
            at(skin, k, "PLAYLIST_SCROLL_HANDLE", width - 15, 20 + (f.coerceIn(0f, 1f) * (rows - 18)).roundToInt())
            // Running time: total of the list.
            val total = queue.sumOf { it.duration.toLong() }
            val (lm, ls) = clock(p.length)
            skinText(skin, k, "%d:%02d/%d:%02d".format(lm, ls, total / 60, total % 60), width - 143, height - 28, 18)
        }
        // The list itself, in the skin's playlist colours.
        val density = LocalDensity.current
        val rowH = with(density) { (k.px(13)).toDp() }
        val fontSize = with(density) { (k.s * 8.5f).toSp() }
        val style = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = fontSize)
        LazyColumn(Modifier.place(k, 12, 20, width - 32, height - 58).clipToBounds(), state = list) {
            itemsIndexed(queue, key = { i, t -> "$i-${t.id}" }) { i, t ->
                val colour = if (i == p.index) skin.current else skin.normal
                Row(Modifier.fillMaxWidth().height(rowH).background(if (i == selected) skin.selectedBg else Color.Transparent)
                    .combinedClickableCompat(onClick = { selected = i }, onDouble = { p.jump(i) })
                    .semantics { contentDescription = "${i + 1}. ${t.artist} – ${t.title}, ${duration(t.duration)}${if (i == p.index) ", läuft" else ""}" },
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    BasicText("${i + 1}. ${t.artist} - ${t.title}", Modifier.weight(1f).padding(start = 2.dp), style = style.copy(color = colour),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(duration(t.duration), Modifier.padding(start = 4.dp, end = 2.dp), style = style.copy(color = colour), maxLines = 1)
                }
            }
        }
        // Bottom buttons: ADD → the library, REM → the selected title, SEL → none, MISC / LIST → options.
        Box(Modifier.place(k, 14, height - 30, 25, 18).clickable(role = Role.Button, onClickLabel = "Titel hinzufügen", onClick = onAdd)
            .semantics { contentDescription = "Titel hinzufügen" })
        Box(Modifier.place(k, 43, height - 30, 25, 18).clickable(role = Role.Button) {
            if (locked) onAdd() else if (selected in queue.indices) { p.remove(selected); selected = -1 }
        }.semantics { contentDescription = "Ausgewählten Titel entfernen" })
        Box(Modifier.place(k, 72, height - 30, 25, 18).clickable(role = Role.Button) { selected = -1 }.semantics { contentDescription = "Auswahl aufheben" })
        Box(Modifier.place(k, 101, height - 30, 25, 18).clickable(role = Role.Button, onClick = onMenu).semantics { contentDescription = "Playlist-Optionen" })
        Box(Modifier.place(k, width - 44, height - 30, 25, 18).clickable(role = Role.Button, onClick = onMenu).semantics { contentDescription = "Listenoptionen" })
    }
}

/** Winamp: a tap selects, a double tap plays. */
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onDouble: () -> Unit) = pointerInput(Unit) { detectTapGestures(onTap = { onClick() }, onDoubleTap = { onDouble() }) }

/** Options in Apple's style – outside the skin, so it reads the same with every skin. */
@Composable
private fun WinampMenu(state: AppState, onLibrary: () -> Unit, onClose: () -> Unit) {
    val ink = Ink
    val w = state.winamp
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(14.dp)).background(ink.elevated)) {
            ListRow(if (w.eq) "Equalizer ausblenden" else "Equalizer einblenden", onClick = { w.eq = !w.eq; w.save(); onClose() })
            ListRow(if (w.playlist) "Playlist ausblenden" else "Playlist einblenden", onClick = { w.playlist = !w.playlist; w.save(); onClose() })
            ListRow("Skin wählen …", onClick = { onClose(); onLibrary(); state.open(Route.Skins) })
            // Winamp's Ctrl+Shift+K: Milkdrop.
            if (Milk.loaded) ListRow(if (w.milk) "Playlist statt Milkdrop" else "Milkdrop im Fenster", onClick = { w.milk = !w.milk; w.playlist = true; w.save(); onClose() })
            if (Milk.loaded) ListRow("Milkdrop als Vollbild", onClick = { onClose(); state.milk = true })
            ListRow("Mediathek", onClick = { onClose(); onLibrary() })
            ListRow("Zur normalen Ansicht", separator = false, titleColor = ink.tint, onClick = { w.on = false; w.save(); onClose() })
        }
    }
}
