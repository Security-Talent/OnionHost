package com.onionhost.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onionhost.app.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val autoStartOnBoot: StateFlow<Boolean> = settingsRepository.autoStartOnBoot
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    val defaultPort: StateFlow<Int> = settingsRepository.defaultPort
        .stateIn(viewModelScope, SharingStarted.Lazily, 8080)

    val rateLimitPerMin: StateFlow<Int> = settingsRepository.rateLimitPerMin
        .stateIn(viewModelScope, SharingStarted.Lazily, 120)

    fun toggleAutoStart(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoStartOnBoot(enabled)
        }
    }

    fun updateDefaultPort(port: Int) {
        if (port !in 1024..65535) return
        viewModelScope.launch {
            settingsRepository.setDefaultPort(port)
        }
    }

    fun updateRateLimit(limit: Int) {
        if (limit !in 1..10_000) return
        viewModelScope.launch {
            settingsRepository.setRateLimit(limit)
        }
    }
}
