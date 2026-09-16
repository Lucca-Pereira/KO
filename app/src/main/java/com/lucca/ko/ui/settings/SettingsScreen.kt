package com.lucca.ko.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lucca.ko.BuildConfig
import com.lucca.ko.ui.common.KoTopBar
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)) {
    val agentImport by vm.agentImport.collectAsStateWithLifecycle()
    val backup by vm.backup.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val savedNasUrl by vm.nasUrl.collectAsStateWithLifecycle()
    val savedNasToken by vm.nasToken.collectAsStateWithLifecycle()
    val lastSyncedAt by vm.lastSyncedAt.collectAsStateWithLifecycle()

    var nasUrlField by remember { mutableStateOf("") }
    var nasTokenField by remember { mutableStateOf("") }
    LaunchedEffect(savedNasUrl) { nasUrlField = savedNasUrl.orEmpty() }
    LaunchedEffect(savedNasToken) { nasTokenField = savedNasToken.orEmpty() }

    val context = androidx.compose.ui.platform.LocalContext.current
    var pendingImport by remember { mutableStateOf<android.net.Uri?>(null) }

    val agentImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { vm.importAgentFile(context.contentResolver, it) } }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let { vm.exportTo(context.contentResolver, it) } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> pendingImport = uri }

    if (pendingImport != null) {
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Replace all data?") },
            text = {
                Text(
                    "Importing will delete everything currently in KO Kitchen — pantry, " +
                        "recipes, meal plan and shopping list — and replace it with the " +
                        "contents of the backup file. This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val uri = pendingImport
                    pendingImport = null
                    if (uri != null) vm.importFrom(context.contentResolver, uri)
                }) { Text("Replace") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            KoTopBar(
                title = "Settings",
                showSettings = false,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Ask Claude for recipes", style = MaterialTheme.typography.titleMedium)
            Text(
                "KO doesn't call any AI itself — no API key, no billing. Instead, ask Claude " +
                    "for a recipe, a pantry update, or a shopping list addition wherever you " +
                    "already talk to it (a Claude Code session, claude.ai). Ask it to write a " +
                    "KO Kitchen import file, save that file to your phone, then import it here. " +
                    "Editing an existing recipe this way is undoable, same as a manual edit.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    agentImportLauncher.launch(
                        arrayOf("application/json", "application/octet-stream", "text/plain"),
                    )
                },
                enabled = agentImport != AgentImportState.Working,
            ) { Text("Import from Claude") }
            when (val a = agentImport) {
                AgentImportState.Working -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                    Text("Importing…", style = MaterialTheme.typography.bodySmall)
                }
                is AgentImportState.Done -> Text(
                    a.message,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
                is AgentImportState.Failed -> Text(
                    a.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                AgentImportState.Idle -> {}
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("NAS sync", style = MaterialTheme.typography.titleMedium)
            Text(
                "If you're running the KO sync service on your own NAS, point the app at it here " +
                    "and Claude Desktop can add recipes and pantry updates directly — no file to " +
                    "carry over. Syncs automatically when you open the app, or tap Sync now.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = nasUrlField,
                onValueChange = { nasUrlField = it },
                label = { Text("NAS URL") },
                placeholder = { Text("http://100.x.x.x:8090") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = nasTokenField,
                onValueChange = { nasTokenField = it },
                label = { Text("Token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        vm.setNasUrl(nasUrlField)
                        vm.setNasToken(nasTokenField)
                        vm.syncNow()
                    },
                    enabled = sync != SyncState.Working,
                ) { Text("Sync now") }
            }
            Text(
                if (lastSyncedAt > 0) {
                    "Last synced ${DateFormat.getDateTimeInstance().format(Date(lastSyncedAt))}"
                } else {
                    "Never synced"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (val s = sync) {
                SyncState.Working -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                    Text("Syncing…", style = MaterialTheme.typography.bodySmall)
                }
                is SyncState.Done -> Text(
                    s.message,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
                is SyncState.Failed -> Text(
                    s.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                SyncState.Idle -> {}
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("Backup & restore", style = MaterialTheme.typography.titleMedium)
            Text(
                "Export everything you've entered — pantry, dishes, meal plan and shopping " +
                    "list — to a JSON file you can keep. If you ever reinstall the app, import " +
                    "that file to get it all back. Importing replaces the app's current contents.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { exportLauncher.launch("ko-kitchen-backup.json") },
                    enabled = backup != BackupState.Working,
                ) { Text("Export") }
                OutlinedButton(
                    onClick = {
                        importLauncher.launch(
                            arrayOf("application/json", "application/octet-stream", "text/plain"),
                        )
                    },
                    enabled = backup != BackupState.Working,
                ) { Text("Import") }
            }
            when (val b = backup) {
                BackupState.Working -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                    Text("Working…", style = MaterialTheme.typography.bodySmall)
                }
                is BackupState.Done -> Text(
                    b.message,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
                is BackupState.Failed -> Text(
                    b.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                BackupState.Idle -> {}
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("About", style = MaterialTheme.typography.titleMedium)
            Text(
                "KO Kitchen v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Recipes are found and written by Claude — ask it directly, nothing in this " +
                    "app talks to it for you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
