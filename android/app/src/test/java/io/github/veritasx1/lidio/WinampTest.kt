package io.github.veritasx1.lidio

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Card f11d52ad: Winamp mode – skins, the layout on every screen shape, the windows. Pictures with -Pshots=<folder>. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WinampTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val out = System.getProperty("lidio.shots")
    private val server = FakeServer()

    private fun shot(name: String) {
        compose.waitForIdle()
        if (out != null) compose.onAllNodes(androidx.compose.ui.test.isRoot()).onFirst().captureRoboImage("$out/$name.png")
    }

    @Before
    fun clean() {
        context.getSharedPreferences("konten", 0).edit().clear().commit()
        context.getSharedPreferences("winamp", 0).edit().clear().commit()
    }

    private fun state(): AppState {
        val accounts = Accounts(context)
        accounts.save(Account("test", ServerKind.Navidrome, "http://test", "test", "geheim", "Navidrome (Test)"))
        return AppState(accounts, Playback(context)) { server }.apply { probe = { _, _ -> true } }.also {
            it.winamp.on = true
            it.playback.preview(server.tracks[2], server.tracks.take(12))
        }
    }

    @Test
    fun builtInSkinHasEverySheet() {
        val skin = Skin.builtIn(context)
        for (sheet in listOf("main", "cbuttons", "titlebar", "numbers", "nums_ex", "text", "posbar", "volume", "balance",
                "shufrep", "monoster", "playpaus", "eqmain", "eq_ex", "pledit")) assertNotNull(sheet, skin.sheet(sheet))
        assertEquals(275, skin.sheet("main")!!.width)
        assertTrue(skin.vis.size >= 18)
    }

    @Test
    fun foreignSkinFillsGapsAndReadsItsColours() {
        // A skin with only viscolor and pledit, in a folder and upper case, as many .wsz files are.
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { z ->
                z.putNextEntry(ZipEntry("MySkin/VISCOLOR.TXT")); z.write((0 until 24).joinToString("\n") { "$it,${it * 2},${it * 3} // farbe $it" }.toByteArray())
                z.putNextEntry(ZipEntry("MySkin/PLEDIT.TXT")); z.write("[Text]\r\nNormal=#00FF00\r\nCurrent=#FFFFFF\r\nNormalBG=#000000\r\nSelectedBG=#0000C6\r\n".toByteArray())
            }
        }.toByteArray()
        val skin = Skin.read("Mein Skin", zip.inputStream(), Skin.builtIn(context))
        assertEquals(24, skin.vis.size)
        assertEquals(androidx.compose.ui.graphics.Color(5, 10, 15), skin.vis[5])
        assertEquals(androidx.compose.ui.graphics.Color(0xFF00FF00), skin.normal)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF0000C6), skin.selectedBg)
        assertNotNull(skin.sheet("main"))   // from LiDio Graphit
    }

    @Test
    fun layoutFitsEveryShape() {
        // Phone portrait: full width, playlist below; without EQ it moves up to the main window.
        val phone = winampLayout(1080, 2200, eq = true, playlist = true)
        assertFalse(phone.side); assertEquals(1080, phone.main.px(275)); assertTrue(phone.plH > 116)
        val noEq = winampLayout(1080, 2200, eq = false, playlist = true)
        assertTrue(noEq.plY < phone.plY); assertTrue(noEq.plH > phone.plH)
        // Phone landscape and a wide tablet: playlist beside.
        for ((w, h) in listOf(2200 to 1080, 2560 to 1600, 1920 to 1080)) {
            val l = winampLayout(w, h, eq = true, playlist = true)
            assertTrue("$w×$h", l.side); assertTrue(l.main.px(232) <= h); assertTrue(l.plX + l.plScale.px(l.plW) <= w); assertTrue(l.plW >= 275)
        }
        // Square (foldables): still everything on screen.
        val square = winampLayout(1800, 1800, eq = true, playlist = true)
        assertTrue(square.main.px(232) <= 1800)
    }

    @Test
    fun portraitWithEqualizer() {
        val state = state()
        compose.setContent { LiDioTheme(dark = true) { WinampScreen(state, server) {} } }
        compose.onNodeWithContentDescription("Wiedergabe").assertExists()
        compose.onNodeWithContentDescription("60 Hz").assertExists()
        shot("w1-winamp-hoch")
        // EQ off: the playlist takes its place.
        compose.onNodeWithContentDescription("Equalizer").performClick()
        compose.waitForIdle()
        assertFalse(state.winamp.eq)
        shot("w2-winamp-ohne-eq")
    }

    @Test
    @Config(qualifiers = "w852dp-h393dp-xxhdpi")
    fun landscape() {
        val state = state()
        compose.setContent { LiDioTheme(dark = true) { WinampScreen(state, server) {} } }
        compose.onNodeWithContentDescription("Wiedergabe").assertExists()
        shot("w3-winamp-quer")
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun tablet() {
        val state = state()
        compose.setContent { LiDioTheme(dark = true) { WinampScreen(state, server) {} } }
        shot("w4-winamp-tablet")
    }

    @Test
    fun appOpensInWinampModeAndMenuLeavesIt() {
        val state = state()
        compose.setContent { LiDioTheme(dark = true) { LiDioApp(state) } }
        compose.waitForIdle()
        assertTrue(state.nowPlaying)
        compose.onNodeWithContentDescription("Optionen").performClick()
        compose.onNodeWithText("Zur normalen Ansicht").performClick()
        assertFalse(state.winamp.on)
        // Still in the player – now Apple's; its bolt goes back to Winamp, Winamp's bolt back again.
        assertTrue(state.nowPlaying)
        compose.onNodeWithContentDescription("Winamp-Ansicht").performClick()
        assertTrue(state.winamp.on)
        compose.onNodeWithContentDescription("Zur normalen Ansicht").performClick()
        assertFalse(state.winamp.on)
        shot("w5-player-mit-blitz")
    }

    @Test
    fun appleViewStaysWhenPlaying() {
        val state = state()
        state.winamp.on = false
        compose.setContent { LiDioTheme(dark = true) { LiDioApp(state) } }
        compose.waitForIdle()
        state.playback.preview(server.tracks[4], server.tracks.take(6))
        compose.waitForIdle()
        assertFalse(state.nowPlaying)   // Apple's way: playing doesn't open the player
    }

    @Test
    fun lockScreenKeepsTheAppBehindTheLock() {
        val state = state()
        var asked: Boolean? = null
        compose.setContent { LiDioTheme(dark = true) { WinampLockScreen(state) { open -> asked = open } } }
        compose.onNodeWithContentDescription("Nach oben wischen zum Entsperren").assertExists()
        compose.onNodeWithContentDescription("Wiedergabe").assertExists()
        shot("w6-sperrbildschirm")
        // Eject (into the library) asks to unlock first, and the bolt isn't there.
        compose.onNodeWithContentDescription("Titel aus der Mediathek wählen").performClick()
        assertEquals(true, asked)
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Zur normalen Ansicht")).fetchSemanticsNodes().size)
    }
}
