@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.veritasx1.lidio

import io.github.veritasx1.lidio.i18n.tr

import android.app.Notification
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Scheduler
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Music without network (card 3). Two stores in the app's own storage:
 *   - "Geladen": what the user downloads on purpose – never cleared by itself (Media3 DownloadManager).
 *   - "Zuletzt gehört": what was played, fetched completely in the background – limited, the longest unheard goes first.
 *  The stream addresses carry changing logins, so every title has its own cache key; the quality is part of the key so
 *  that parts of different conversions are never mixed. A small index keeps the titles' details for browsing offline. */
object Offline {
    private var downloads: SimpleCache? = null
    private var played: SimpleCache? = null
    private var database: StandaloneDatabaseProvider? = null
    private var manager: DownloadManager? = null
    private val background = Executors.newSingleThreadExecutor()
    const val CHANNEL = "laden"

    @Synchronized fun database(context: Context) = database ?: StandaloneDatabaseProvider(context.applicationContext).also { database = it }

    @Synchronized fun downloads(context: Context): SimpleCache = downloads
        ?: SimpleCache(File(context.filesDir, "geladen"), NoOpCacheEvictor(), database(context)).also { downloads = it }

    /** The limit is read when LiDio starts; a new limit applies from the next start (said in the settings). */
    @Synchronized fun played(context: Context): SimpleCache = played
        ?: SimpleCache(File(context.filesDir, "gehoert"), LeastRecentlyUsedCacheEvictor(Settings(context).keepLimitMb * 1024L * 1024L), database(context))
            .also { played = it }

    private val http: DataSource.Factory = DefaultHttpDataSource.Factory().setUserAgent("LiDio").setAllowCrossProtocolRedirects(true)

    /** For the player: the downloads first, then what was heard, then the server (which also fills "Zuletzt gehört"). */
    fun playerSource(context: Context): DataSource.Factory {
        val cached = CacheDataSource.Factory().setCache(downloads(context)).setCacheWriteDataSinkFactory(null)
            .setUpstreamDataSourceFactory(CacheDataSource.Factory().setCache(played(context)).setUpstreamDataSourceFactory(http))
        val direct = androidx.media3.datasource.DefaultDataSource.Factory(context, http)
        // Local files (and FFmpeg's) past the caches, server streams through them.
        return DataSource.Factory { RoutingDataSource(context, cached.createDataSource(), direct.createDataSource()) }
    }

    @Synchronized fun manager(context: Context): DownloadManager = manager ?: DownloadManager(context.applicationContext, database(context),
        downloads(context), http, Executors.newFixedThreadPool(1)).apply {
        // One title after the other (Olaf 05.10.2026): spares the server and leaves room for what is playing; in the home
        // network a 26-title playlist took ~10 s with two at once.
        maxParallelDownloads = 1
        requirements = requirements(context)
        addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(manager: DownloadManager, download: Download, error: Exception?) {
                if (download.state == Download.STATE_COMPLETED) Index.add(context, download.request.id, String(download.request.data), Index.DOWNLOADED)
            }
            override fun onDownloadRemoved(manager: DownloadManager, download: Download) = Index.remove(context, download.request.id)
        })
    }.also { manager = it }

    /** Card 2aaf09ce: what is loading right now, per download key – queued (waiting) or its percentage. Read by every title row. */
    val live = androidx.compose.runtime.mutableStateMapOf<String, Float?>()

    /** Called twice a second while LiDio is open: waiting = null, loading = 0..1. */
    fun refreshLive(context: Context) {
        val now = runCatching { manager(context).currentDownloads }.getOrDefault(emptyList())
        val fresh = now.associate { d -> d.request.id to (if (d.state == Download.STATE_DOWNLOADING) (d.percentDownloaded.takeIf { it >= 0 } ?: 0f) / 100f else null) }
        live.keys.filter { it !in fresh }.forEach { live.remove(it) }
        fresh.forEach { (k, v) -> if (live[k] != v || k !in live) live[k] = v }
    }

    fun requirements(context: Context) = Requirements(if (Settings(context).wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)

    fun downloadKey(account: String, track: Track) = "d:$account:${track.id}"
    fun streamKey(account: String, track: Track, kbit: Int) = "s:$account:${track.id}:$kbit"

    /** Downloads these titles (an album, a playlist) in the original quality – or the WLAN setting. */
    fun download(context: Context, account: String, server: MusicServer, tracks: List<Track>) {
        val kbit = Settings(context).wifiBitrate
        tracks.forEach { track ->
            val key = downloadKey(account, track)
            val request = DownloadRequest.Builder(key, Uri.parse(server.streamUrl(track, kbit))).setCustomCacheKey(key)
                .setData(Index.encode(track).toString().toByteArray()).build()
            DownloadService.sendAddDownload(context, Downloads::class.java, request, false)
        }
        // The covers too, so the list and Now Playing show them offline.
        val covers = tracks.mapNotNull { it.coverId }.distinct()
        background.execute { covers.forEach { id -> kotlinx.coroutines.runBlocking { Covers.load(context, server.coverUrl(id, 600)) } } }
    }

    fun remove(context: Context, keys: List<String>) = keys.forEach { key ->
        if (key.startsWith("d:")) DownloadService.sendRemoveDownload(context, Downloads::class.java, key, false)
        else { runCatching { played(context).removeResource(key) }; Index.remove(context, key) }
    }

    /** How a title can be played without the network right now (download or completely heard), or null. */
    fun stored(context: Context, account: String, track: Track): Pair<String, String>? {
        val download = downloadKey(account, track)
        Index.entry(context, download)?.let { return download to it.optString("uri") }
        return Index.heard(context, "s:$account:${track.id}:")?.let { (key, e) -> key to e.optString("uri") }
    }

    /** "Gehörtes behalten": fetches the whole title in the background while (or after) it plays. */
    fun keep(context: Context, item: MediaItem) {
        val settings = Settings(context)
        if (!settings.keepPlayed) return
        val config = item.localConfiguration ?: return
        val key = config.customCacheKey ?: return
        if (config.uri.scheme?.startsWith("http") != true) return   // a file on the phone needs no copy
        if (!key.startsWith("s:") || Index.entry(context, key) != null) return
        if (settings.wifiOnly && !settings.unmetered()) return
        val track = trackOf(item) ?: return
        background.execute {
            runCatching {
                val source = CacheDataSource.Factory().setCache(played(context)).setUpstreamDataSourceFactory(http).createDataSource()
                CacheWriter(source, DataSpec.Builder().setUri(config.uri).setKey(key).build(), null, null).cache()
                Index.add(context, key, Index.encode(track).put("uri", config.uri.toString()).toString(), Index.HEARD)
            }
        }
    }

    /** Bytes in use: downloads and heard titles. */
    fun usage(context: Context): Pair<Long, Long> = downloads(context).cacheSpace to played(context).cacheSpace

    fun clearHeard(context: Context) {
        val cache = played(context)
        cache.keys.toList().forEach { runCatching { cache.removeResource(it) } }
        Index.all(context).filter { it.first.startsWith("s:") }.forEach { Index.remove(context, it.first) }
    }

    /** The titles known offline, newest first; heard ones that the limit pushed out are left out. */
    fun titles(context: Context, account: String): List<Pair<Track, String>> {
        val heard = played(context).keys
        return Index.all(context).filter { (key, _) -> key.split(":").getOrNull(1) == account && (key.startsWith("d:") || key in heard) }
            .sortedByDescending { it.second.optLong("at") }.map { (key, e) -> Index.decode(e) to e.optString("kind") }
    }
}

