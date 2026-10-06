package io.github.veritasx1.lidio

import io.github.veritasx1.lidio.i18n.tr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Sources on the internet (card 1843f577). Their names, colours and addresses come from the variant (Variant.source):
 *  the public LiDio knows only the public playlists of Deezer and Spotify; everything else is empty there. */
enum class WebSource(val searchable: Boolean) {
    /** Card 768db4ae: all searchable sources side by side. */
    All(true), Music(true), Video(true), Sounds(true),
    /** Playlists only (card 146a2d74). */
    Deezer(true), Spotify(true),
    Bandcamp(false), Vimeo(false), Mixcloud(false), Audiomack(false), Dailymotion(false), Archive(false), Other(false);

    val label: String get() = Variant.source(this).label
    val color: Color get() = Variant.source(this).color
    val hosts: List<String> get() = Variant.source(this).hosts

    companion object {
        /** The source a link belongs to – by its host (the more specific host first). */
        fun of(link: String): WebSource {
            val host = runCatching { Uri.parse(link.trim()).host?.lowercase() }.getOrNull() ?: return Other
            return entries.firstOrNull { s -> s.hosts.any { host == it || host.endsWith(".$it") } } ?: Other
        }
    }
}

/** What a variant says about a source. */
data class SourceInfo(val label: String, val color: Color, val hosts: List<String> = emptyList())

/** A title (or a whole list) found on the internet. */
data class WebHit(val url: String, val title: String, val artist: String, val album: String? = null, val duration: Int = 0,
                  val thumbnail: String? = null, val source: WebSource = WebSource.of(url), val isList: Boolean = false, val count: Int = 0)

/** The same title under another address is still the same title (the variant knows the sites' short forms). */
val WebHit.key: String get() = Variant.key(url)

/** What "LiDio privat" can do with the internet; made by Variant.engine(). Every call blocks – only off the main thread. */
interface WebEngine {
    /** Titles for a search at one source (the ones with searchable = true). */
    fun search(source: WebSource, query: String, count: Int = 20): List<WebHit>
    /** What is behind a link: one title, or a list's name and its titles. */
    fun open(link: String): Pair<String, List<WebHit>>
    /** Loads one title into the folder; progress 0..1 and a short German status. Returns the file. */
    fun download(hit: WebHit, folder: File, id: String, progress: (Float, String) -> Unit): File
    fun cancel(id: String)
    /** Apple Music's music videos, rebuilt (card d894cc42): the video to a title. */
    fun musicVideo(title: String, artist: String): WebHit?
    /** What the player streams for a video: one or two parts (picture, sound), each with the headers the site wants. */
    fun stream(hit: WebHit): List<StreamPart>
    /** Only the sound of a title, to listen before loading (card 6c9ba022). */
    fun audio(url: String): StreamPart
    /** Brings the loader up to date – the sites change often. Returns its version. */
    fun update(): String
    fun version(): String
}

data class StreamPart(val url: String, val headers: Map<String, String>)

/** One title in the queue, with everything the list shows: where from, how far, what went wrong. */
data class WebJob(val id: String, val hit: WebHit, val state: State = State.Waiting, val progress: Float = 0f, val note: String = "",
                  val file: String? = null, val at: Long = System.currentTimeMillis()) {
    enum class State { Waiting, Loading, Converting, Done, Failed }
}

/** The download queue of "LiDio privat": one title after the other, in a foreground service (it goes on in the background,
 *  Android shows a notification with the current title and its source). Kept in a file, so it survives a restart. */
object WebDownloads {
    var jobs by mutableStateOf<List<WebJob>>(emptyList()); private set
    private var loaded = false
    /** Card 1092956d (Olaf 06.10.2026: "Downloadgrenze wieder auf max 2 downloads setzen"): two titles at a time. */
    const val PARALLEL = 2
    private val worker = Executors.newFixedThreadPool(PARALLEL)
    private val running = java.util.concurrent.atomic.AtomicInteger(0)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    const val CHANNEL = "netz"

    /** The loaded file of a title from the internet, if it is on this phone (Olaf 05.10.2026: play the file, not the stream). */
    fun fileFor(track: Track): File? {
        val key = Variant.key(track.path ?: Variant.watch(track.id))
        return index().done[key]?.file?.let(::File)?.takeIf { it.isFile }
    }

    /** The last job for a title (any state), without walking the whole list. */
    fun jobFor(key: String): WebJob? = index().last[key]

