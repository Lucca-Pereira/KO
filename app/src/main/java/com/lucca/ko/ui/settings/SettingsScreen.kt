package com.lucca.ko.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.foundation.text.KeyboardOptions
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

    var url by remember { mutableStateOf(settings.ollamaBaseUrl) }
    var model by remember { mutableStateOf(settings.ollamaModel) }
    LaunchedEffect(settings.ollamaBaseUrl) { if (url.isBlank()) url = settings.ollamaBaseUrl }
    LaunchedEffect(settings.ollamaModel) { if (model.isBlank()) model = settings.ollamaModel }

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
                        "dishes, meal plan and shopping list — and replace it with the " +
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

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Recipe bot (Ollama)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Run Ollama on your computer. On the Android emulator, http://10.0.2.2:11434 " +
                    "reaches it (that is the host's 127.0.0.1). On a real phone, start Ollama with " +
                    "OLLAMA_HOST=0.0.0.0 and use the computer's Wi-Fi IP, e.g. http://192.168.1.20:11434.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = url,
                onValueChange = { url = it; vm.setBaseUrl(it) },
                label = { Text("Ollama server URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it; vm.setModel(it) },
                label = { Text("Model name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { vm.testConnection(url) },
                    enabled = url.isNotBlank() && test != com.lucca.ko.ui.settings.TestState.Running,
                ) { Text("Test connection") }
                when (val t = test) {
                    is TestState.Running -> CircularProgressIndicator(Modifier.padding(4.dp))
                    is TestState.Ok -> Text(
                        if (t.models.isEmpty()) "Connected — no models installed"
                        else "Connected · ${t.models.size} model(s)",
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
            (test as? TestState.Ok)?.takeIf { it.models.isNotEmpty() }?.let { ok ->
                Text(
                    "Tap an installed model to use it:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ok.models.forEach { m ->
                        FilterChip(
                            selected = m == model,
                            onClick = { model = m; vm.setModel(m) },
                            label = { Text(m) },
                        )
                    }
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
