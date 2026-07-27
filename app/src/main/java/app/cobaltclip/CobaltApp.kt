package app.cobaltclip

import android.app.Application
import app.cobaltclip.data.AppDatabase
import app.cobaltclip.data.SettingsRepository

class CobaltApp : Application() {
    val database by lazy { AppDatabase.create(this) }
    val settings by lazy { SettingsRepository(this) }
}
