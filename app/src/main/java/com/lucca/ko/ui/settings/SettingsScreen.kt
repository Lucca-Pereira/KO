package com.lucca.ko.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lucca.ko.BuildConfig
import com.lucca.ko.ui.common.KoTopBar

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)) {
    val apiKey by vm.apiKey.collectAsStateWithLifecycle()
    val test by vm.test.collectAsStateWithLifecycle()
    val backup by vm.backup.collectAsStateWithLifecycle()

    var keyText by remember { mutableStateOf(apiKey) }
    var keyVisible by remember { mutableStateOf(false) }
    LaunchedEffect(apiKey) { if (keyText.isBlank()) keyText = apiKey }

    val context = LocalContext.current
    var pendingImport by remember { mutableStateOf<android.net.Uri?>(null) }

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
            Text("Recipe agent", style = MaterialTheme.typography.titleMedium)
            Text(
                "KO talks to Claude directly using your own Anthropic API key — nothing runs " +
                    "on your own hardware, and the key never leaves this field except to " +
                    "reach api.anthropic.com.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = keyText,
                onValueChange = { keyText = it; vm.setApiKey(it) },
                label = { Text("Anthropic API key") },
                placeholder = { Text("sk-ant-…") },
                singleLine = true,
                visualTransformation = if (keyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (keyVisible) "Hide key" else "Show key",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { vm.testApiKey(keyText) },
                    enabled = keyText.isNotBlank() && test != TestState.Running,
                ) { Text("Test key") }
                when (val t = test) {
                    is TestState.Running -> CircularProgressIndicator(Modifier.padding(4.dp))
                    is TestState.Ok -> Text(
                        t.message,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    is TestState.Failed -> Text(
                        t.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TestState.Idle -> {}
                }
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
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
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
                "Recipes are found and written by Claude, using your own API key.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