    /** Olaf 06.10.2026: "wenn ich eine playlist herunterlade … die performance geht komplett in den keller". Every row asks
     *  "is this title loaded / loading?" – with ~500 jobs, walking the list per row and per change froze the app. The answers come
     *  from an index, built again only when the list itself changes (a title starts, finishes or fails – not on every percent). */
    private class Index(val of: List<WebJob>, val last: Map<String, WebJob>, val done: Map<String, WebJob>)
    @Volatile private var cache = Index(emptyList(), emptyMap(), emptyMap())
    private fun index(): Index {
        val all = jobs
        cache.takeIf { it.of === all }?.let { return it }
        val last = HashMap<String, WebJob>(all.size * 2); val done = HashMap<String, WebJob>(all.size * 2)
        for (j in all) { last[j.hit.key] = j; if (j.state == WebJob.State.Done) done[j.hit.key] = j }
        return Index(all, last, done).also { cache = it }
    }

    /** Progress of the running titles, 0..1 – apart from the list, so a percent step redraws only the ring that shows it. */
    val progress = androidx.compose.runtime.mutableStateMapOf<String, Float>()
    private val notes = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun noteOf(job: WebJob): String = notes[job.id] ?: job.note

    /** Card 66bd0f1b (Olaf 06.10.2026): a title loaded from the internet that the own server has by now (LiDio-Lader) is a
     *  server title – and its file on the phone is the same song. Matched by title and artist (and length when both know
     *  it), so the server title shows the phone and plays the file; the user notices only the symbol. */
    @Volatile private var byTitle: Pair<List<WebJob>, Map<String, List<WebJob>>> = emptyList<WebJob>() to emptyMap()
    fun copyOf(track: Track): File? {
        if (track.title.isBlank()) return null
        val title = Matcher.normal(track.title)
        // Asked for every row of a list: the loaded titles are indexed by their normalised title once per change of the list.
        val all = jobs
        val index = byTitle.takeIf { it.first === all }?.second
            ?: all.filter { it.state == WebJob.State.Done && it.file != null }.groupBy { Matcher.normal(it.hit.title) }.also { byTitle = all to it }
        return index[title].orEmpty().lastOrNull { j ->
                Matcher.artistScore(track.artist, j.hit.artist) >= 0.5 &&
                (track.duration <= 0 || j.hit.duration <= 0 || kotlin.math.abs(track.duration - j.hit.duration) <= 5) }
            ?.file?.let(::File)?.takeIf { it.isFile }
    }

    fun folder(context: Context): File =
        File(context.externalMediaDirs.firstOrNull() ?: context.filesDir, tr("Aus dem Netz")).apply { mkdirs() }

    private fun file(context: Context) = File(context.filesDir, "netz-warteschlange.json")

