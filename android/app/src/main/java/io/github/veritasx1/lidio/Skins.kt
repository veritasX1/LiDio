package io.github.veritasx1.lidio

import io.github.veritasx1.lidio.i18n.tr

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/*
 * Card b23dc665: skins from the Winamp Skin Museum (skins.webamp.org, by Jordan Eldredge), chosen inside LiDio.
 * Winamp's own base skin is Nullsoft's and isn't shipped – it sits on top of the list and comes from the museum,
 * like every other skin. Nothing is asked of the museum before this page is opened.
 */

/** A skin in the museum: its md5 is its name there. */
data class MuseumSkin(val md5: String, val file: String, val download: String, val screenshot: String) {
    val name get() = if (md5 == Museum.CLASSIC) tr("Winamp Classic") else skinName(file)
}

fun skinName(file: String) = file.substringBeforeLast('.').replace('_', ' ').trim()

/** The museum's public GraphQL API (no account, no key). */
object Museum {
    /** Tests point this at their own server. */
    var api = "https://api.webamp.org/graphql"
    /** Winamp 2.91's own skin ("base-2.91.wsz"). */
    const val CLASSIC = "5e4f10275dcb1fb211d4a8b4f1bda236"
    private const val FIELDS = "md5 filename download_url screenshot_url nsfw"

    private fun query(q: String, variables: JSONObject = JSONObject()): JSONObject =
        JSONObject(Http.post(api, JSONObject().put("query", q).put("variables", variables).toString())).let { answer ->
            answer.optJSONArray("errors")?.let { throw ServerError("Skin-Museum: " + (it.optJSONObject(0)?.optString("message") ?: tr("Fehler"))) }
            answer.getJSONObject("data")
        }

    fun parse(o: JSONObject?): MuseumSkin? = o?.takeIf { !it.optBoolean("nsfw") && it.optString("md5").isNotEmpty() }?.let {
        MuseumSkin(it.getString("md5"), it.optString("filename"), it.optString("download_url"), it.optString("screenshot_url"))
    }

    /** The museum's own order (most loved first), page by page. NSFW skins are left out. */
    fun page(offset: Int, count: Int = 40): List<MuseumSkin> {
        val nodes = query("query(\$n:Int,\$o:Int){ skins(first:\$n, offset:\$o, sort: MUSEUM) { nodes { $FIELDS } } }",
            JSONObject().put("n", count).put("o", offset)).getJSONObject("skins").getJSONArray("nodes")
        return (0 until nodes.length()).mapNotNull { parse(nodes.optJSONObject(it)) }
    }

    fun search(text: String, count: Int = 60): List<MuseumSkin> {
        val nodes = query("query(\$q:String!,\$n:Int){ search_skins(query:\$q, first:\$n) { $FIELDS } }",
            JSONObject().put("q", text).put("n", count)).getJSONArray("search_skins")
        return (0 until nodes.length()).mapNotNull { parse(nodes.optJSONObject(it)) }
    }

    fun byMd5(md5: String): MuseumSkin? =
        parse(query("query(\$m:String!){ fetch_skin_by_md5(md5:\$m) { $FIELDS } }", JSONObject().put("m", md5)).optJSONObject("fetch_skin_by_md5"))
}

/** A skin on this device: the .wsz, its preview and its name, in LiDio's own folder. */
data class LocalSkin(val md5: String, val name: String, val path: String, val preview: String?)

object SkinLibrary {
    /** The built-in skin has no file; its preview lives in the app. */
    val BUILT_IN = LocalSkin("", tr("LiDio Graphit"), "", "asset:skins/lidio.png")

    fun folder(context: Context) = File(context.filesDir, "skins").apply { mkdirs() }

