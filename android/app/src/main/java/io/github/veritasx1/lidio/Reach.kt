package io.github.veritasx1.lidio

import java.net.HttpURLConnection
import java.net.URL

/** Home and away (Olaf, 04.10.2026): a server can have a fast address in the WLAN and one that works from anywhere. LiDio
 *  tries the WLAN one first (a short look, 1.5 s) and otherwise uses the other – at start and whenever the network changes. */
object Reach {
    fun normal(address: String): String = address.trim().trimEnd('/').let { if (it.isEmpty() || it.contains("://")) it else "http://$it" }

    /** Any answer from the server counts – even "not logged in"; only no answer at all means unreachable. */
    fun answers(kind: ServerKind, address: String, timeout: Int = 1500): Boolean {
        if (address.isBlank()) return false
        val probe = when (kind) {
            ServerKind.Navidrome -> "${normal(address)}/rest/ping.view"
            ServerKind.Emby, ServerKind.Jellyfin -> "${MediaBrowserServer.root(kind, address)}/System/Info/Public"
            ServerKind.Local, ServerKind.Web -> return true
        }
        return runCatching {
            val c = URL(probe).openConnection() as HttpURLConnection
            c.connectTimeout = timeout; c.readTimeout = timeout
            try { c.responseCode > 0 } finally { c.disconnect() }
        }.getOrDefault(false)
    }

    /** The address to use now: the WLAN one if it answers, else the one for away (if any), else the WLAN one anyway. */
    fun best(account: Account, check: (ServerKind, String) -> Boolean = { k, a -> answers(k, a) }): String = when {
        account.kind == ServerKind.Local || account.kind == ServerKind.Web || account.external.isBlank() -> account.address
        check(account.kind, account.address) -> account.address
        check(account.kind, account.external) -> account.external
        else -> account.address
    }
}
