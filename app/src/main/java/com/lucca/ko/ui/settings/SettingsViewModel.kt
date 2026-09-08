package com.lucca.ko.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lucca.ko.data.KitchenRepository
import com.lucca.ko.data.prefs.AppSettings
import com.lucca.ko.data.prefs.SettingsRepository
import com.lucca.ko.ui.koApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Ok(val models: List<String>) : TestState
    data class Failed(val message: String) : TestState
}

class SettingsViewModel(
    private val repo: KitchenRepository,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    val settings = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _test = MutableStateFlow<TestState>(TestState.Idle)
    val test = _test.asStateFlow()

    fun setBaseUrl(value: String) = viewModelScope.launch { settingsRepo.update(baseUrl = value) }
    fun setModel(value: String) = viewModelScope.launch { settingsRepo.update(model = value) }
    fun setCount(value: Int) = viewModelScope.launch { settingsRepo.update(count = value) }

    fun testConnection(baseUrl: String) = viewModelScope.launch {
        _test.value = TestState.Running
        repo.testOllama(baseUrl)
            .onSuccess { _test.value = TestState.Ok(it) }
            .onFailure { _test.value = TestState.Failed(it.message ?: "Connection failed") }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    koApp.container.repository,
                    koApp.container.settingsRepository,
                )
            }
        }
    }
}
