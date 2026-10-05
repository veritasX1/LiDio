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
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var running = false
    const val CHANNEL = "netz"

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
        if (running) return
        running = true
        worker.execute {
            try {
                val engine = Variant.engine(context) ?: return@execute
                while (true) {
                    val job = synchronized(this) { jobs.firstOrNull { it.state == WebJob.State.Waiting } } ?: break
                    change(context, job.id) { it.copy(state = WebJob.State.Loading, note = tr("Wird vorbereitet …")) }
                    notify(jobs.first { it.id == job.id })
                    var last = 0L
                    try {
                        val file = engine.download(job.hit, folder(context), job.id) { p, note ->
                            val state = if (note.startsWith("Wird umgewandelt")) WebJob.State.Converting else WebJob.State.Loading
                            change(context, job.id, persist = false) { it.copy(state = state, progress = p, note = note) }
                            val now = System.currentTimeMillis()
                            if (now - last > 700) { last = now; notify(jobs.firstOrNull { it.id == job.id }) }
                        }
                        change(context, job.id) { it.copy(state = WebJob.State.Done, progress = 1f, note = "", file = file.absolutePath, at = System.currentTimeMillis()) }
                        WebLibrary.added(context)
                    } catch (e: Exception) {
                        if (jobs.any { it.id == job.id })
                            change(context, job.id) { it.copy(state = WebJob.State.Failed, note = e.message?.take(300) ?: tr("Unbekannter Fehler")) }
                    }
                }
            } finally {
                running = false
                notify(null)
            }
        }
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
            .setContentText(listOf(job.note, if (waiting > 0) tr("noch {waiting} in der Warteschlange", "waiting" to waiting) else "").filter { it.isNotEmpty() }.joinToString(" · "))
            .setProgress(100, (job.progress * 100).toInt(), job.progress <= 0f).build()
    }
}
