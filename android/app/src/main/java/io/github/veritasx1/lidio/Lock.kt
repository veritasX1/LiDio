package io.github.veritasx1.lidio

import io.github.veritasx1.lidio.i18n.tr

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import java.util.Calendar
import kotlin.math.roundToInt

/*
 * Card f11d52ad, last part: the lock screen in Winamp's look. When the screen goes off while music plays and the
 * Winamp view was the last one, LiDio puts this screen over the lock: a big clock in the skin's digits, the main
 * window and the playlist. Anything leading into the app (eject, options, ADD …) asks to unlock first – the library
 * and settings stay behind the lock. Swipe up to unlock.
 *
 * Android starts screens from the background only for apps allowed to "display over other apps"; the user turns
 * this on in LiDio's settings and grants that permission there.
 */

object LockScreen {
    fun allowed(context: Context) = android.provider.Settings.canDrawOverlays(context)

    /** Should the Winamp lock screen come up now? */
    fun wanted(context: Context, playing: Boolean): Boolean {
        val p = context.getSharedPreferences("winamp", Context.MODE_PRIVATE)
        return playing && p.getBoolean("an", false) && p.getBoolean("sperr", false) && allowed(context)
    }

    /** The player listens for the screen going off (registered while it runs). */
    fun receiver(isPlaying: () -> Boolean) = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF && wanted(context, isPlaying()))
                runCatching { context.startActivity(Intent(context, LockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)) }
        }
    }
}

class LockActivity : ComponentActivity() {
    private lateinit var playback: Playback
    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { finish() }   // unlocked some other way (fingerprint …)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        playback = Playback(this).also { it.connect() }
        val state = AppState(Accounts(this), playback)
        registerReceiver(unlocked, IntentFilter(Intent.ACTION_USER_PRESENT))
        setContent { LiDioTheme(dark = true) { WinampLockScreen(state, onUnlock = { open -> unlock(open) }) } }
    }

    /** Asks Android to unlock (code, fingerprint …); then into LiDio if `open`, else just away. */
    private fun unlock(open: Boolean) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        val done = {
            if (open) startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }
        if (keyguard == null || !keyguard.isKeyguardLocked) { done(); return }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { done() }
        })
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(unlocked) }
        playback.release()
        super.onDestroy()
    }
}

/** Places a child at (x, y) pixels with exact size w × h pixels. */
private fun Modifier.pixels(x: Int, y: Int, w: Int, h: Int) = offset { IntOffset(x, y) }.layout { m, _ ->
    val p = m.measure(Constraints.fixed(w.coerceAtLeast(1), h.coerceAtLeast(1))); layout(p.width, p.height) { p.place(0, 0) }
}

