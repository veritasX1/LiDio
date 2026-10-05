package io.github.veritasx1.lidio

import io.github.veritasx1.lidio.i18n.tr

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Card ef3a3dfb: Milkdrop through projectM (LGPL-2.1) – the visualisation Winamp made famous. The presets come with the app
 *  (projectM's public-domain "cream of the crop", a selection). The sound comes from the same ring as Winamp's analyser. */
object Milk {
    val loaded: Boolean = runCatching { System.loadLibrary("projectM-4"); System.loadLibrary("lidiomilk") }.isSuccess

    @JvmStatic external fun nCreate(): Long
    @JvmStatic external fun nSize(h: Long, w: Int, height: Int)
    @JvmStatic external fun nPreset(h: Long, path: String, smooth: Boolean)
    @JvmStatic external fun nPcm(h: Long, samples: FloatArray, count: Int)
    @JvmStatic external fun nFrame(h: Long)
    @JvmStatic external fun nDestroy(h: Long)

    /** The presets, copied once from the app's assets into its files (projectM reads files). */
    fun presets(context: Context): List<File> {
        val dir = File(context.filesDir, "milkdrop")
        val marker = File(dir, ".version")
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString() }.getOrDefault("1")
        if (marker.takeIf { it.exists() }?.readText() != version) {
            dir.deleteRecursively()
            context.assets.list("milkdrop")?.forEach { cat ->
                val to = File(dir, cat).apply { mkdirs() }
                context.assets.list("milkdrop/$cat")?.forEach { f -> context.assets.open("milkdrop/$cat/$f").use { i -> File(to, f).outputStream().use { i.copyTo(it) } } }
            }
            marker.writeText(version)
        }
        return dir.walk().filter { it.isFile && it.extension == "milk" }.sortedBy { it.path }.toList()
    }
}

/** The GL side: projectM is created on the GL thread, fed with the last heard samples and asked for a frame each time. */
internal class MilkRenderer(private val presets: List<File>) : GLSurfaceView.Renderer {
    var handle = 0L
    @Volatile var wanted: Int = 0
    private var shown = -1
    private val pcm = FloatArray(4096)
    private var size = 0 to 0
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) { release(); size = 0 to 0 }
    /** projectM keeps the framebuffers of its first size – a new size (the docked window is laid out twice) gets a new instance. */
    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES30.glViewport(0, 0, w, h)
        if (size != w to h) { release(); handle = Milk.nCreate(); Milk.nSize(handle, w, h); size = w to h; shown = -1 }
    }
    override fun onDrawFrame(gl: GL10?) {
        if (handle == 0L) return
        if (wanted != shown && presets.isNotEmpty()) { Milk.nPreset(handle, presets[Math.floorMod(wanted, presets.size)].path, shown >= 0); shown = wanted }
        // 60 frames a second are enough (and spare the battery on 120-Hz screens); projectM gets the samples since the last frame.
        val now = System.nanoTime()
        val wait = 16_400_000L - (now - last)
        if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
        val t = System.nanoTime()
        val n = ((t - last) / 1e9 * Dsp.sampleRate).toInt().coerceIn(256, pcm.size)
        last = t
        Dsp.recent(n, pcm)
        Milk.nPcm(handle, pcm, n)
        Milk.nFrame(handle)
    }
    private var last = System.nanoTime()
    fun release() { if (handle != 0L) { Milk.nDestroy(handle); handle = 0L } }
}

/** Full screen like Winamp's Milkdrop window: tap shows title and controls; ‹ › change the preset, the shuffle button picks at
 *  random every 20 s (on by default), double tap to the next preset. */
