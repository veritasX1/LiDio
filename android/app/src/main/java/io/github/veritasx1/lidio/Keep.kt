package io.github.veritasx1.lidio

import android.content.Context
import io.github.veritasx1.lidio.i18n.tr
import java.io.File

/**
 * How long music stays on the phone (Olaf 05.10.2026): what was not heard for the chosen time is taken off the phone and
 * comes from the server again – loaded titles as well as heard ones. Favourites stay as long as they are favourites, then
 * the normal time runs from their last hearing. Titles from the internet go only when the own server has them (else they
 * would be gone). Tidied when LiDio starts, at most once a day, in the background.
 */
object Keep {
    /** 7, 30, 90, 180 days, or 0 = always. */
    val DAYS = listOf(7, 30, 90, 180, 0)
    fun label(days: Int) = when (days) { 0 -> tr("Immer"); 7 -> tr("1 Woche"); 30 -> tr("1 Monat"); 90 -> tr("3 Monate"); else -> tr("6 Monate") }

    private fun heard(context: Context) = context.getSharedPreferences("zuletzt-gehoert", Context.MODE_PRIVATE)
    private fun prefs(context: Context) = context.getSharedPreferences("behalten", Context.MODE_PRIVATE)

    /** A title started playing – remembered per user (account), so one person's listening keeps nothing for another. */
    fun heard(context: Context, account: String, id: String) { heard(context).edit().putLong("$account:$id", System.currentTimeMillis()).apply() }
    fun lastHeard(context: Context, account: String, id: String): Long = heard(context).getLong("$account:$id", 0L)

    /** The time to keep, per user (days, 0 = always; 1 month by default). */
    fun days(context: Context, account: String): Int = prefs(context).getInt("tage:$account", 30)
    fun setDays(context: Context, account: String, days: Int) { prefs(context).edit().putInt("tage:$account", days).apply() }

    /** The own server has this title from the internet now (the LiDio-Lader finished it). */
    fun onServer(context: Context, webKey: String) { prefs(context).edit().putBoolean("server:$webKey", true).apply() }
    private fun isOnServer(context: Context, webKey: String) = prefs(context).getBoolean("server:$webKey", false)

    /** Blocking – off the main thread. Returns how many titles left the phone. */
    fun tidy(context: Context, account: String, server: MusicServer, force: Boolean = false): Int {
        val days = days(context, account)
        if (days == 0) return 0
        val last = prefs(context).getLong("aufgeraeumt:$account", 0L)
        if (!force && System.currentTimeMillis() - last < 20 * 3600_000L) return 0
        // Without knowing the favourites nothing is touched (a favourite must never disappear by mistake).
        val favourites = runCatching { server.favorites().map { it.id }.toSet() }.getOrNull() ?: return 0
        val limit = System.currentTimeMillis() - days * 86_400_000L
        val old = Index.all(context).filter { (key, entry) ->
            val parts = key.split(":")
            val id = parts.getOrNull(2) ?: return@filter false
            parts.getOrNull(1) == account && id !in favourites && maxOf(lastHeard(context, account, id), entry.optLong("at")) < limit
        }.map { it.first }
        Offline.remove(context, old)
        old.filter { it.startsWith("d:") }.forEach { Index.remove(context, it) }
        // From the internet: only what the server has, and only when not heard for the time.
        var web = 0
        WebDownloads.load(context)
        for (job in WebDownloads.jobs.filter { it.state == WebJob.State.Done && it.file != null }) {
            val file = File(job.file!!)
            val video = Regex("""[?&]v=([\w-]{11})""").find(job.hit.url)?.groupValues?.get(1) ?: job.hit.key
            if (!isOnServer(context, job.hit.key) || video in favourites) continue
            if (maxOf(lastHeard(context, account, video), file.lastModified()) >= limit) continue
            WebDownloads.remove(context, job.id, deleteFile = true)
            web++
        }
        prefs(context).edit().putLong("aufgeraeumt:$account", System.currentTimeMillis()).apply()
        return old.size + web
    }
}
