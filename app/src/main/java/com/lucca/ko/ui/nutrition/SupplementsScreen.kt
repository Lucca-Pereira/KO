package com.lucca.ko.ui.nutrition

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lucca.ko.data.db.Supplement
import com.lucca.ko.data.db.SupplementKind
import com.lucca.ko.data.repo.SupplementRepository
import com.lucca.ko.data.repo.SupplementStatus
import com.lucca.ko.ui.common.EmptyState
import com.lucca.ko.ui.common.KoTopBar
import com.lucca.ko.ui.koFactory
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class SupplementsViewModel(private val repo: SupplementRepository) : ViewModel() {

    private val date = MutableStateFlow(LocalDate.now())

    val state = date
        .flatMapLatest { repo.observeStatus(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggle(status: SupplementStatus) = viewModelScope.launch {
        if (status.takenToday) {
            repo.unlogDose(status.supplement, date.value)
        } else {
            repo.logDose(status.supplement, date.value)
        }
    }

    fun save(supplement: Supplement) = viewModelScope.launch { repo.save(supplement) }

    fun delete(id: Long) = viewModelScope.launch { repo.delete(id) }

    companion object {
        val Factory = koFactory { SupplementsViewModel(it.supplementRepository) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupplementsScreen(
    onBack: () -> Unit,
    vm: SupplementsViewModel = viewModel(factory = SupplementsViewModel.Factory),
) {
    val statuses by vm.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Supplement?>(null) }

    editing?.let { supplement ->
        SupplementDialog(
            supplement = supplement,
            onDismiss = { editing = null },
            onSave = { vm.save(it); editing = null },
            onDelete = if (supplement.id > 0) {
                { vm.delete(supplement.id); editing = null }
            } else {
                null
            },
        )
    }

    Scaffold(
        topBar = {
            KoTopBar(
                title = "Supplements",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = Supplement(name = "") }) {
                Icon(Icons.Filled.Add, contentDescription = "Add a supplement")
            }
        },
    ) { padding ->
        if (statuses.isEmpty()) {
            EmptyState(
                title = "Nothing tracked yet",
                subtitle = "Add creatine, a protein shake, whatever you take daily. " +
                    "Anything with calories lands in the food diary too.",
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(statuses, key = { it.supplement.id }) { status ->
                Card(Modifier.fillMaxWidth().clickable { editing = status.supplement }) {
                    Row(
                        Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = status.takenToday,
                            onCheckedChange = { vm.toggle(status) },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                status.supplement.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                buildList {
                                    add(
                                        "${status.supplement.doseAmount.trimZero()} " +
                                            status.supplement.doseUnit,
                                    )
                                    if (status.supplement.affectsMacros) {
                                        add("${status.supplement.kcalPerDose.toInt()} kcal")
                                        add("${status.supplement.proteinPerDose.toInt()} g protein")
                                    }
                                }.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                if (status.streakDays > 0) "${status.streakDays}d" else "—",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                "${(status.adherence30d * 100).toInt()}% / 30d",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    "Creatine has no calories, so ticking it records the streak and nothing " +
                        "else. A protein shake adds a line to the food diary as well.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SupplementDialog(
    supplement: Supplement,
    onDismiss: () -> Unit,
    onSave: (Supplement) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(supplement.name) }
    var dose by remember { mutableStateOf(supplement.doseAmount.trimZero()) }
    var unit by remember { mutableStateOf(supplement.doseUnit) }
    var kcal by remember { mutableStateOf(supplement.kcalPerDose.blankIfZero()) }
    var protein by remember { mutableStateOf(supplement.proteinPerDose.blankIfZero()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (supplement.id > 0) "Edit supplement" else "New supplement") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecimalField(dose, { dose = it }, "Dose", Modifier.weight(1f))
                    OutlinedTextField(
                        value = unit,
                        onValueChange = { unit = it },
                        label = { Text("Unit") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecimalField(kcal, { kcal = it }, "kcal (opt)", Modifier.weight(1f))
                    DecimalField(protein, { protein = it }, "Protein (opt)", Modifier.weight(1f))
                }
                Text(
                    "Leave the calories blank for something like creatine — it will be tracked " +
                        "for consistency without cluttering the food diary.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    val kcalValue = kcal.toDoubleOrNull() ?: 0.0
                    val proteinValue = protein.toDoubleOrNull() ?: 0.0
                    onSave(
                        supplement.copy(
                            name = name.trim(),
                            doseAmount = dose.toDoubleOrNull() ?: 1.0,
                            doseUnit = unit.trim().ifEmpty { "g" },
                            kcalPerDose = kcalValue,
                            proteinPerDose = proteinValue,
                            // Inferred rather than asked: it only changes the icon and the
                            // wording, and one more question on this dialog is not worth it.
                            kind = when {
                                proteinValue >= 10 -> SupplementKind.PROTEIN
                                kcalValue == 0.0 && name.contains("creatine", true) ->
                                    SupplementKind.CREATINE
                                else -> SupplementKind.OTHER
                            },
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text("Delete") } }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private fun Double.blankIfZero(): String = if (this == 0.0) "" else trimZero()
