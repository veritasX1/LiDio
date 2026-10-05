package io.github.veritasx1.lidio

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** LiDio's own settings (not the server's): streaming quality on WLAN and mobile data, like Apple Music's "Audioqualität". */
class Settings(private val context: Context) {
    private val prefs = context.getSharedPreferences("einstellungen", Context.MODE_PRIVATE)

    /** kbit/s, 0 = the original file. Default: original on WLAN, 192 on mobile data (spares the data plan). */
    var wifiBitrate: Int
        get() = prefs.getInt("wlan", 0)
        set(value) { prefs.edit().putInt("wlan", value).apply() }
    var mobileBitrate: Int
        get() = prefs.getInt("mobil", 192)
        set(value) { prefs.edit().putInt("mobil", value).apply() }

    /** Card 4: one copy of the same title (and of the same album) in lists. */
    var hideDuplicates: Boolean
        get() = prefs.getBoolean("duplikate", true)
        set(value) { prefs.edit().putBoolean("duplikate", value).apply() }

    /** Card 561fa339: public playlists – Deezer only when switched on (search words go there), Spotify with one's own app. */
    var deezer: Boolean
        get() = prefs.getBoolean("deezer", false)
        set(value) { prefs.edit().putBoolean("deezer", value).apply() }
    var spotifyId: String
        get() = prefs.getString("spotifyId", "") ?: ""
        set(value) { prefs.edit().putString("spotifyId", value.trim()).apply() }
    var spotifySecret: String
        get() = prefs.getString("spotifySecret", null)?.let { Vault.open(it) } ?: ""
        set(value) { prefs.edit().putString("spotifySecret", if (value.isBlank()) null else Vault.seal(value.trim())).apply() }

    fun publicSources(): List<PublicPlaylists> = listOfNotNull(
        if (deezer) DeezerPlaylists() else null,
        if (spotifyId.isNotEmpty() && spotifySecret.isNotEmpty()) SpotifyPlaylists(spotifyId, spotifySecret) else null)

    /** Card 3: keep what was heard (fetched completely in the background), up to this many MB; load only on WLAN. */
    var keepPlayed: Boolean
        get() = prefs.getBoolean("gehoertBehalten", true)
        set(value) { prefs.edit().putBoolean("gehoertBehalten", value).apply() }
    var keepLimitMb: Int
        get() = prefs.getInt("gehoertGrenze", 2048)
        set(value) { prefs.edit().putInt("gehoertGrenze", value).apply() }
    var wifiOnly: Boolean
        get() = prefs.getBoolean("nurWlan", true)
        set(value) { prefs.edit().putBoolean("nurWlan", value).apply() }

    /** Card d894cc42: "Klassisch" (iOS 18 and before) or "Modern" (iOS 26: glass, floating tab bar) – independent of Winamp. */
    var modern: Boolean
        get() = prefs.getBoolean("modern", true)
        set(value) { prefs.edit().putBoolean("modern", value).apply() }

    /** Card 7ac89c11: look for lyrics at LRCLIB when the server has none – LiDio privat by itself; the public LiDio only when
     *  switched on (the title and artist go there). */
    var lyricsOnline: Boolean
        get() = prefs.getBoolean("liedtexteNetz", Variant.PRIVATE)
        set(value) { prefs.edit().putBoolean("liedtexteNetz", value).apply() }

    /** Autoplay ∞ (card 81b01b4a): when the queue ends, similar music goes on – on by default, like Apple Music. */
    var autoplay: Boolean
        get() = prefs.getBoolean("autoplay", true)
        set(value) { prefs.edit().putBoolean("autoplay", value).apply() }

    /** True on WLAN (or any network that isn't metered). */
    fun unmetered(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** The bitrate for now: mobile data (metered) or not. */
    fun bitrate(): Int = if (unmetered()) wifiBitrate else mobileBitrate

    companion object {
        val BITRATES = listOf(0, 320, 256, 192, 128)
        fun label(kbit: Int) = if (kbit == 0) "Original" else "$kbit"
        val LIMITS = listOf(1024, 2048, 5120, 10240)
        fun size(mb: Int) = if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"
    }
}