    fun all(context: Context): List<LocalSkin> = listOf(BUILT_IN) + (folder(context).listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
        .mapNotNull { f -> runCatching { decode(context, JSONObject(f.readText())) }.getOrNull() }
        .filter { File(it.path).exists() }.sortedBy { it.name.lowercase() }

    private fun decode(context: Context, o: JSONObject): LocalSkin {
        val md5 = o.getString("md5")
        val png = File(folder(context), "$md5.png")
        return LocalSkin(md5, o.getString("name"), File(folder(context), "$md5.wsz").path, if (png.exists()) png.toURI().toString() else null)
    }

    fun nameOf(context: Context, path: String): String =
        if (path.isEmpty()) BUILT_IN.name else all(context).firstOrNull { it.path == path }?.name ?: File(path).nameWithoutExtension

    fun installed(context: Context, md5: String): LocalSkin? = all(context).firstOrNull { it.md5 == md5 }

    /** Fetches the skin and its picture, and checks that it is a Winamp 2 skin before keeping it. */
    suspend fun install(context: Context, skin: MuseumSkin): LocalSkin = withContext(Dispatchers.IO) {
        installed(context, skin.md5)?.let { return@withContext it }
        val bytes = download(skin.download, 20L shl 20)
        val parsed = runCatching { Skin.read(skin.name, bytes.inputStream(), null) }.getOrNull()
        if (parsed?.sheet("main") == null) throw ServerError(tr("„{name}“ ist kein Skin für Winamp 2.", "name" to skin.name))
        val dir = folder(context)
        File(dir, "${skin.md5}.wsz").writeBytes(bytes)
        runCatching { File(dir, "${skin.md5}.png").writeBytes(download(skin.screenshot, 4L shl 20)) }
        File(dir, "${skin.md5}.json").writeText(JSONObject().put("md5", skin.md5).put("name", skin.name).put("file", skin.file).toString())
        decode(context, JSONObject().put("md5", skin.md5).put("name", skin.name))
    }

    fun remove(context: Context, skin: LocalSkin) {
        if (skin.md5.isEmpty()) return
        listOf("wsz", "png", "json").forEach { File(folder(context), "${skin.md5}.$it").delete() }
    }

    private fun download(url: String, limit: Long): ByteArray {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 10_000; connection.readTimeout = 30_000
        try {
            if (connection.responseCode != 200) throw ServerError(tr("Das Skin-Museum antwortet nicht ({responseCode}).", "responseCode" to connection.responseCode))
            val out = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > limit) throw ServerError(tr("Die Datei ist zu groß für einen Skin."))
                }
            }
            return out.toByteArray()
        } catch (e: java.io.IOException) {
            throw ServerError(tr("Keine Verbindung zum Skin-Museum."))
        } finally { connection.disconnect() }
    }
}

// --- the page -------------------------------------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SkinsScreen(state: AppState) {
    val ink = Ink
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val w = state.winamp
    var local by remember { mutableStateOf(SkinLibrary.all(context)) }
    var classic by remember { mutableStateOf<MuseumSkin?>(null) }
    val museum = remember { mutableStateListOf<MuseumSkin>() }
    var query by remember { mutableStateOf("") }
    var asked by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var end by remember { mutableStateOf(false) }
    var pages by remember { mutableIntStateOf(0) }
    var chosen by remember { mutableStateOf<MuseumSkin?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<LocalSkin?>(null) }

    fun use(skin: LocalSkin) { w.skin = skin.path; w.save() }
    fun more() {
        if (loading || end) return
        loading = true; error = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { if (asked.isEmpty()) Museum.page(pages * 40) else Museum.search(asked) } }
                .onSuccess { found ->
                    museum.addAll(found.filter { f -> f.md5 != Museum.CLASSIC && museum.none { it.md5 == f.md5 } })
                    pages++; if (asked.isNotEmpty() || found.isEmpty()) end = true
                }
                .onFailure { error = it.message ?: tr("Keine Verbindung zum Skin-Museum.") }
            loading = false
        }
    }
    LaunchedEffect(Unit) { runCatching { withContext(Dispatchers.IO) { Museum.byMd5(Museum.CLASSIC) } }.onSuccess { classic = it } }
    LaunchedEffect(query) {
        delay(400)
        if (query.trim() == asked && pages > 0) return@LaunchedEffect
        asked = query.trim(); museum.clear(); pages = 0; end = false; loading = false; more()
    }

    Column(Modifier.fillMaxSize()) {
        NavBar(state, tr("Skins"))
        LazyColumn(Modifier.fillMaxSize().background(ink.grouped), contentPadding = chromePadding()) {
            sectionLabel(tr("AUF DIESEM GERÄT"))
            grid(local) { skin ->
                SkinTile(skin.name, skin.preview, selected = w.skin == skin.path,
                    note = if (skin.md5.isEmpty()) "Eingebaut" else null,
                    onLongClick = if (skin.md5.isNotEmpty()) ({ removing = skin }) else null) { use(skin) }
            }
            if (SkinLibrary.installed(context, Museum.CLASSIC) == null) {
                sectionLabel("WINAMP CLASSIC")
                item {
                    val c = classic
                    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ink.card)
                        .combinedClickable(role = Role.Button, enabled = c != null && busy == null) { chosen = c }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Cover(c?.screenshot, Modifier.width(96.dp).aspectRatio(275f / 348f), 4.dp)
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Label(tr("Winamp Classic"), 17f, 600)
                            Label(tr("Der Original-Skin von Winamp 2.91 (Nullsoft). Wird aus dem Skin-Museum geladen."), 13f,
                                color = ink.secondary, lines = 4)
                            Label(if (busy == Museum.CLASSIC) tr("Wird geladen …") else tr("Laden"), 15f, 600, ink.tint, Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
            sectionLabel("SKIN-MUSEUM")
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(ink.fill.copy(alpha = if (ink.dark) 0.5f else 0.25f)).padding(horizontal = 8.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    SymbolIcon(Symbol.Search, ink.secondary, 18.dp)
                    Box(Modifier.weight(1f).padding(start = 6.dp)) {
                        if (query.isEmpty()) Label(tr("Über 90 000 Skins durchsuchen"), 17f, color = ink.secondary)
                        BasicTextField(query, { query = it }, singleLine = true, textStyle = style(17f, color = ink.label), cursorBrush = SolidColor(ink.tint),
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = tr("Skin suchen") })
                    }
                    if (query.isNotEmpty()) Box(Modifier.combinedClickable(role = Role.Button, onClickLabel = tr("Löschen")) { query = "" }) {
                        SymbolIcon(Symbol.Close, ink.secondary, 16.dp)
                    }
                }
            }
            grid(museum.toList()) { skin ->
                SkinTile(skin.name, skin.screenshot, selected = false, note = if (busy == skin.md5) tr("Wird geladen …") else null) { chosen = skin }
            }
            item {
                LaunchedEffect(museum.size, asked) { more() }   // the end of the list is in sight: the next page
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    when {
                        error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Label(error ?: "", 15f, color = ink.secondary, lines = 3, align = TextAlign.Center)
                            Label(tr("Erneut versuchen"), 17f, color = ink.tint, modifier = Modifier.padding(top = 8.dp)
                                .combinedClickable(role = Role.Button) { error = null; more() })
                        }
                        loading -> Label(tr("Wird geladen …"), 15f, color = ink.secondary)
                        end && museum.isEmpty() -> Label(tr("Keine Skins gefunden."), 15f, color = ink.secondary)
                        else -> Spacer(Modifier.height(1.dp))
                    }
                }
            }
            item {
                Label(tr("Die Skins stammen aus dem Winamp Skin Museum (skins.webamp.org) und gehören ihren Gestalterinnen und Gestaltern. ") +
                    tr("LiDio fragt das Museum nur, solange diese Seite offen ist. Halte einen geladenen Skin gedrückt, um ihn zu löschen."),
                    13f, color = ink.secondary, lines = 6, modifier = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 32.dp))
            }
        }
    }

    chosen?.let { skin ->
        SkinSheet(skin, onClose = { chosen = null }) {
            chosen = null; busy = skin.md5
            scope.launch {
                runCatching { SkinLibrary.install(context, skin) }
                    .onSuccess { use(it); local = SkinLibrary.all(context); state.notice = tr("„{name}“ ist jetzt dein Skin.", "name" to it.name) }
                    .onFailure { state.notice = it.message ?: tr("Der Skin ließ sich nicht laden.") }
                busy = null
            }
        }
    }
    removing?.let { skin ->
        Ask(tr("„{name}“ löschen?", "name" to skin.name), tr("Der Skin wird von diesem Gerät entfernt. Du kannst ihn jederzeit wieder laden."), tr("Löschen"),
            onYes = { if (w.skin == skin.path) use(SkinLibrary.BUILT_IN); SkinLibrary.remove(context, skin); local = SkinLibrary.all(context); removing = null },
            onNo = { removing = null })
    }
}

