package io.github.veritasx1.lidio

import io.github.veritasx1.lidio.i18n.tr

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken

/** Plays in the background: ExoPlayer in a MediaSessionService – Android shows its controls on the lock screen and in
 *  the notification, headphones and Bluetooth buttons work, other apps' sound pauses LiDio (audio focus). */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var lockReceiver: android.content.BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        // The phone's own decoders first, FFmpeg's (AC3, DTS, TrueHD, ALAC …) where the phone has none.
        // Winamp's EQ, balance and analyzer sit in the audio path (card 6e42f66e).
        Dsp.load(this)
        val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean) =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(WinampProcessor()))
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
        }
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val player = ExoPlayer.Builder(this, renderers)
            // Downloads and heard titles first, the server only for what isn't stored (card 3).
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(Offline.playerSource(this)))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)   // headphones out → pause, like the iPhone
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(open).build()
        // Winamp on the lock screen (when switched on): screen off while playing → LockActivity over the lock.
        lockReceiver = LockScreen.receiver { player.isPlaying }
        registerReceiver(lockReceiver, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF))
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                item?.let { Played.note(this@PlaybackService, it); Offline.keep(this@PlaybackService, it) }
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiped away from the recent apps while paused: stop entirely (nothing left running). */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        lockReceiver?.let { runCatching { unregisterReceiver(it) } }
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}

/** The title carried in a player item (see Playback.item). */
fun trackOf(item: MediaItem): Track? = item.mediaMetadata.extras?.getString("track")?.let { runCatching { Index.decode(org.json.JSONObject(it)) }.getOrNull() }

/** Remembers which server a playing track belongs to, so the service can report "played" (scrobble). */
object Played {
    var server: MusicServer? = null
    fun note(context: Context, item: MediaItem) {
        val track = trackOf(item) ?: return
        val server = server ?: return
        Thread { server.played(track) }.start()
    }
}

/** The app's side of playback: a MediaController with the state the screens show. */
class Playback(private val context: Context) {
    val settings = Settings(context)
    /** The account in use – part of every title's cache key (the same title id on two servers is not the same file). */
    var accountId = ""
    var controller: MediaController? by mutableStateOf(null)
        private set
    var current by mutableStateOf<Track?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(false)
        private set
    var position by mutableLongStateOf(0L)
    var length by mutableLongStateOf(0L)
        private set
    var queue by mutableStateOf<List<Track>>(emptyList())
        private set
    var index by mutableIntStateOf(0)
        private set
    var shuffle by mutableStateOf(false)
        private set
    var repeat by mutableIntStateOf(Player.REPEAT_MODE_OFF)
        private set
    var volume by mutableFloatStateOf(1f)
    /** Titles Autoplay added (shown under "Autoplay" in the queue, like iOS). */
    val autoplayed = androidx.compose.runtime.mutableStateListOf<String>()
    var autoplay by mutableStateOf(settings.autoplay)
        private set
    /** ★ changed in this session (the server's answer may come later). */
    val favorites = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    private var autoplayFor: String? = null
    var error by mutableStateOf<String?>(null)

    private val tracks = mutableMapOf<String, Track>()

