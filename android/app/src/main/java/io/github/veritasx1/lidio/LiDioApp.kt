package io.github.veritasx1.lidio

import android.app.Application
import io.github.veritasx1.lidio.i18n.I18n

/** Sets the language before any screen, service or widget builds its texts. */
class LiDioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        I18n.init(this)
        LoaderUpdate.daily(this)
        // Card 66bd0f1b: which titles from the internet are on this phone must be known from the start – before, only the
        // "Aus dem Netz" screen read the list, so after a restart loaded titles showed the cloud and streamed again.
        WebDownloads.load(this)
    }
}