private fun LazyListScope.sectionLabel(text: String) = item {
    Label(text, 13f, color = Ink.secondary, modifier = Modifier.padding(start = 32.dp, top = 20.dp, bottom = 8.dp))
}

/** Two pictures a row (three on wide screens would want a grid; the phone is the measure here). */
private fun <T> LazyListScope.grid(list: List<T>, tile: @Composable (T) -> Unit) {
    items(list.chunked(2)) { row ->
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { Box(Modifier.weight(1f)) { tile(it) } }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SkinTile(name: String, picture: String?, selected: Boolean, note: String? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val ink = Ink
    Column(Modifier.combinedClickable(role = Role.Button, onLongClick = onLongClick, onLongClickLabel = if (onLongClick != null) tr("Löschen") else null, onClick = onClick)
        .semantics { contentDescription = name; this.selected = selected }) {
        Box {
            Cover(picture, Modifier.fillMaxWidth().aspectRatio(275f / 348f)
                .then(if (selected) Modifier.border(3.dp, ink.tint, RoundedCornerShape(6.dp)) else Modifier), 6.dp)
            if (selected) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(RoundedCornerShape(50)).background(ink.tint),
                contentAlignment = Alignment.Center) { SymbolIcon(Symbol.Check, Color.White, 16.dp, weight = 2.6f) }
        }
        Label(name, 15f, 500, modifier = Modifier.padding(top = 6.dp))
        note?.let { Label(it, 13f, color = ink.secondary) }
    }
}

/** The skin big, and the one question: use it? */
@Composable
private fun SkinSheet(skin: MuseumSkin, onClose: () -> Unit, onUse: () -> Unit) {
    val ink = Ink
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(14.dp)).background(ink.elevated), horizontalAlignment = Alignment.CenterHorizontally) {
            Cover(skin.screenshot, Modifier.padding(top = 20.dp).width(220.dp).aspectRatio(275f / 348f), 4.dp)
            Label(skin.name, 17f, 600, modifier = Modifier.padding(16.dp), lines = 2, align = TextAlign.Center)
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(ink.separator))
            ListRow(tr("Laden und verwenden"), titleColor = ink.tint, onClick = onUse)
            ListRow(tr("Abbrechen"), separator = false, onClick = onClose)
        }
    }
}
