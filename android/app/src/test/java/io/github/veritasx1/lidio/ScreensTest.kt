package io.github.veritasx1.lidio

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Card 1: the screens after Apple Music, light and dark. Pictures with -Pshots=<folder>. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val out = System.getProperty("lidio.shots")
    private val server = FakeServer()

    private fun shot(name: String) {
        compose.waitForIdle()
        if (out != null) compose.onAllNodes(androidx.compose.ui.test.isRoot()).onFirst().captureRoboImage("$out/$name.png")
    }

    private fun state(): AppState {
        val accounts = Accounts(context)
        accounts.save(Account("test", ServerKind.Navidrome, "http://test", "test", "geheim", "Navidrome (Test)"))
        // "Klassisch" unless a test switches to "Modern" itself (Modern is the default since 05.10.2026).
        return AppState(accounts, Playback(context)) { server }.apply { probe = { _, _ -> true }; modern = false }
    }

    @Before
    fun clean() { context.getSharedPreferences("konten", 0).edit().clear().commit(); LoadCache.clear() }

    @Test
    fun welcomeAsksForAServer() {
        // The private variant has no welcome form (its own test).
        org.junit.Assume.assumeFalse(Variant.PRIVATE)
        val state = AppState(Accounts(context), Playback(context)) { server }.apply { probe = { _, _ -> true } }
        compose.setContent { LiDioTheme(dark = true) { LiDioApp(state) } }
        compose.onNodeWithText("Willkommen bei LiDio").assertExists()
        compose.onNodeWithText("Emby").assertExists()
        shot("01-willkommen")
        // Without address the form says what is missing instead of trying.
        compose.onNodeWithText("Verbinden").performClick()
        compose.onNodeWithText("Bitte Adresse und Benutzername eintragen.").assertExists()
    }

    @Test
    fun startAndLibrary() {
        val state = state()
        compose.setContent { LiDioTheme(dark = false) { LiDioApp(state) } }
        compose.onNodeWithText("Zuletzt gespielt").assertExists()
        compose.onNodeWithText("Neu hinzugefügt").assertExists()
        shot("02-start-hell")
        compose.onNodeWithContentDescription("Mediathek").performClick()
        compose.onNodeWithText("Interpreten").assertExists()
        shot("03-mediathek-hell")
        compose.onNodeWithText("Interpreten").performClick()
        compose.waitUntil(3000) { compose.onAllNodesWithText("Kreis Quartett").fetchSemanticsNodes().isNotEmpty() }   // loads in the background
        compose.onAllNodesWithText("Mediathek").assertCountEquals(2)   // the way back in the nav bar, and the tab
        shot("04-interpreten-hell")
    }

    /** Card d894cc42: "Modern" – glass tab bar with Search as its own circle, back as a round button (no text). */
    @Test
    fun modernLook() {
        val state = state()
        state.modern = true
        compose.setContent { LiDioTheme(dark = true) { LiDioApp(state) } }
        compose.onNodeWithText("Neu hinzugefügt").assertExists()
        compose.onNodeWithContentDescription("Suchen").assertExists()
        shot("20-modern-start")
        compose.onNodeWithContentDescription("Mediathek").performClick()
        compose.onNodeWithText("Interpreten").performClick()
        compose.waitUntil(3000) { compose.onAllNodesWithText("Kreis Quartett").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Zurück zu Mediathek").assertExists()
        compose.onAllNodesWithText("Mediathek").assertCountEquals(1)   // only the tab – the back button has no text in iOS 26
        shot("21-modern-interpreten")
        compose.onNodeWithContentDescription("Zurück zu Mediathek").performClick()
        compose.onNodeWithText("Interpreten").assertExists()
    }

    @Test
    fun albumPlaysFromTheTappedTitle() {
        val state = state()
        compose.setContent { LiDioTheme(dark = true) { LiDioApp(state) } }
        compose.onAllNodesWithContentDescription("Sinus & Söhne von Die Testtöne").onFirst().performClick()
        compose.waitUntil(3000) { compose.onAllNodesWithText("Wiedergabe").fetchSemanticsNodes().isNotEmpty() }   // loads in the background
        compose.onNodeWithText("Wiedergabe").assertExists()
        state.playback.preview(server.tracks[2], server.tracks.take(6))
        shot("05-album-dunkel")
    }

    @Test
    fun compilationShowsTheArtists() {
        val state = state()
        compose.setContent { LiDioTheme(dark = true) { LiDioApp(state) } }
        state.open(Route.AlbumPage("a5"))
        compose.waitUntil(3000) { compose.onAllNodesWithText("Kreis Quartett").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Kreis Quartett").onFirst().assertExists()
    }

    @Test
    fun nowPlaying() {
        val state = state()
        state.playback.preview(server.tracks[2], server.tracks.take(6))
        state.nowPlaying = true
        compose.setContent { LiDioTheme(dark = true) { LiDioApp(state) } }
        compose.onNodeWithContentDescription("Pause").assertExists()
        compose.onNodeWithContentDescription("Position im Titel").assertExists()
        shot("06-wiedergabe")
        compose.onNodeWithContentDescription("Als Nächstes").performClick()
        compose.onNodeWithText("Oktave").assertExists()
        shot("07-als-naechstes")
    }

    @Test
    fun search() {
        val state = state()
        compose.setContent { LiDioTheme(dark = false) { LiDioApp(state) } }
        compose.onNodeWithContentDescription("Suchen").performClick()
        compose.onNodeWithContentDescription("Suchbegriff").performTextInput("Kreis")
        compose.mainClock.advanceTimeBy(600)
        compose.waitUntil(3000) { compose.onAllNodesWithText("Kreis Quartett").fetchSemanticsNodes().isNotEmpty() }
        shot("08-suche-hell")
    }

    @Test
    fun miniPlayerOpensNowPlaying() {
        val state = state()
        state.playback.preview(server.tracks[0])
        compose.setContent { LiDioTheme(dark = false) { LiDioApp(state) } }
        compose.onNodeWithText("Erster Ton").assertExists()
        compose.onNodeWithText("Erster Ton").performClick()
        assertEquals(true, state.nowPlaying)
    }

    @Test
    fun importPastedList() {
        val state = state()
        state.tab = Tab.Library; state.stacks[Tab.Library] = listOf(Route.Library, Route.Playlists, Route.Import)
        compose.setContent { LiDioTheme(dark = false) { LiDioApp(state) } }
        compose.onNodeWithContentDescription("Liste").performTextInput("Die Testtöne – Quinte\nKreis Quartett – Spiralen\nQueen – Bohemian Rhapsody")
        compose.onNodeWithText("3 Titel mit dem Server abgleichen").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("2 gefunden").fetchSemanticsNodes().isNotEmpty() }   // "Spiralen" found via the artist
        compose.onNodeWithText("1 fehlen").assertExists()
        shot("09-import-hell")
        compose.onNodeWithText("Als Playlist anlegen (2 Titel)").performClick()
        compose.waitUntil(3000) { server.created.isNotEmpty() }
        assertEquals(listOf("Quinte", "Spirale"), server.created.single().second.map { it.title })
    }
}
