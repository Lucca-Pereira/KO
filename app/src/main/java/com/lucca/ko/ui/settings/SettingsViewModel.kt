package com.lucca.ko.ui.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucca.ko.data.BackupRepository
import com.lucca.ko.data.repo.AgentImportRepository
import com.lucca.ko.ui.koFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface BackupState {
    data object Idle : BackupState

    data object Working : BackupState

    data class Done(val message: String) : BackupState

    data class Failed(val message: String) : BackupState
}

sealed interface AgentImportState {
    data object Idle : AgentImportState

    data object Working : AgentImportState

    data class Done(val message: String) : AgentImportState

    data class Failed(val message: String) : AgentImportState
}

class SettingsViewModel(
    private val agentImportRepo: AgentImportRepository,
    private val backupRepo: BackupRepository,
) : ViewModel() {

    private val _agentImport = MutableStateFlow<AgentImportState>(AgentImportState.Idle)
    val agentImport = _agentImport.asStateFlow()

    fun importAgentFile(resolver: ContentResolver, uri: Uri) = viewModelScope.launch {
        _agentImport.value = AgentImportState.Working
        runCatching {
            val text = withContext(Dispatchers.IO) {
                resolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                    ?: error("Couldn't open the file.")
            }
            agentImportRepo.import(text)
        }
            .onSuccess { summary ->
                _agentImport.value = if (summary.isEmpty && summary.skipped.isEmpty()) {
                    AgentImportState.Failed("That file didn't have anything importable in it.")
                } else {
                    val parts = buildList {
                        if (summary.recipesSaved > 0) add("${summary.recipesSaved} recipe(s)")
                        if (summary.pantryUpdated > 0) add("${summary.pantryUpdated} pantry item(s)")
                        if (summary.shoppingAdded > 0) add("${summary.shoppingAdded} shopping item(s)")
                        if (summary.planEntriesAdded > 0) add("${summary.planEntriesAdded} plan entr(y/ies)")
                    }
                    val done = if (parts.isEmpty()) "Nothing new." else "Added ${parts.joinToString(", ")}."
                    val skippedNote = if (summary.skipped.isNotEmpty()) {
                        " Skipped: ${summary.skipped.joinToString("; ")}"
                    } else {
                        ""
                    }
                    AgentImportState.Done(done + skippedNote)
                }
            }
            .onFailure { _agentImport.value = AgentImportState.Failed(it.message ?: "Import failed.") }
    }

    fun clearAgentImportState() {
        _agentImport.value = AgentImportState.Idle
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
                agentImportRepo = it.agentImportRepository,
                backupRepo = it.backupRepository,
            )
        }
    }
}