/** Details of the titles that can be played offline (key → title as JSON, kind, when), in a private file. */
object Index {
    const val DOWNLOADED = "geladen"
    const val HEARD = "gehoert"
    private fun prefs(context: Context) = context.getSharedPreferences("offline", Context.MODE_PRIVATE)

    /** Read once, then kept in memory (card 5902afb5: every title row asks "offline?" – parsing the whole file each time was slow). */
    private var memory: LinkedHashMap<String, JSONObject>? = null
    @Synchronized private fun map(context: Context): LinkedHashMap<String, JSONObject> = memory ?: LinkedHashMap<String, JSONObject>().also { m ->
        prefs(context).all.forEach { (k, v) -> (v as? String)?.let { runCatching { m[k] = JSONObject(it) } } }
        memory = m
    }

    @Synchronized fun add(context: Context, key: String, json: String, kind: String) {
        val entry = runCatching { JSONObject(json) }.getOrNull() ?: return
        entry.put("kind", kind).put("at", System.currentTimeMillis())
        map(context)[key] = entry
        prefs(context).edit().putString(key, entry.toString()).apply()
    }
    @Synchronized fun remove(context: Context, key: String) { map(context).remove(key); prefs(context).edit().remove(key).apply() }
    @Synchronized fun entry(context: Context, key: String): JSONObject? = map(context)[key]
    @Synchronized fun all(context: Context): List<Pair<String, JSONObject>> = map(context).map { (k, v) -> k to v }
    /** The heard copy of a title, without going through all entries. */
    @Synchronized fun heard(context: Context, prefix: String): Pair<String, JSONObject>? = map(context).entries.firstOrNull { it.key.startsWith(prefix) }?.toPair()

    fun encode(t: Track): JSONObject = JSONObject().put("id", t.id).put("title", t.title).put("artist", t.artist).put("album", t.album)
        .put("albumId", t.albumId).put("duration", t.duration).put("number", t.number).put("disc", t.disc).put("year", t.year)
        .put("coverId", t.coverId).put("artUrl", t.artUrl).put("albumArtist", t.albumArtist)
    fun decode(j: JSONObject) = Track(j.getString("id"), j.optString("title"), j.optString("artist"), j.optString("album"), j.str("albumId"),
        j.optInt("duration"), j.int("number"), j.int("disc"), j.int("year"), j.str("coverId"), artUrl = j.str("artUrl"), albumArtist = j.str("albumArtist"))
}

/** Android's download service for "Laden": a notification with progress while titles come in. */
class Downloads : DownloadService(7, DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL, Offline.CHANNEL, R.string.downloads, R.string.downloads_text) {
    override fun getDownloadManager(): DownloadManager = Offline.manager(this)
    override fun getScheduler(): Scheduler? = null
    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        DownloadNotificationHelper(this, Offline.CHANNEL).buildProgressNotification(this, R.drawable.media3_notification_small_icon, null,
            if (notMetRequirements != 0) tr("Wartet auf WLAN") else null, downloads, notMetRequirements)
}

