package com.lucca.ko.ui.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucca.ko.data.BackupRepository
import com.lucca.ko.data.KitchenRepository
import com.lucca.ko.data.prefs.AppSettings
import com.lucca.ko.data.prefs.SettingsRepository
import com.lucca.ko.ui.koFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Ok(val models: List<String>) : TestState
    data class Failed(val message: String) : TestState
}

sealed interface BackupState {
    data object Idle : BackupState
    data object Working : BackupState
    data class Done(val message: String) : BackupState
    data class Failed(val message: String) : BackupState
}

sealed interface TranslateState {
    data object Idle : TranslateState
    data object Running : TranslateState
    data class Done(val message: String) : TranslateState
    data class Failed(val message: String) : TranslateState
}

class SettingsViewModel(
    private val repo: KitchenRepository,
    private val settingsRepo: SettingsRepository,
    private val backupRepo: BackupRepository,
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

    private val _translate = MutableStateFlow<TranslateState>(TranslateState.Idle)
    val translate = _translate.asStateFlow()

    fun translatePantry() = viewModelScope.launch {
        _translate.value = TranslateState.Running
        runCatching { repo.translatePantryToEnglish() }
            .onSuccess { n ->
                _translate.value = when {
                    n > 0 -> TranslateState.Done("Translated $n pantry item(s) for recipe search.")
                    else -> TranslateState.Failed(
                        "Nothing translated — check the recipe bot connection above.",
                    )
                }
            }
            .onFailure { _translate.value = TranslateState.Failed(it.message ?: "Translation failed.") }
    }

    private val _backup = MutableStateFlow<BackupState>(BackupState.Idle)
    val backup = _backup.asStateFlow()

    fun clearBackupState() { _backup.value = BackupState.Idle }

    fun exportTo(resolver: ContentResolver, uri: Uri) = viewModelScope.launch {
        _backup.value = BackupState.Working
        runCatching {
            val text = backupRepo.exportJson()
            withContext(Dispatchers.IO) {
                resolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(text.toByteArray())
                } ?: error("Couldn't open the file for writing.")
            }
        }
            .onSuccess { _backup.value = BackupState.Done("Backup saved.") }
            .onFailure { _backup.value = BackupState.Failed(it.message ?: "Export failed.") }
    }

    fun importFrom(resolver: ContentResolver, uri: Uri) = viewModelScope.launch {
        _backup.value = BackupState.Working
        runCatching {
            val text = withContext(Dispatchers.IO) {
                resolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                    ?: error("Couldn't open the file.")
            }
            backupRepo.importJson(text)
        }
            .onSuccess {
                _backup.value = BackupState.Done(
                    "Restored ${it.pantry} pantry · ${it.dishes} dishes · " +
                        "${it.plan} planned · ${it.shopping} shopping.",
                )
            }
            .onFailure { _backup.value = BackupState.Failed(it.message ?: "Import failed.") }
    }

    companion object {
        val Factory = koFactory {
            SettingsViewModel(it.repository, it.settingsRepository, it.backupRepository)
        }
    }
}