    @Synchronized fun load(context: Context) {
        if (loaded) return
        loaded = true
        jobs = runCatching {
            JSONArray(file(context).readText()).objects().map { j ->
                val h = j.getJSONObject("hit")
                val hit = WebHit(h.getString("url"), h.optString("title"), h.optString("artist"), h.str("album"), h.optInt("duration"),
                    h.str("thumbnail"), runCatching { WebSource.valueOf(h.optString("source")) }.getOrDefault(WebSource.Other))
                val state = WebJob.State.valueOf(j.getString("state"))
                // Interrupted by a restart: start again.
                WebJob(j.getString("id"), hit, if (state == WebJob.State.Loading || state == WebJob.State.Converting) WebJob.State.Waiting else state,
                    0f, j.optString("note"), j.str("file"), j.optLong("at"))
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized private fun save(context: Context) {
        val a = JSONArray()
        jobs.forEach { j ->
            val h = j.hit
            a.put(JSONObject().put("id", j.id).put("state", j.state.name).put("note", j.note).put("file", j.file).put("at", j.at)
                .put("hit", JSONObject().put("url", h.url).put("title", h.title).put("artist", h.artist).put("album", h.album)
                    .put("duration", h.duration).put("thumbnail", h.thumbnail).put("source", h.source.name)))
        }
        runCatching { file(context).writeText(a.toString()) }
    }

    @Synchronized private fun change(context: Context, id: String, persist: Boolean = true, f: (WebJob) -> WebJob) {
        jobs = jobs.map { if (it.id == id) f(it) else it }
        if (persist) save(context)
    }

    /** Puts titles into the queue (a title already waiting or loaded is not added twice) and starts the service. */
    @Synchronized fun add(context: Context, hits: List<WebHit>) {
        load(context)
        val known = jobs.filter { it.state != WebJob.State.Failed }.map { it.hit.key }.toSet()
        val fresh = hits.filter { !it.isList && it.key !in known }.distinctBy { it.key }.map { WebJob(SubsonicServer.md5(it.url + System.nanoTime()).take(12), it) }
        jobs = jobs.filter { j -> j.state != WebJob.State.Failed || hits.none { it.key == j.hit.key } } + fresh
        save(context)
        start(context)
    }

    /** Missing titles of an import: the best match at the music source goes into the queue (shown with its real name there). */
    fun addSearched(context: Context, wanted: List<Wanted>, progress: (Int, Int) -> Unit) {
        val engine = Variant.engine(context) ?: return
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            val hits = wanted.mapIndexedNotNull { i, w ->
                main.post { progress(i, wanted.size) }
                runCatching { engine.search(WebSource.Music, listOf(w.artist, w.title).filter { it.isNotEmpty() }.joinToString(" "), 1).firstOrNull() }.getOrNull()
            }
            main.post { add(context, hits); progress(wanted.size, hits.size) }
        }.start()
    }

    fun start(context: Context) {
        if (jobs.none { it.state == WebJob.State.Waiting }) return
        val intent = Intent(context, WebLoadService::class.java)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    }

    @Synchronized fun retry(context: Context, id: String) { change(context, id) { it.copy(state = WebJob.State.Waiting, note = "", progress = 0f) }; start(context) }

    /** Cancels a waiting or loading title, or removes a finished one from the list (the file stays unless asked). */
    fun remove(context: Context, id: String, deleteFile: Boolean = false) {
        val job = jobs.firstOrNull { it.id == id } ?: return
        if (job.state == WebJob.State.Loading || job.state == WebJob.State.Converting) Variant.engine(context)?.cancel(id)
        if (deleteFile) job.file?.let { File(it).delete() }
        synchronized(this) { jobs = jobs.filter { it.id != id }; save(context) }
    }

    @Synchronized fun clearDone(context: Context) { jobs = jobs.filter { it.state != WebJob.State.Done }; save(context) }

    /** The service's loop: takes the next waiting title until none is left. */
    fun run(context: Context, notify: (WebJob?) -> Unit) {
        // Up to PARALLEL loops; each takes the next waiting title (taken and marked in one step, so no title runs twice).
        // The first loop always starts (its end stops the service); a further one only for a waiting title.
        while (true) {
            val now = running.get()
            if (now >= PARALLEL || (now > 0 && jobs.none { it.state == WebJob.State.Waiting })) return
            if (running.compareAndSet(now, now + 1)) break
        }
        worker.execute {
            // The downloads must never take the CPU from the app and the music (Olaf 06.10.2026).
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND + android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE)
            try {
                val engine = Variant.engine(context) ?: return@execute
                while (true) {
                    val job = synchronized(this) {
                        jobs.firstOrNull { it.state == WebJob.State.Waiting }?.also { j -> change(context, j.id) { it.copy(state = WebJob.State.Loading, note = tr("Wird vorbereitet …")) } }
                    } ?: break
                    notify(jobs.first { it.id == job.id })
                    var last = 0L
                    try {
                        var shown = -1f
                        val file = engine.download(job.hit, folder(context), job.id) { p, note ->
                            notes[job.id] = note
                            // Only a state change touches the list; the percentage goes to its own map, at most every 2 %.
                            if (note.startsWith("Wird umgewandelt") && jobs.firstOrNull { it.id == job.id }?.state != WebJob.State.Converting)
                                change(context, job.id, persist = false) { it.copy(state = WebJob.State.Converting) }
                            if (p - shown >= 0.02f || p >= 1f) { shown = p; main.post { progress[job.id] = p } }
                            val now = System.currentTimeMillis()
                            if (now - last > 1000) { last = now; notify(jobs.firstOrNull { it.id == job.id }) }
                        }
                        main.post { progress.remove(job.id) }; notes.remove(job.id)
                        change(context, job.id) { it.copy(state = WebJob.State.Done, progress = 1f, note = "", file = file.absolutePath, at = System.currentTimeMillis()) }
                        WebLibrary.added(context)
                    } catch (e: Exception) {
                        // A refusal (403) mostly means the loader is out of date – the sites change often. Then: bring it up to
                        // date at once (at most hourly) and try this title once more, without bothering the user.
                        val refused = e.message?.contains("403") == true
                        if (refused && LoaderUpdate.now(context, engine) && jobs.any { it.id == job.id }) {
                            change(context, job.id) { it.copy(state = WebJob.State.Waiting, progress = 0f, note = tr("Wird erneut versucht …")) }
                            continue
                        }
                        if (jobs.any { it.id == job.id })
                            change(context, job.id) { it.copy(state = WebJob.State.Failed, note = e.message?.take(300) ?: tr("Unbekannter Fehler")) }
                    }
                }
            } finally {
                // The service ends only when the last loop is done.
                if (running.decrementAndGet() == 0) notify(null)
            }
        }
        // A second title waiting: start the second loop, too.
        if (jobs.count { it.state == WebJob.State.Waiting } > 1) run(context, notify)
    }
}

/** What came from the internet, as a library of its own (the folder "Aus dem Netz", read like a folder on the phone). */
object WebLibrary {
    const val ACCOUNT = "netz"
    var generation by mutableStateOf(0); private set
    private var library: LocalLibrary? = null

