package io.github.veritasx1.lidio

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A server the user connected: address, user and the secret (password for Navidrome, the access token for Jellyfin/Emby). */
data class Account(val id: String, val kind: ServerKind, val address: String, val user: String, val secret: String,
                   val name: String = "", val userId: String = "",
                   /** The address from outside (e.g. https://…goip.de) – used when the WLAN address doesn't answer. */
                   val external: String = "") {
    fun server(context: Context): MusicServer = when (kind) {
        ServerKind.Navidrome -> SubsonicServer(address, user, secret)
        ServerKind.Jellyfin, ServerKind.Emby -> MediaBrowserServer(kind, address, userId, secret)
        ServerKind.Local -> LocalLibrary(context, id, LocalLibrary.decode(address))
        ServerKind.Web -> Variant.webServer(context) ?: throw ServerError("Diese LiDio kennt keine Quelle im Netz.")
    }
}

/** The accounts on this phone; the secrets encrypted with a key that never leaves Android's keystore. */
class Accounts(val context: Context) {
    private val prefs = context.getSharedPreferences("konten", Context.MODE_PRIVATE)

    fun all(): List<Account> = runCatching { JSONArray(prefs.getString("list", "[]")) }.getOrDefault(JSONArray()).objects().mapNotNull { j ->
        val secret = Vault.open(j.optString("secret")) ?: return@mapNotNull null
        Account(j.getString("id"), ServerKind.valueOf(j.getString("kind")), j.getString("address"), j.optString("user"), secret,
            j.optString("name"), j.optString("userId"), j.optString("external"))
    }

    var activeId: String?
        get() = prefs.getString("active", null)
        set(value) { prefs.edit().putString("active", value).apply() }

    fun active(): Account? = all().let { list -> list.firstOrNull { it.id == activeId } ?: list.firstOrNull() }

    fun save(account: Account) {
        val list = all().filter { it.id != account.id } + account
        write(list)
        activeId = account.id
    }

    fun remove(id: String) {
        write(all().filter { it.id != id })
        if (activeId == id) activeId = all().firstOrNull()?.id
    }

    private fun write(list: List<Account>) {
        val json = JSONArray()
        list.forEach { a ->
            json.put(JSONObject().put("id", a.id).put("kind", a.kind.name).put("address", a.address).put("user", a.user)
                .put("secret", Vault.seal(a.secret)).put("name", a.name).put("userId", a.userId).put("external", a.external))
        }
        prefs.edit().putString("list", json.toString()).apply()
    }
}

/** AES-GCM with a key in the Android keystore. Robolectric has no keystore – there (tests only) a marked plain form. */
object Vault {
    private const val ALIAS = "lidio-konten"
    private val testing = Build.FINGERPRINT == "robolectric"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun seal(text: String): String {
        if (testing) return "test:" + Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(text.toByteArray())
        return "v1:" + Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    fun open(text: String): String? = runCatching {
        when {
            text.startsWith("test:") && testing -> String(Base64.decode(text.removePrefix("test:"), Base64.NO_WRAP))
            text.startsWith("v1:") -> {
                val bytes = Base64.decode(text.removePrefix("v1:"), Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12)) }
                String(cipher.doFinal(bytes, 12, bytes.size - 12))
            }
            else -> null
        }
    }.getOrNull()
}
