package com.lucca.ko.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lucca.ko.BuildConfig

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val test by vm.test.collectAsStateWithLifecycle()
    val backup by vm.backup.collectAsStateWithLifecycle()
    val translate by vm.translate.collectAsStateWithLifecycle()

    val token by vm.token.collectAsStateWithLifecycle()

    var url by remember { mutableStateOf(settings.nasBaseUrl) }
    var tokenText by remember { mutableStateOf(token) }
    var tokenVisible by remember { mutableStateOf(false) }
    LaunchedEffect(settings.nasBaseUrl) { if (url.isBlank()) url = settings.nasBaseUrl }
    LaunchedEffect(token) { if (tokenText.isBlank()) tokenText = token }

    val context = LocalContext.current
    var pendingImport by remember { mutableStateOf<android.net.Uri?>(null) }
    var restoreSettingsToo by remember { mutableStateOf(false) }

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
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Importing will delete everything currently in KO Kitchen — pantry, " +
                            "recipes, meal plan and shopping list — and replace it with the " +
                            "contents of the backup file. This can't be undone.",
                    )
                    // Off by default: restoring an old backup should not silently reset the
                    // server settings you fixed last week.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { restoreSettingsToo = !restoreSettingsToo },
                    ) {
                        Checkbox(
                            checked = restoreSettingsToo,
                            onCheckedChange = { restoreSettingsToo = it },
                        )
                        Text("Also restore the server settings from the file")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val uri = pendingImport
                    pendingImport = null
                    if (uri != null) {
                        vm.importFrom(context.contentResolver, uri, restoreSettingsToo)
                    }
                }) { Text("Replace") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) { Text("Cancel") }
            },
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Recipe bot", style = MaterialTheme.typography.titleMedium)
            Text(
                "The KO brain service on your NAS. It talks to Ollama for you, so the model " +
                    "settings live there, not here — which is why fixing a prompt no longer " +
                    "needs a new version of this app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = url,
                onValueChange = { url = it; vm.setBaseUrl(it) },
                label = { Text("Brain URL") },
                supportingText = { Text("Port 8080, not Ollama's 11434") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = tokenText,
                onValueChange = { tokenText = it; vm.setToken(it) },
                label = { Text("Access token") },
                supportingText = { Text("KO_API_TOKEN from the server's .env") },
                singleLine = true,
                visualTransformation = if (tokenVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(
                            if (tokenVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (tokenVisible) "Hide token" else "Show token",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { vm.testConnection(url) },
                    enabled = url.isNotBlank() && test != TestState.Running,
                ) { Text("Test connection") }
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

            Text("Suggestions per request: ${settings.suggestionCount}", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = settings.suggestionCount.toFloat(),
                onValueChange = { vm.setCount(it.toInt()) },
                valueRange = 3f..10f,
                steps = 6,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("Recipe search language", style = MaterialTheme.typography.titleMedium)
            Text(
                "Recipes come from TheMealDB, which is English-only. If your pantry is in " +
                    "another language, the bot can fill in an English name for each item so " +
                    "recipe search and the have/need colours work. Re-run it after adding items.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { vm.translatePantry() },
                enabled = translate != TranslateState.Running,
            ) { Text("Translate pantry to English") }
            when (val tr = translate) {
                TranslateState.Running -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                    Text("Asking the bot…", style = MaterialTheme.typography.bodySmall)
                }
                is TranslateState.Done -> Text(
                    tr.message,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
                is TranslateState.Failed -> Text(
                    tr.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                TranslateState.Idle -> {}
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
                "Recipe data and images from TheMealDB (themealdb.com). Recipe ideas are " +
                    "generated locally by your own Ollama server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