    @Synchronized fun get(context: Context): LocalLibrary = library
        ?: LocalLibrary(context.applicationContext, ACCOUNT, listOf(Uri.fromFile(WebDownloads.folder(context)))).also { library = it }

    /** A new file: read the folder again (unchanged files keep their entry). */
    fun added(context: Context) { runCatching { get(context).scan() }; generation++ }
}

/** Keeps the queue going while LiDio is in the background; the notification says what is loaded from where. */
class WebLoadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(WebDownloads.CHANNEL) == null)
            manager.createNotificationChannel(NotificationChannel(WebDownloads.CHANNEL, tr("Aus dem Netz laden"), NotificationManager.IMPORTANCE_LOW))
        val first = notification(null)
        if (Build.VERSION.SDK_INT >= 29) startForeground(8, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(8, first)
        WebDownloads.load(this)
        WebDownloads.run(this) { job ->
            if (job == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            else runCatching { manager.notify(8, notification(job)) }
        }
        return START_NOT_STICKY
    }

    private fun notification(job: WebJob?): Notification {
        val open = PendingIntent.getActivity(this, 0, packageManager.getLaunchIntentForPackage(packageName)?.putExtra("netz", true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val waiting = WebDownloads.jobs.count { it.state == WebJob.State.Waiting }
        val builder = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, WebDownloads.CHANNEL) else Notification.Builder(this))
            .setSmallIcon(R.drawable.media3_notification_small_icon).setOngoing(true).setContentIntent(open).setOnlyAlertOnce(true)
        if (job == null) return builder.setContentTitle(tr("Aus dem Netz laden")).setContentText(tr("Wird vorbereitet …")).build()
        return builder.setContentTitle(tr("„{title}“ von {label}", "title" to job.hit.title, "label" to job.hit.source.label))
            .setContentText(listOf(WebDownloads.noteOf(job), if (waiting > 0) tr("noch {waiting} in der Warteschlange", "waiting" to waiting) else "").filter { it.isNotEmpty() }.joinToString(" · "))
            .setProgress(100, ((WebDownloads.progress[job.id] ?: 0f) * 100).toInt(), (WebDownloads.progress[job.id] ?: 0f) <= 0f).build()
    }
}


/** Keeps the loader current without the user: once a day at the start, and right away when a site refuses. */
object LoaderUpdate {
    private fun prefs(context: Context) = context.getSharedPreferences("lader", Context.MODE_PRIVATE)

    /** Updates unless that happened within [minAge]; true when it ran now. Blocking – off the main thread. */
    fun now(context: Context, engine: WebEngine, minAge: Long = 3600_000L): Boolean {
        val last = prefs(context).getLong("aktualisiert", 0L)
        if (System.currentTimeMillis() - last < minAge) return false
        prefs(context).edit().putLong("aktualisiert", System.currentTimeMillis()).apply()
        return runCatching { engine.update() }.isSuccess
    }

    fun daily(context: Context) {
        if (!Variant.PRIVATE) return
        Thread { Variant.engine(context)?.let { now(context, it, 20 * 3600_000L) } }.start()
    }
}
