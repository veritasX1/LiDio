package io.github.veritasx1.lidio

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private lateinit var playback: Playback
    private lateinit var state: AppState

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        MediaBrowserServer.DEVICE = Device.id(this)
        playback = Playback(this).also { it.connect() }
        // Titles still waiting from last time (an update, a restart) go on loading by themselves.
        runCatching { WebDownloads.load(this); WebDownloads.start(this) }
        state = AppState(Accounts(this), playback)
        handle(intent)
        // The playing title in the notification and on the lock screen needs Android 13's notification permission.
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { LiDioTheme { LiDioApp(state) } }
    }

    /** A shared link (https://lisoft.goip.de/lidio/t#… from Signal & Co.) while LiDio is open. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: android.content.Intent?) {
        intent?.data?.let(Shared::parse)?.let { state.pendingShare = it }
        intent?.data?.let(SharedList::parse)?.let { state.pendingList = it }
        if (!Variant.PRIVATE || intent == null) return
        // LiDio privat: the download notification opens "Aus dem Netz" …
        if (intent.getBooleanExtra("netz", false)) {
            state.nowPlaying = false; state.tab = Tab.Library; state.stacks[Tab.Library] = listOf(Route.Library, Route.Web)
        }
        // … and a link shared from a site the variant knows opens in Suchen → Im Netz.
        if (intent.action == android.content.Intent.ACTION_SEND) {
            val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT) ?: return
            val link = Regex("""https?://\S+""").find(text)?.value ?: return
            state.nowPlaying = false; state.searchWeb = true; state.tab = Tab.Search
            Links.parse(link)?.let { (src, id) -> state.stacks[Tab.Search] = listOf(Route.RemotePage(RemoteList(src, id, ""))); return }
            state.searchFor = link; state.stacks[Tab.Search] = emptyList()
        }
    }

    override fun onDestroy() {
        playback.release()
        super.onDestroy()
    }
}

/** This installation's own id – servers list LiDio under it (Emby/Jellyfin "Geräte"). Random, nothing about the phone. */
object Device {
    fun id(context: android.content.Context): String {
        val prefs = context.getSharedPreferences("geraet", android.content.Context.MODE_PRIVATE)
        return prefs.getString("id", null) ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("id", it).apply() }
    }
}
