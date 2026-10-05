package io.github.veritasx1.lidio

import android.content.ContentValues
import android.content.Context
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Card 5ef5e3c8 – help like LiCida's: a German guide (in the app and as PDF), a tour on first start, hints per help level.
 *  Written for someone who never used such an app ("Tante Erna"). The privacy chapter names exactly the network accesses
 *  this variant makes – the public LiDio and LiDio privat differ. */
data class Chapter(val title: String, val paragraphs: List<String>)

object Guide {
    fun chapters(): List<Chapter> = buildList {
        add(Chapter("Was LiDio macht", listOf(
            "LiDio spielt deine eigene Musik – von deinem Musikserver zu Hause (Emby, Jellyfin oder Navidrome) oder aus Ordnern auf diesem Handy. " +
                "Es sieht aus und funktioniert wie die Musik-App auf dem iPhone, nur ohne Abo und ohne Apple.",
            "LiDio kostet nichts, zeigt keine Werbung und verkauft nichts. Es gibt kein LiDio-Konto.")))
        add(Chapter("Server verbinden", listOf(
            "Beim ersten Start trägst du die Adresse deines Servers ein, dazu Benutzername und Passwort – dieselben wie in der Weboberfläche des Servers.",
            "„Unterwegs“ ist eine zweite Adresse für außer Haus (zum Beispiel deine goip.de-Adresse). Im WLAN nimmt LiDio die schnelle Adresse, sonst die zweite – von selbst.",
            "Weitere Server oder Ordner auf dem Handy fügst du unter Mediathek → Server-Symbol oben rechts hinzu.")))
        add(Chapter("Start, Mediathek, Suchen", listOf(
            "Unten schwebt die Leiste: Start (Zuletzt gespielt, Neu hinzugefügt, Mixe für dich), Mediathek (Playlists, Interpreten, Alben, Titel, Lieblingstitel) " +
                "und rechts die Lupe für die Suche. Beim Herunterscrollen wird die Leiste klein; hochscrollen holt sie zurück.",
            "Halte ein Album oder eine Playlist gedrückt: Wiedergabe, Zufall, Als Nächstes, Zur Playlist hinzufügen oder Anheften. Angeheftetes steht oben in der Mediathek.",
            "Einen Titel nach rechts wischen spielt ihn als Nächstes, nach links kommt er ans Ende der Warteschlange. Gedrückt halten öffnet sein Menü.")))
        add(Chapter("Jetzt läuft", listOf(
            "Tippe auf den kleinen Player unten. Nach unten wischen schließt „Jetzt läuft“ wieder.",
            "Neben dem Titel: ★ macht ihn zum Lieblingstitel (auf deinem Server gespeichert), ••• öffnet Teilen, Zum Album und Ausgabegerät (Bluetooth, Lautsprecher).",
            "Unten links die Sprechblase: der Liedtext, falls es einen gibt. In der Mitte der Blitz: die Winamp-Ansicht. Rechts: die Warteschlange mit Zufall, " +
                "Wiederholen und Autoplay (∞ – danach geht es mit ähnlicher Musik weiter). Titel ziehst du am Griff ≡ um, nach links wischen entfernt sie.")))
        add(Chapter("Playlists", listOf(
            "Playlists legst du über „Zur Playlist hinzufügen“ an, oder du importierst eine Liste (Mediathek → Playlists → +). LiDio gleicht sie mit deinem Server ab.",
            "Titel, die dem Server fehlen, stehen grau an ihrer Stelle.",
            "Oben rechts auf einer Playlist: Freigeben (für alle auf dem Server oder einzelne Personen, zum Hören oder Bearbeiten) und ↓ zum Laden aufs Handy.")))
        add(Chapter("Ohne Netz hören", listOf(
            "↓ an einem Album oder einer Playlist lädt sie aufs Handy. Was du hörst, behält LiDio auf Wunsch ebenfalls („Gehörtes behalten“). " +
                "Beides findest du unter Mediathek → Geladen; entfernen per langem Druck.")))
        Variant.helpChapter()?.let { add(it) }
        add(Chapter("Winamp", listOf(
            "Der Blitz in „Jetzt läuft“ schaltet in die Winamp-2-Ansicht mit Equalizer, Spektrum und Playlist; der Blitz oben links im Winamp-Fenster wieder zurück.",
            "Skins wählst du unter Einstellungen → Darstellung → Winamp-Skin, auch aus dem Skin-Museum mit über 90 000 Skins.")))
        add(Chapter("Datenschutz", privacy()))
    }