@Composable
fun WinampLockScreen(state: AppState, onUnlock: (open: Boolean) -> Unit) {
    val context = LocalContext.current
    val w = state.winamp
    val p = state.playback
    val skin = remember(w.skin) { loadSkin(context, w.skin) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(p.controller) { while (true) { p.refresh(); now = System.currentTimeMillis(); delay(250) } }
    var drag = 0f
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)
        .pointerInput(Unit) {
            detectVerticalDragGestures(onDragStart = { drag = 0f }, onDragEnd = { if (drag < -size.height * 0.12f) onUnlock(false) }) { change, dy ->
                change.consume(); drag += dy
            }
        }
        .windowInsetsPadding(WindowInsets.safeDrawing)) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.roundToPx() }
        val height = with(density) { maxHeight.roundToPx() }
        val wide = width > height
        // The clock: four digits of 9 × 13 skin pixels (and a colon), a third bigger than the main window's pixels allow.
        val k = Scale(if (wide) height / 260f else width / 275f)
        val big = k.s * if (wide) 2.2f else 2.6f
        val clockW = ((4 * 9 + 2 + 6) * big).roundToInt()
        val clockH = (13 * big).roundToInt()
        val top = (k.s * 10).roundToInt()
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        val hh = calendar.get(Calendar.HOUR_OF_DAY); val mm = calendar.get(Calendar.MINUTE)
        val days = listOf("SO", "MO", "DI", "MI", "DO", "FR", "SA")
        val date = "%s %02d.%02d.%d".format(days[calendar.get(Calendar.DAY_OF_WEEK) - 1], calendar.get(Calendar.DAY_OF_MONTH),
            calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.YEAR))
        val dateScale = Scale(big * 0.55f)
        val dateH = (6 * dateScale.s).roundToInt()
        val clockX = if (wide) (k.px(275) - clockW) / 2 else (width - clockW) / 2
        Canvas(Modifier.pixels(clockX, top, clockW, clockH + dateH + (6 * k.s).roundToInt())
            .semantics { contentDescription = "%d:%02d Uhr, %s".format(hh, mm, date) }) {
            fun digit(d: Int, x: Int) = sprite(skin, "DIGIT_$d", x, 0, (9 * big).roundToInt(), clockH)
            digit(hh / 10, 0); digit(hh % 10, (10 * big).roundToInt())
            val colon = colonColour(skin)
            val cx = (20 * big + 1.5f * big).roundToInt(); val dot = (2 * big)
            drawRect(colon, androidx.compose.ui.geometry.Offset(cx.toFloat(), 3.5f * big), androidx.compose.ui.geometry.Size(dot, dot))
            drawRect(colon, androidx.compose.ui.geometry.Offset(cx.toFloat(), 8.5f * big), androidx.compose.ui.geometry.Size(dot, dot))
            digit(mm / 10, (26 * big).roundToInt()); digit(mm % 10, (36 * big).roundToInt())
            val textW = date.length * 5
            skinText(skin, dateScale, date, ((clockW / dateScale.s - textW) / 2).roundToInt(), ((clockH + 4 * k.s) / dateScale.s).roundToInt())
        }
        // The windows below (or, wide, the main window under the clock and the playlist beside).
        val hint = (6 * k.s * 1.4f).roundToInt() + (12 * k.s).roundToInt()
        val windowsTop = top + clockH + dateH + (14 * k.s).roundToInt()
        if (!wide) {
            val l = winampLayout(width, height - windowsTop - hint, eq = false, playlist = true)
            Box(Modifier.offset { IntOffset(l.mainX, windowsTop + l.mainY) }) {
                MainWindow(state, skin, l.main, onLibrary = { onUnlock(true) }, onMenu = { onUnlock(true) }, onBolt = null)
            }
            if (l.plH >= 58) Box(Modifier.offset { IntOffset(l.plX, windowsTop + l.plY) }) {
                PlaylistWindow(state, skin, l.plScale, l.plW, l.plH, onAdd = { onUnlock(true) }, onMenu = { onUnlock(true) }, locked = true)
            }
        } else {
            Box(Modifier.offset { IntOffset(0, windowsTop) }) {
                MainWindow(state, skin, k, onLibrary = { onUnlock(true) }, onMenu = { onUnlock(true) }, onBolt = null)
            }
            val restW = width - k.px(275)
            val pk = Scale(minOf(k.s, restW / 275f))
            Box(Modifier.offset { IntOffset(k.px(275), 0) }) {
                PlaylistWindow(state, skin, pk, (restW / pk.s).toInt(), ((height - hint) / pk.s).toInt(), onAdd = { onUnlock(true) },
                    onMenu = { onUnlock(true) }, locked = true)
            }
        }
        // The hint, in the skin's own letters.
        val hk = Scale(k.s * 1.4f)
        val text = "NACH OBEN WISCHEN ZUM ENTSPERREN"
        Canvas(Modifier.pixels((width - (text.length * 5 * hk.s).roundToInt()) / 2, height - hint + (4 * k.s).roundToInt(),
            (text.length * 5 * hk.s).roundToInt(), (6 * hk.s).roundToInt()).semantics { contentDescription = tr("Nach oben wischen zum Entsperren") }) {
            skinText(skin, hk, text, 0, 0)
        }
    }
}

/** The brightest pixel of the skin's "8" – the colour its digits glow in. */
private fun colonColour(skin: Skin): Color {
    val s = Sprites.find("DIGIT_8") ?: return Color.Green
    val image = skin.sheet(s.sheet) ?: return Color.Green
    if (s.x + s.w > image.width || s.y + s.h > image.height) return Color.Green
    val pixels = image.toPixelMap(s.x, s.y, s.w, s.h)
    var best = Color.Green; var light = -1f
    for (x in 0 until s.w) for (y in 0 until s.h) {
        val c = pixels[x, y]; val l = c.red + c.green + c.blue
        if (l > light) { light = l; best = c }
    }
    return best
}
