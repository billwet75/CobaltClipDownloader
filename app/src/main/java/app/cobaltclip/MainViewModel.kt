package app.cobaltclip

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.cobaltclip.data.UserSettings
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
    fun setEndpoint(value: String) = viewModelScope.launch { app.settings.setEndpoint(value) }
    fun setApiKey(value: String) = viewModelScope.launch { app.settings.setApiKey(value) }
    fun clearHistory() = viewModelScope.launch { app.database.downloads().clear() }
}