    /** Exactly the connections LiDio makes – nothing else leaves the phone. */
    fun privacy(): List<String> = buildList {
        add("LiDio sammelt nichts über dich, hat keine Werbung, keine Statistik und keine Tracker. Es gibt keine Käufe und keine Abos.")
        add("Verbindungen gehen nur dorthin, wo du sie auslöst: zu deinem eigenen Server (Musik, Cover, Playlists, Favoriten).")
        add("Zum Skin-Museum (skins.webamp.org) nur, solange die Skin-Auswahl offen ist. Zu Deezer nur, wenn du die Deezer-Suche einschaltest; " +
            "zu Spotify nur mit deiner eigenen Spotify-App (Client-ID).")
        add(if (Variant.PRIVATE) "Liedtexte: Titel und Interpret gehen an lrclib.net (frei, ohne Konto), wenn dein Server keinen Text hat."
            else "Liedtexte: nur wenn du „Liedtexte aus dem Netz“ einschaltest, gehen Titel und Interpret an lrclib.net (frei, ohne Konto).")
        Variant.text("datenschutz").takeIf { it.isNotEmpty() }?.let { add(it) }
        add("Dein Server-Zugang liegt verschlüsselt auf diesem Gerät. Sicherungen von Android enthalten LiDio nicht.")
    }

    /** The guide as a PDF (A4) – same text as in the app. */
    fun pdf(context: Context): ByteArray {
        val document = PdfDocument()
        val width = 595; val height = 842; val margin = 64f
        val inter = runCatching { androidx.core.content.res.ResourcesCompat.getFont(context, R.font.inter) }.getOrNull() ?: Typeface.DEFAULT
        fun paint(size: Float, bold: Boolean, grey: Boolean = false) = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(inter, if (bold) Typeface.BOLD else Typeface.NORMAL); textSize = size
            color = if (grey) 0xFF6E6E73.toInt() else 0xFF1C1C1E.toInt()
        }
        fun layout(text: String, p: TextPaint) = StaticLayout.Builder.obtain(text, 0, text.length, p, (width - 2 * margin).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.2f).build()
        var number = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        val name = if (Variant.PRIVATE) "LiDio privat" else "LiDio"
        fun finish() { page?.let { p ->
            if (number > 1) p.canvas.drawText("$name – Anleitung · Seite $number", margin, height - 32f, paint(8f, false, grey = true))
            document.finishPage(p) } }
        fun newPage() { finish(); number++; page = document.startPage(PdfDocument.PageInfo.Builder(width, height, number).create()); y = margin }
        fun place(text: String, p: TextPaint, before: Float) {
            val block = layout(text, p)
            if (page == null || y + before + block.height > height - margin) newPage() else y += before
            val canvas = page!!.canvas
            canvas.save(); canvas.translate(margin, y); block.draw(canvas); canvas.restore()
            y += block.height
        }
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
        place(name, paint(30f, true), 120f)
        place("Anleitung · Version $version", paint(11f, false, grey = true), 24f)
        place("Ohne Abo, ohne Werbung, ohne Datensammlung.", paint(11f, false, grey = true), 6f)
        chapters().forEachIndexed { index, chapter ->
            if (index == 0) newPage()
            place("${index + 1}  ${chapter.title}", paint(16f, true), if (y > margin) 26f else 0f)
            chapter.paragraphs.forEach { place(it, paint(11f, false), 8f) }
        }
        finish()
        return java.io.ByteArrayOutputStream().also { document.writeTo(it); document.close() }.toByteArray()
    }

    /** Into Downloads/LiDio (MediaStore – no storage permission). */
    fun savePdf(context: Context, bytes: ByteArray): Uri? = runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, if (Variant.PRIVATE) "LiDio-privat-Anleitung.pdf" else "LiDio-Anleitung.pdf")
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/LiDio")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        uri
    }.getOrNull()
}

/** How much LiDio explains by itself: hints every time, once, or never (LiCida's help levels). */
enum class HelpLevel(val label: String, val times: Int) { Always("Immer", Int.MAX_VALUE), Once("Einmal", 1), Off("Aus", 0) }