    fun connect() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = refresh()
                override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                    error = tr("Wiedergabe nicht möglich – {if}.", "if" to (if (e.errorCode in 2000..2999) "keine Verbindung zum Server" else "Format nicht unterstützt"))
                }
            })
            refresh()
        }, context.mainExecutor)
    }

    fun release() { controller?.release(); controller = null }

    /** Tests and previews: shows a title as playing without a player behind it. */
    fun preview(track: Track, list: List<Track> = listOf(track), playing: Boolean = true) {
        list.forEach { tracks[it.id] = it }
        current = track; queue = list; index = list.indexOf(track).coerceAtLeast(0); this.playing = playing
        length = track.duration * 1000L; position = length / 3
    }

    /** Reads the controller's state into Compose state (also called every half second for the position). */
    fun refresh() {
        val c = controller ?: return
        playing = c.isPlaying
        buffering = c.playbackState == Player.STATE_BUFFERING
        position = c.currentPosition.coerceAtLeast(0)
        length = c.duration.takeIf { it > 0 } ?: ((current?.duration ?: 0) * 1000L)
        index = c.currentMediaItemIndex
        shuffle = c.shuffleModeEnabled
        repeat = c.repeatMode
        volume = systemVolume()
        val items = (0 until c.mediaItemCount).map { c.getMediaItemAt(it) }
        // The whole title travels in the item's extras – a second screen (the lock screen) gets it complete, length too.
        queue = items.map { tracks.getOrPut(it.mediaId) { trackOf(it) ?: fromMetadata(it) } }
        current = c.currentMediaItem?.let { tracks.getOrPut(it.mediaId) { trackOf(it) ?: fromMetadata(it) } }
        autoplayIfNeeded()
    }

    /** Autoplay ∞: the last title is playing and nothing repeats – similar titles are put behind it, quietly (no message on failure). */
    private fun autoplayIfNeeded() {
        val now = current ?: return
        val server = Played.server ?: return
        if (!autoplay || repeat != Player.REPEAT_MODE_OFF || queue.isEmpty() || index != queue.lastIndex || autoplayFor == now.id) return
        autoplayFor = now.id
        val known = queue.map { it.id }.toSet()
        Thread {
            val more = runCatching { server.similar(now, 25) }.getOrDefault(emptyList()).filter { it.id !in known }
            if (more.isEmpty()) return@Thread
            context.mainExecutor.execute {
                val c = controller ?: return@execute
                if (c.currentMediaItem?.mediaId != now.id) return@execute
                more.forEach { tracks[it.id] = it }
                c.addMediaItems(more.map { item(server, it, settings.bitrate()) })
                autoplayed.addAll(more.map { it.id })
                refresh()
            }
        }.start()
    }

    fun toggleAutoplay() { autoplay = !autoplay; settings.autoplay = autoplay; if (autoplay) { autoplayFor = null; refresh() } }

    /** Drag in the queue: a title moves to another place. */
    fun move(from: Int, to: Int) { controller?.run { if (from in 0 until mediaItemCount && to in 0 until mediaItemCount) moveMediaItem(from, to) }; refresh() }

    fun isFavorite(track: Track) = favorites[track.id] ?: track.favorite ?: false
    /** ★: at once on screen, then on the server (Played.server – the one the title came from). */
    fun toggleFavorite(track: Track, server: MusicServer? = Played.server) {
        val on = !isFavorite(track)
        favorites[track.id] = on
        val s = server ?: return
        Thread { if (!runCatching { s.setFavorite(track, on) }.getOrDefault(false)) context.mainExecutor.execute { favorites[track.id] = !on } }.start()
    }

    /** Plays these tracks from `start` on (Apple Music: tap a title → the album or list from there). */
    fun play(server: MusicServer, list: List<Track>, start: Int = 0, shuffled: Boolean = false) {
        val c = controller ?: return
        Played.server = server
        list.forEach { tracks[it.id] = it }
        val kbit = settings.bitrate()
        autoplayed.clear(); autoplayFor = null
        c.setMediaItems(list.map { item(server, it, kbit) }, if (shuffled) 0 else start, 0L)
        c.shuffleModeEnabled = shuffled
        c.prepare()
        c.play()
        error = null
        refresh()
    }

    /** The network changed (WLAN ↔ away): every queued address moves to the other server address; the playing title
     *  goes on where it was. */
    fun moveTo(old: String, new: String) {
        val c = controller ?: return
        if (old.isEmpty() || new.isEmpty() || old == new) return
        val current = c.currentMediaItemIndex; val position = c.currentPosition; val wasPlaying = c.playWhenReady
        for (i in 0 until c.mediaItemCount) {
            val item = c.getMediaItemAt(i)
            val uri = item.localConfiguration?.uri?.toString() ?: continue
            if (!uri.startsWith(old)) continue
            c.replaceMediaItem(i, item.buildUpon().setUri(new + uri.removePrefix(old))
                .setMediaMetadata(item.mediaMetadata.buildUpon().setArtworkUri(item.mediaMetadata.artworkUri?.toString()?.replace(old, new)?.let { Uri.parse(it) }).build()).build())
        }
        if (c.currentMediaItemIndex == current) { c.seekTo(current, position); c.playWhenReady = wasPlaying }
        refresh()
    }

    fun toggle() { controller?.run { if (isPlaying) pause() else { if (playbackState == Player.STATE_IDLE) prepare(); play() } }; refresh() }
    fun next() { controller?.seekToNextMediaItem(); refresh() }
    /** Back: to the start of the title, or – within the first 3 seconds – to the one before (iPhone behaviour). */
    fun previous() { controller?.run { if (currentPosition > 3000) seekTo(0) else seekToPreviousMediaItem() }; refresh() }
    fun seek(ms: Long) { controller?.seekTo(ms); position = ms }
    fun jump(i: Int) { controller?.seekTo(i, 0); refresh() }
    fun remove(i: Int) { controller?.run { if (i in 0 until mediaItemCount) removeMediaItem(i) }; refresh() }
    fun toggleShuffle() { controller?.run { shuffleModeEnabled = !shuffleModeEnabled }; refresh() }
    /** Off → whole list → this title → off (Apple Music's repeat button). */
    fun cycleRepeat() {
        controller?.run { repeatMode = when (repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } }
        refresh()
    }
    /** The slider is the phone's music volume – the same as the volume keys (like on the iPhone). */
    private val audio = context.getSystemService(android.media.AudioManager::class.java)
    private fun systemVolume(): Float = audio?.let { it.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() /
        it.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1) } ?: 1f
    fun changeVolume(v: Float) {
        audio?.let { it.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, Math.round(v * it.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)), 0) }
        volume = v
    }
    fun playNext(server: MusicServer, track: Track) { tracks[track.id] = track; controller?.run { addMediaItem((currentMediaItemIndex + 1).coerceAtMost(mediaItemCount), item(server, track, settings.bitrate())) }; refresh() }
    fun playNextAll(server: MusicServer, list: List<Track>) {
        list.forEach { tracks[it.id] = it }
        controller?.run { if (mediaItemCount == 0) { play(server, list); return } ; addMediaItems((currentMediaItemIndex + 1).coerceAtMost(mediaItemCount), list.map { item(server, it, settings.bitrate()) }) }; refresh()
    }
    fun addAllToQueue(server: MusicServer, list: List<Track>) {
        list.forEach { tracks[it.id] = it }
        controller?.run { if (mediaItemCount == 0) { play(server, list); return } ; addMediaItems(list.map { item(server, it, settings.bitrate()) }) }; refresh()
    }
    fun addToQueue(server: MusicServer, track: Track) { tracks[track.id] = track; controller?.addMediaItem(item(server, track, settings.bitrate())); refresh() }

    /** A title for the player: from the store when it's there (no network needed), else streamed under its own key. */
    fun item(server: MusicServer, track: Track, kbit: Int = 0): MediaItem {
        val stored = Offline.stored(context, accountId, track)
        return item(server, track, kbit, stored?.first ?: Offline.streamKey(accountId, track, kbit), stored?.second)
    }

    companion object {
        /** The app was closed while the service kept playing: the titles come back from the player's own metadata. */
        fun fromMetadata(item: MediaItem): Track {
            val m = item.mediaMetadata
            return Track(item.mediaId, m.title?.toString() ?: "", m.artist?.toString() ?: "", m.albumTitle?.toString() ?: "",
                m.extras?.getString("albumId"), number = m.trackNumber, artUrl = m.artworkUri?.toString())
        }

        fun item(server: MusicServer, track: Track, kbit: Int, key: String, uri: String? = null): MediaItem = MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(Uri.parse(uri ?: server.streamUrl(track, kbit)))
            .setCustomCacheKey(key)
            .setTag(track)
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album)
                .setArtworkUri((track.coverId?.let { server.coverUrl(it, 600) } ?: track.artUrl)?.let { Uri.parse(it) })
                .setTrackNumber(track.number)
                .setIsPlayable(true).setIsBrowsable(false)
                // The whole title travels in the extras: a tag does not survive the way from the app to the service.
                .setExtras(Bundle().apply { putString("albumId", track.albumId); putString("track", Index.encode(track).toString()) })
                .build())
            .build()
    }
}
