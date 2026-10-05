package io.github.veritasx1.lidio

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Card b23dc665: skins from the museum – list, search, NSFW left out, fetch + check + use. Pictures with -Pshots=<folder>. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkinsTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val out = System.getProperty("lidio.shots")
    private lateinit var http: TinyHttp
    private val wsz = java.util.Base64.getEncoder().encodeToString(context.assets.open("skins/lidio.wsz").readBytes())

    private fun node(md5: String, name: String, nsfw: Boolean = false) =
        """{"md5":"$md5","filename":"$name","download_url":"http://127.0.0.1:${http.port}/skins/$md5.wsz","screenshot_url":"http://127.0.0.1:${http.port}/shots/$md5.png","nsfw":$nsfw}"""

    @Before
    fun start() {
        http = TinyHttp { path ->
            when {
                path == "/graphql" -> """{"data":{
                    "skins":{"nodes":[${node(Museum.CLASSIC, "base-2.91.wsz")},${node("a1", "Blue_Steel.wsz")},${node("a2", "Rude.wsz", nsfw = true)},${node("a3", "Purple_Haze_v2.wsz")}]},
                    "search_skins":[${node("a1", "Blue_Steel.wsz")}],
                    "fetch_skin_by_md5":${node(Museum.CLASSIC, "base-2.91.wsz")}}}"""
                path == "/skins/broken.wsz" -> "kein zip"
                path.startsWith("/skins/") -> "base64:$wsz"
                else -> null
            }
        }
        Museum.api = "http://127.0.0.1:${http.port}/graphql"
        SkinLibrary.folder(context).deleteRecursively()
        context.getSharedPreferences("winamp", 0).edit().clear().commit()
        context.getSharedPreferences("konten", 0).edit().clear().commit()
    }

    @After
    fun stop() { http.close() }

    @Test
    fun museumListLeavesOutNsfwAndNamesNicely() {
        val page = Museum.page(0)
        assertEquals(listOf(Museum.CLASSIC, "a1", "a3"), page.map { it.md5 })
        assertEquals("Purple Haze v2", page[2].name)
        assertEquals("Blue Steel", Museum.search("blue").single().name)
        assertEquals("Winamp Classic", Museum.byMd5(Museum.CLASSIC)?.name)
    }

    @Test
    fun installKeepsTheSkinAndRefusesWhatIsNone() = runBlocking {
        val skin = Museum.page(0)[1]
        val local = SkinLibrary.install(context, skin)
        assertTrue(File(local.path).exists())
        assertEquals("Blue Steel", SkinLibrary.nameOf(context, local.path))
        assertEquals(listOf("LiDio Graphit", "Blue Steel"), SkinLibrary.all(context).map { it.name })
        // Not a zip: refused, nothing left behind.
        val broken = MuseumSkin("broken", "Kaputt.wsz", "http://127.0.0.1:${http.port}/skins/broken.wsz", "")
        val error = runCatching { SkinLibrary.install(context, broken) }.exceptionOrNull()
        assertTrue(error?.message ?: "", error?.message?.contains("kein Skin") == true)
        assertNull(SkinLibrary.installed(context, "broken"))
        SkinLibrary.remove(context, local)
        assertEquals(1, SkinLibrary.all(context).size)
    }

    @Test
    fun pageChoosesClassic() {
        val accounts = Accounts(context)
        accounts.save(Account("test", ServerKind.Navidrome, "http://test", "test", "geheim", "Navidrome (Test)"))
        val state = AppState(accounts, Playback(context)) { FakeServer() }.apply { probe = { _, _ -> true } }
        state.winamp.on = true
        compose.setContent { LiDioTheme(dark = true) { SkinsScreen(state) } }
        compose.waitUntil(5000) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Purple Haze v2")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Winamp Classic").assertExists()
        compose.onNodeWithContentDescription("LiDio Graphit").assertExists()
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Rude")).fetchSemanticsNodes().size)
        if (out != null) compose.onAllNodes(androidx.compose.ui.test.isRoot()).onFirst().captureRoboImage("$out/s1-skins.png")
        compose.onNodeWithText("Winamp Classic").performClick()
        compose.onNodeWithText("Laden und verwenden").performClick()
        compose.waitUntil(5000) { state.winamp.skin.isNotEmpty() }
        assertNotNull(SkinLibrary.installed(context, Museum.CLASSIC))
        assertEquals("Winamp Classic", SkinLibrary.nameOf(context, state.winamp.skin))
    }
}
