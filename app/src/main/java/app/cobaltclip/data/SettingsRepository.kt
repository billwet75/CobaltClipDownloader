package app.cobaltclip.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore("settings")

data class UserSettings(
    val autoDownload: Boolean = true,
    val quality: String = "1080",
    val downloadMode: String = "auto",
    val endpoint: String = "",
    val apiKey: String = ""
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val auto = booleanPreferencesKey("auto")
        val quality = stringPreferencesKey("quality")
        val downloadMode = stringPreferencesKey("download_mode")
        val endpoint = stringPreferencesKey("endpoint")
        val apiKey = stringPreferencesKey("api_key")
    }

    val flow: Flow<UserSettings> = context.settingsStore.data.map {
        UserSettings(
            autoDownload = it[Keys.auto] ?: true,
            quality = it[Keys.quality] ?: "1080",
            downloadMode = it[Keys.downloadMode] ?: "auto",
            endpoint = it[Keys.endpoint] ?: "",
            apiKey = it[Keys.apiKey] ?: ""
        )
    }

    suspend fun setAuto(value: Boolean) = context.settingsStore.edit { it[Keys.auto] = value }
    suspend fun setQuality(value: String) = context.settingsStore.edit { it[Keys.quality] = value }
    suspend fun setDownloadMode(value: String) =
        context.settingsStore.edit { it[Keys.downloadMode] = value }
    suspend fun setEndpoint(value: String) = context.settingsStore.edit { it[Keys.endpoint] = value.trim() }
    suspend fun setApiKey(value: String) = context.settingsStore.edit { it[Keys.apiKey] = value.trim() }
}