object Help {
    private fun prefs(context: Context) = context.getSharedPreferences("hilfe", Context.MODE_PRIVATE)
    fun level(context: Context) = runCatching { HelpLevel.valueOf(prefs(context).getString("stufe", "")!!) }.getOrDefault(HelpLevel.Once)
    fun setLevel(context: Context, l: HelpLevel) { prefs(context).edit().putString("stufe", l.name).apply() }
    /** Shows this hint now? Counts it as shown. */
    fun hint(context: Context, key: String): Boolean {
        val shown = prefs(context).getInt("hinweis_$key", 0)
        if (shown >= level(context).times) return false
        prefs(context).edit().putInt("hinweis_$key", shown + 1).apply(); return true
    }
    var tourOffered: Boolean = false
    fun tourOffered(context: Context) = prefs(context).getBoolean("rundgangAngeboten", false)
    fun markTourOffered(context: Context) = prefs(context).edit().putBoolean("rundgangAngeboten", true).apply()
}

/** The guide page: tour, chapters (each its own page), PDF, help level. */
@Composable
fun GuideScreen(state: AppState) {
    val ink = Ink
    val context = LocalContext.current
    var open by remember { mutableStateOf<Int?>(null) }
    var level by remember { mutableStateOf(Help.level(context)) }
    val chapters = remember { Guide.chapters() }
    androidx.activity.compose.BackHandler(enabled = open != null) { open = null }
    Column(Modifier.fillMaxSize()) {
        NavBar(state)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = chromePadding()) {
            val chapter = open?.let { chapters[it] }
            if (chapter != null) {
                largeTitle(chapter.title, topInset = false)
                chapter.paragraphs.forEach { p -> item { Label(p, 17f, lines = 30, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)) } }
                val next = open!! + 1
                if (next < chapters.size) item {
                    Label("Weiter: ${chapters[next].title} ›", 17f, color = ink.tint, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp)
                        .clickable(role = Role.Button) { open = next }.padding(vertical = 6.dp))
                }
                item { Label("‹ Alle Kapitel", 17f, color = ink.tint, modifier = Modifier.padding(start = 20.dp, top = 12.dp).clickable(role = Role.Button) { open = null }.padding(vertical = 6.dp)) }
            } else {
                largeTitle("Anleitung", topInset = false)
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(10.dp)).background(ink.card)) {
                        ListRow("Rundgang starten", onClick = { state.tour = 0; state.stacks.clear(); state.tab = Tab.Start }, titleColor = ink.tint, separator = false)
                    }
                    Label("Zeigt dir die wichtigsten Knöpfe – eine Minute.", 13f, color = ink.secondary, modifier = Modifier.padding(start = 32.dp, end = 32.dp))
                }
                item { Label("KAPITEL", 13f, color = ink.secondary, modifier = Modifier.padding(start = 32.dp, top = 24.dp, bottom = 6.dp)) }
                item {
                    Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(10.dp)).background(ink.card)) {
                        chapters.forEachIndexed { i, c -> ListRow("${i + 1}. ${c.title}", onClick = { open = i }, separator = i < chapters.lastIndex, trailing = { Chevron() }) }
                    }
                }
                item {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp).clip(RoundedCornerShape(10.dp)).background(ink.card)) {
                        ListRow("Anleitung als PDF sichern", onClick = {
                            val uri = Guide.savePdf(context, Guide.pdf(context))
                            state.notice = if (uri != null) "Gesichert in „Downloads/LiDio“." else "Das PDF ließ sich nicht sichern."
                        }, titleColor = ink.tint, separator = false)
                    }
                    Label("Zum Ausdrucken oder Lesen am Computer.", 13f, color = ink.secondary, modifier = Modifier.padding(start = 32.dp, end = 32.dp))
                }
                item { Label("HINWEISE", 13f, color = ink.secondary, modifier = Modifier.padding(start = 32.dp, top = 24.dp, bottom = 6.dp)) }
                item {
                    Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(10.dp)).background(ink.card).padding(vertical = 6.dp)) {
                        Segmented(HelpLevel.entries.map { it.label }, level.ordinal, Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            level = HelpLevel.entries[it]; Help.setLevel(context, level)
                        }
                    }
                    Label("Kurze Hinweise beim ersten Benutzen, etwa „nach unten wischen schließt“ – immer, einmal oder nie.", 13f, color = ink.secondary, lines = 3,
                        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp, bottom = 24.dp))
                }
            }
        }
    }
}

/** Where the tour's targets are on screen. */
val LocalTourAnchors = staticCompositionLocalOf<SnapshotStateMap<String, Rect>?> { null }

@Composable
fun Modifier.tourAnchor(name: String): Modifier {
    val anchors = LocalTourAnchors.current ?: return this
    return this.onGloballyPositioned { anchors[name] = it.boundsInRoot() }
}

