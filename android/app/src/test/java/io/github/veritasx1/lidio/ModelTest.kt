package io.github.veritasx1.lidio

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelTest {
    @Test
    fun durations() {
        assertEquals("0:07", duration(7))
        assertEquals("3:07", duration(187))
        assertEquals("1:02:45", duration(3765))
        assertEquals("12 Titel, 48 Minuten", summary(12, 2880))
        assertEquals("1 Titel, 1 Minute", summary(1, 61))
        assertEquals("20 Titel, 1 Std. 5 Min.", summary(20, 3900))
    }

    @Test
    fun coverCacheIgnoresTheLogin() {
        val a = "http://s/rest/getCoverArt?u=x&t=abc&s=111&id=al-1&size=300"
        val b = "http://s/rest/getCoverArt?u=x&t=def&s=222&id=al-1&size=300"
        assertEquals(Covers.cacheKey(a), Covers.cacheKey(b))
        assertEquals(Covers.cacheKey("http://e/Items/1/Images/Primary?maxHeight=300&api_key=A"), Covers.cacheKey("http://e/Items/1/Images/Primary?maxHeight=300&api_key=B"))
    }

    @Test
    fun subsonicTokenLogin() {
        // Subsonic: t = md5(password + salt) – the password itself never goes over the wire.
        assertEquals("26719a1196d2a940705a59634eb18eab", SubsonicServer.md5("sesame" + "c19b2d"))
    }

    @Test
    fun accountsKeepTheSecretSealed() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val accounts = Accounts(context)
        accounts.save(Account("1", ServerKind.Emby, "https://musik", "olaf", "token-123", "Emby 4.9", "u1"))
        assertEquals("token-123", Accounts(context).active()?.secret)
        val raw = context.getSharedPreferences("konten", 0).getString("list", "")!!
        assertEquals(false, raw.contains("token-123"))
    }
}
