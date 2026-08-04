package app.cobaltclip

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.cobaltclip.data.UserSettings
import app.cobaltclip.download.DownloadService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as CobaltApp
    val history = app.database.downloads().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings = app.settings.flow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    fun setAuto(value: Boolean) = viewModelScope.launch { app.settings.setAuto(value) }
    fun setQuality(value: String) = viewModelScope.launch { app.settings.setQuality(value) }
    fun setDownloadMode(value: String) =
        viewModelScope.launch { app.settings.setDownloadMode(value) }
    fun setIncognito(value: Boolean) =
        viewModelScope.launch { app.settings.setIncognito(value) }
    fun setOutputTreeUri(value: String) =
        viewModelScope.launch { app.settings.setOutputTreeUri(value) }
    fun setEndpoint(value: String) = viewModelScope.launch { app.settings.setEndpoint(value) }
    fun setApiKey(value: String) = viewModelScope.launch { app.settings.setApiKey(value) }
    fun setVkToken(value: String) = viewModelScope.launch { app.settings.setVkToken(value) }
    fun clearHistory() = viewModelScope.launch { app.database.downloads().clearFinished() }
    fun retry(id: Long) = viewModelScope.launch {
        app.database.downloads().retry(id)
        DownloadService.process(getApplication())
    }
    fun cancel(id: Long) {
        DownloadService.cancel(getApplication(), id)
    }
    fun delete(id: Long) = viewModelScope.launch { app.database.downloads().delete(id) }

    fun applyProfile(mode: String, quality: String) = viewModelScope.launch {
        app.settings.setDownloadMode(mode)
        app.settings.setQuality(quality)
    }
}
