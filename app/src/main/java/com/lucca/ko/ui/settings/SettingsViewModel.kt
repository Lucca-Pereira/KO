package com.lucca.ko.ui.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucca.ko.data.BackupRepository
import com.lucca.ko.data.prefs.SecretsRepository
import com.lucca.ko.data.remote.claude.ClaudeClient
import com.lucca.ko.data.remote.claude.textMessage
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

    data class Ok(val message: String) : TestState

    data class Failed(val message: String) : TestState
}

sealed interface BackupState {
    data object Idle : BackupState

    data object Working : BackupState

    data class Done(val message: String) : BackupState

    data class Failed(val message: String) : BackupState
}

class SettingsViewModel(
    private val claude: ClaudeClient,
    private val secretsRepo: SecretsRepository,
    private val backupRepo: BackupRepository,
) : ViewModel() {

    val apiKey = secretsRepo.apiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private val _test = MutableStateFlow<TestState>(TestState.Idle)
    val test = _test.asStateFlow()

    fun setApiKey(value: String) = viewModelScope.launch { secretsRepo.setApiKey(value) }

    /** Sends the smallest possible request, just to confirm the key is accepted. */
    fun testApiKey(key: String) = viewModelScope.launch {
        _test.value = TestState.Running
        runCatching { secretsRepo.setApiKey(key) }
        runCatching {
            claude.send(messages = listOf(textMessage("user", "Say \"hi\" and nothing else.")), maxTokens = 16)
        }
            .onSuccess { _test.value = TestState.Ok("That key works.") }
            .onFailure { _test.value = TestState.Failed(it.message ?: "Couldn't reach Claude.") }
    }

    private val _backup = MutableStateFlow<BackupState>(BackupState.Idle)
    val backup = _backup.asStateFlow()

    fun clearBackupState() {
        _backup.value = BackupState.Idle
    }

    fun exportTo(resolver: ContentResolver, uri: Uri) = viewModelScope.launch {
        _backup.value = BackupState.Working
        runCatching {
            val text = backupRepo.exportJson()
            withContext(Dispatchers.IO) {
                resolver.openOutputStream(uri, "wt")?.use { out -> out.write(text.toByteArray()) }
                    ?: error("Couldn't open the file for writing.")
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
                    "Restored ${it.pantry} pantry · ${it.recipes} recipes · " +
                        "${it.plan} planned · ${it.shopping} shopping" +
                        if (it.loggedDays > 0) " · ${it.loggedDays} days logged." else ".",
                )
            }
            .onFailure { _backup.value = BackupState.Failed(it.message ?: "Import failed.") }
    }

    companion object {
        val Factory = koFactory {
            SettingsViewModel(
                claude = it.claudeClient,
                secretsRepo = it.secretsRepository,
                backupRepo = it.backupRepository,
            )
        }
    }
}
