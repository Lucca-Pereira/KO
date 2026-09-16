package com.lucca.ko.ui.nutrition

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lucca.ko.data.prefs.ProfileRepository
import com.lucca.ko.data.repo.NutritionRepository
import com.lucca.ko.domain.nutrition.ActivityLevel
import com.lucca.ko.domain.nutrition.EnergyCalculator
import com.lucca.ko.domain.nutrition.Goal
import com.lucca.ko.domain.nutrition.Sex
import com.lucca.ko.domain.nutrition.UserProfile
import com.lucca.ko.ui.common.KoTopBar
import com.lucca.ko.ui.koFactory
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ProfileViewModel(
    private val profileRepo: ProfileRepository,
    private val nutrition: NutritionRepository,
) : ViewModel() {

    private val _profile = MutableStateFlow(UserProfile())
    val profile = _profile.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved = _saved.asStateFlow()

    init {
        viewModelScope.launch { _profile.value = profileRepo.current() }
    }

    fun edit(block: (UserProfile) -> UserProfile) = _profile.update(block)

    fun save() = viewModelScope.launch {
        profileRepo.save(_profile.value)
        // The target is derived from all of this, so recompute it rather than leaving the rings
        // measuring against yesterday's numbers.
        nutrition.refreshTodaysTarget()
        _saved.value = true
    }

    companion object {
        val Factory = koFactory { ProfileViewModel(it.profileRepository, it.nutritionRepository) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    vm: ProfileViewModel = viewModel(factory = ProfileViewModel.Factory),
) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()

    LaunchedEffect(saved) { if (saved) onBack() }

    var height by remember(profile.heightCm) {
        mutableStateOf(profile.heightCm.takeIf { it > 0 }?.toInt()?.toString().orEmpty())
    }
    var weight by remember(profile.weightKg) {
        mutableStateOf(profile.weightKg.takeIf { it > 0 }?.toString().orEmpty())
    }
    var bodyFat by remember(profile.bodyFatPct) {
        mutableStateOf(profile.bodyFatPct?.toString().orEmpty())
    }
    var birthYear by remember(profile.birthYear) {
        mutableStateOf(profile.birthYear.takeIf { it > 1900 }?.toString().orEmpty())
    }
    var manualKcal by remember(profile.manualKcalTarget) {
        mutableStateOf(profile.manualKcalTarget?.toString().orEmpty())
    }
    // Off until a save is actually attempted, so a blank first-run screen doesn't open already
    // covered in red — only once someone has tried to save incomplete data do the specific
    // fields explain themselves, instead of the button just silently refusing to do anything.
    var showErrors by remember { mutableStateOf(false) }

    val edited = profile.copy(
        heightCm = height.toDoubleOrNull() ?: 0.0,
        weightKg = weight.toDoubleOrNull() ?: 0.0,
        bodyFatPct = bodyFat.toDoubleOrNull(),
        birthYear = birthYear.toIntOrNull() ?: 0,
        manualKcalTarget = manualKcal.toIntOrNull()?.takeIf { it > 0 },
    )
    val preview = EnergyCalculator.targetsFor(edited, LocalDate.now().year)

    Scaffold(
        topBar = {
            KoTopBar(
                title = "Your details",
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
            Text(
                "These feed the calorie and macro targets. Nothing leaves your phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Sex.entries.forEach { sex ->
                    FilterChip(
                        selected = profile.sex == sex,
                        onClick = { vm.edit { it.copy(sex = sex) } },
                        label = { Text(if (sex == Sex.MALE) "Male" else "Female") },
                    )
                }
            }

            val birthYearInvalid = showErrors && edited.birthYear <= 1900
            val heightInvalid = showErrors && edited.heightCm <= 50
            val weightInvalid = showErrors && edited.weightKg <= 20

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecimalField(
                    birthYear, { birthYear = it }, "Birth year", Modifier.weight(1f),
                    isError = birthYearInvalid,
                    supportingText = if (birthYearInvalid) "e.g. 1995" else null,
                )
                DecimalField(
                    height, { height = it }, "Height (cm)", Modifier.weight(1f),
                    isError = heightInvalid,
                    supportingText = if (heightInvalid) "Required" else null,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecimalField(
                    weight, { weight = it }, "Weight (kg)", Modifier.weight(1f),
                    isError = weightInvalid,
                    supportingText = if (weightInvalid) "Required" else null,
                )
                DecimalField(bodyFat, { bodyFat = it }, "Body fat % (opt)", Modifier.weight(1f))
            }
            if (bodyFat.toDoubleOrNull() != null) {
                Text(
                    "With body fat known, protein is set from lean mass rather than total " +
                        "weight — a better rule when there is fat to lose.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            Text("How active are you?", style = MaterialTheme.typography.titleSmall)
            ActivityLevel.entries.forEach { level ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = profile.activity == level,
                        onClick = { vm.edit { it.copy(activity = level) } },
                        label = { Text(level.label) },
                    )
                    Text(
                        level.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider()

            Text("What are you after?", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Goal.entries.forEach { goal ->
                    FilterChip(
                        selected = profile.goal == goal,
                        onClick = { vm.edit { it.copy(goal = goal) } },
                        label = { Text(goal.label) },
                    )
                }
            }

            HorizontalDivider()

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Adjust from my weight trend", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "The formula is a population average. Once there are a few weeks of " +
                            "weigh-ins and logs, what actually happened is a better guide.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = profile.useAdaptiveTdee,
                    onCheckedChange = { vm.edit { p -> p.copy(useAdaptiveTdee = it) } },
                )
            }

            DecimalField(manualKcal, { manualKcal = it }, "Set calories myself (optional)")
            if (manualKcal.toIntOrNull() != null) {
                Text(
                    "Overrides everything above. Macros are still split out of it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (preview != null) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("That gives you", style = MaterialTheme.typography.labelMedium)
                        Text(
                            "${preview.kcal.toInt()} kcal",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            "${preview.proteinG.toInt()} g protein · " +
                                "${preview.carbsG.toInt()} g carbs · ${preview.fatG.toInt()} g fat",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            if (showErrors && !edited.isComplete) {
                Text(
                    "Fill in birth year, height and weight to save.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = {
                    if (!edited.isComplete) {
                        showErrors = true
                        return@Button
                    }
                    vm.edit {
                        it.copy(
                            heightCm = edited.heightCm,
                            weightKg = edited.weightKg,
                            bodyFatPct = edited.bodyFatPct,
                            birthYear = edited.birthYear,
                            manualKcalTarget = edited.manualKcalTarget,
                        )
                    }
                    vm.save()
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            ) { Text("Save") }
        }
    }
}
