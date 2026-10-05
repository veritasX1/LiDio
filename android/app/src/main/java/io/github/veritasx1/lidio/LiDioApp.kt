package io.github.veritasx1.lidio

import android.app.Application
import io.github.veritasx1.lidio.i18n.I18n

/** Sets the language before any screen, service or widget builds its texts. */
class LiDioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        I18n.init(this)
    }
}