data class TourStep(val anchor: String?, val title: String, val text: String)

object Tour {
    fun steps(): List<TourStep> = listOfNotNull(
        TourStep(null, "Willkommen bei ${if (Variant.PRIVATE) "LiDio privat" else "LiDio"}", "Deine Musik wie auf dem iPhone – ohne Abo. Ein kurzer Rundgang zeigt dir die wichtigsten Stellen."),
        TourStep("start", "Start", "Zuletzt gespielt, Neues und Mixe für dich."),
        TourStep("mediathek", "Mediathek", "Playlists, Interpreten, Alben, Titel und Lieblingstitel. Oben rechts das Server-Symbol: Server und Einstellungen."),
        TourStep("suchen", "Suchen", Variant.text("rundgang-suchen").ifEmpty { "Interpreten, Alben, Titel und Playlists – vor dem Tippen Genres zum Stöbern." }),
        TourStep("miniplayer", "Was gerade läuft", "Antippen öffnet „Jetzt läuft“ mit Liedtext, Warteschlange und der Winamp-Ansicht (Blitz)."),
        TourStep(null, "Gesten", "Album oder Playlist gedrückt halten öffnet ein Menü. Titel nach rechts wischen: als Nächstes, nach links: ans Ende. " +
            "„Jetzt läuft“ nach unten wischen schließt es."),
        TourStep(null, "Hilfe", "Die ganze Anleitung findest du unter Mediathek → Server-Symbol → Anleitung – auch als PDF."),
    )
}

/** The tour: everything dimmed except what it is about, a card beside it (iOS-style coach mark). */
@Composable
fun TourOverlay(index: Int, anchors: Map<String, Rect>, onNext: () -> Unit, onSkip: () -> Unit) {
    val ink = Ink
    val steps = remember { Tour.steps() }
    val step = steps[index]
    val hole = step.anchor?.let { anchors[it] }
    val density = LocalDensity.current
    var screenHeight by remember { mutableStateOf(0) }
    var cardHeight by remember { mutableStateOf(0) }
    Box(Modifier.fillMaxSize().clickable(enabled = true, onClick = {}).onSizeChanged { screenHeight = it.height }) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            drawRect(Color.Black.copy(alpha = 0.72f))
            hole?.let { r ->
                val pad = 8.dp.toPx()
                val radius = CornerRadius(minOf(r.width, r.height) / 2 + pad)
                drawRoundRect(Color.Black, Offset(r.left - pad, r.top - pad), androidx.compose.ui.geometry.Size(r.width + 2 * pad, r.height + 2 * pad), radius, blendMode = BlendMode.Clear)
                drawRoundRect(ink.tint, Offset(r.left - pad, r.top - pad), androidx.compose.ui.geometry.Size(r.width + 2 * pad, r.height + 2 * pad), radius, style = Stroke(2.dp.toPx()))
            }
        }
        val gap = with(density) { 20.dp.toPx() }
        val top = when {
            hole == null -> (screenHeight - cardHeight) / 2f
            hole.center.y < screenHeight / 2 -> hole.bottom + gap
            else -> hole.top - gap - cardHeight
        }
        Column(Modifier.align(Alignment.TopCenter).offset { IntOffset(0, top.roundToInt().coerceAtLeast(0)) }.padding(horizontal = 24.dp)
            .widthIn(max = 420.dp).clip(RoundedCornerShape(16.dp)).background(ink.elevated).onSizeChanged { cardHeight = it.height }.padding(18.dp)) {
            Label(step.title, 20f, 700, modifier = Modifier.semantics { heading() })
            Label(step.text, 15f, lines = 8, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Label("${index + 1} von ${steps.size}", 13f, color = ink.secondary, tabular = true, modifier = Modifier.weight(1f))
                if (index < steps.lastIndex) Label("Überspringen", 17f, color = ink.secondary, modifier = Modifier.padding(end = 16.dp).clickable(role = Role.Button, onClick = onSkip).padding(4.dp))
                Label(if (index == steps.lastIndex) "Fertig" else "Weiter", 17f, 600, ink.tint, Modifier.clickable(role = Role.Button, onClick = onNext).padding(4.dp))
            }
        }
    }
}

/** A short hint at the top that goes away by itself (LiCida's hint pill). */
@Composable
fun HintPill(text: String, modifier: Modifier = Modifier) {
    Box(modifier.padding(horizontal = 24.dp).clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Label(text, 15f, 500, Color.White, lines = 3)
    }
}