@Composable
fun MilkScreen(state: AppState, onClose: () -> Unit) {
    val context = LocalContext.current
    val presets = remember { Milk.presets(context) }
    val prefs = remember { context.getSharedPreferences("milkdrop", Context.MODE_PRIVATE) }
    var index by remember { mutableStateOf(prefs.getInt("preset", (0 until presets.size.coerceAtLeast(1)).random())) }
    var shuffle by remember { mutableStateOf(prefs.getBoolean("zufall", true)) }
    var controls by remember { mutableStateOf(true) }
    val renderer = remember { MilkRenderer(presets) }
    LaunchedEffect(index) { renderer.wanted = index; prefs.edit().putInt("preset", index).apply() }
    LaunchedEffect(shuffle, index) { if (shuffle) { kotlinx.coroutines.delay(20_000); index = (0 until presets.size).random() } }
    LaunchedEffect(controls) { if (controls) { kotlinx.coroutines.delay(3500); controls = false } }
    var view by remember { mutableStateOf<GLSurfaceView?>(null) }
    DisposableEffect(Unit) { onDispose { view?.let { v -> v.queueEvent { renderer.release() }; v.onPause() } } }
    androidx.activity.compose.BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ c -> GLSurfaceView(c).apply { setEGLContextClientVersion(3); setRenderer(renderer); renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY; view = this } },
            Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { controls = !controls }, onDoubleTap = { index++; controls = true }) })
        AnimatedVisibility(controls, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)).clickable(role = Role.Button, onClick = onClose)
                        .semantics { contentDescription = tr("Milkdrop schließen") }, contentAlignment = Alignment.Center) { SymbolIcon(Symbol.Close, Color.White, 16.dp, weight = 2.6f) }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        state.playback.current?.let { Label(it.title, 17f, 600, Color.White); Label(it.artist, 13f, color = Color.White.copy(alpha = 0.7f)) }
                    }
                }
                Spacer(Modifier.weight(1f))
                val name = presets.getOrNull(Math.floorMod(index, presets.size.coerceAtLeast(1)))
                Label(name?.let { "${it.parentFile?.name} · ${it.nameWithoutExtension}" } ?: tr("Keine Presets"), 13f, color = Color.White.copy(alpha = 0.8f), lines = 2,
                    modifier = Modifier.padding(bottom = 10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Circle(Symbol.Backward, tr("Voriges Preset")) { index--; controls = true }
                    Spacer(Modifier.size(16.dp))
                    Circle(if (state.playback.playing) Symbol.Pause else Symbol.Play, if (state.playback.playing) tr("Pause") else tr("Wiedergabe")) { state.playback.toggle(); controls = true }
                    Spacer(Modifier.size(16.dp))
                    Circle(Symbol.Forward, tr("Nächstes Preset")) { index++; controls = true }
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.size(44.dp).clip(CircleShape).background(if (shuffle) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.2f))
                        .clickable(role = Role.Switch) { shuffle = !shuffle; prefs.edit().putBoolean("zufall", shuffle).apply(); controls = true }
                        .semantics { contentDescription = tr("Presets wechseln von selbst: {if}", "if" to (if (shuffle) "an" else "aus")) }, contentAlignment = Alignment.Center) {
                        SymbolIcon(Symbol.Shuffle, if (shuffle) Color.Black else Color.White, 20.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Circle(symbol: Symbol, label: String, onClick: () -> Unit) {
    Box(Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) { SymbolIcon(symbol, Color.White, 24.dp, filled = true) }
}

/** Milkdrop docked in Winamp's window (where the playlist is): tap = full screen, long press = Winamp's options. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MilkWindow(state: AppState, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp, onMenu: () -> Unit) {
    val context = LocalContext.current
    val presets = remember { Milk.presets(context) }
    val prefs = remember { context.getSharedPreferences("milkdrop", Context.MODE_PRIVATE) }
    var index by remember { mutableStateOf(prefs.getInt("preset", 0)) }
    val renderer = remember { MilkRenderer(presets) }
    LaunchedEffect(index) { renderer.wanted = index; prefs.edit().putInt("preset", index).apply() }
    LaunchedEffect(index) { if (prefs.getBoolean("zufall", true)) { kotlinx.coroutines.delay(20_000); index = (0 until presets.size).random() } }
    var view by remember { mutableStateOf<GLSurfaceView?>(null) }
    DisposableEffect(Unit) { onDispose { view?.let { v -> v.queueEvent { renderer.release() }; v.onPause() } } }
    Box(Modifier.size(width, height).background(Color.Black)) {
        AndroidView({ c -> GLSurfaceView(c).apply { setEGLContextClientVersion(3); setRenderer(renderer); view = this } }, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().androidx_combined(onClick = { state.milk = true }, onLongClick = onMenu, onDouble = { index++ })
            .semantics { contentDescription = tr("Milkdrop – antippen für Vollbild") })
    }
}

private fun Modifier.androidx_combined(onClick: () -> Unit, onLongClick: () -> Unit, onDouble: () -> Unit) =
    pointerInput(Unit) { detectTapGestures(onTap = { onClick() }, onLongPress = { onLongClick() }, onDoubleTap = { onDouble() }) }
