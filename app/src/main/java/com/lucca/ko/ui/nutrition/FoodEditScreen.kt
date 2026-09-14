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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.toRoute
import com.lucca.ko.data.db.FoodItem
import com.lucca.ko.data.db.FoodSource
import com.lucca.ko.data.repo.NutritionRepository
import com.lucca.ko.ui.koFactory
import com.lucca.ko.ui.nav.FoodEditRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FoodEditViewModel(
    private val nutrition: NutritionRepository,
    private val foodId: Long,
    private val barcode: String,
) : ViewModel() {

    private val _food = MutableStateFlow<FoodItem?>(null)
    val food = _food.asStateFlow()

    private val _savedId = MutableStateFlow<Long?>(null)
    val savedId = _savedId.asStateFlow()

    init {
        viewModelScope.launch {
            _food.value = if (foodId > 0) {
                nutrition.foodById(foodId)
            } else {
                // Prefilled with the barcode when a scan found the product but no nutrition:
                // the packet is in your hand, so typing the numbers off it is the fast path.
                FoodItem(name = "", normalizedName = "", barcode = barcode.ifBlank { null })
            }
        }
    }

    fun save(food: FoodItem) = viewModelScope.launch {
        _savedId.value = nutrition.saveFood(food.copy(source = FoodSource.MANUAL))
    }

    companion object {
        val Factory = koFactory { container ->
            val route = runCatching {
                createSavedStateHandle().toRoute<FoodEditRoute>()
            }.getOrNull()
            FoodEditViewModel(
                nutrition = container.nutritionRepository,
                foodId = route?.foodId ?: 0L,
                barcode = route?.barcode.orEmpty(),
            )
        }

        @Suppress("unused")
        private fun SavedStateHandle.route(): FoodEditRoute? =
            runCatching { toRoute<FoodEditRoute>() }.getOrNull()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodEditScreen(
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
    vm: FoodEditViewModel = viewModel(factory = FoodEditViewModel.Factory),
) {
    val loaded by vm.food.collectAsStateWithLifecycle()
    val savedId by vm.savedId.collectAsStateWithLifecycle()

    LaunchedEffect(savedId) { savedId?.let(onSaved) }

    val food = loaded ?: return

    var name by remember(food.id) { mutableStateOf(food.name) }
    var brand by remember(food.id) { mutableStateOf(food.brand.orEmpty()) }
    var kcal by remember(food.id) { mutableStateOf(food.kcalPer100.nonZero()) }
    var protein by remember(food.id) { mutableStateOf(food.proteinPer100.nonZero()) }
    var carbs by remember(food.id) { mutableStateOf(food.carbsPer100.nonZero()) }
    var fat by remember(food.id) { mutableStateOf(food.fatPer100.nonZero()) }
    var servingGrams by remember(food.id) {
        mutableStateOf(food.servingGrams?.toInt()?.toString().orEmpty())
    }
    var servingLabel by remember(food.id) { mutableStateOf(food.servingLabel.orEmpty()) }
    var isSupplement by remember(food.id) { mutableStateOf(food.isSupplement) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (food.id > 0) "Edit food" else "New food") },
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
                "Everything is per 100 g — that is how packets are labelled, and it makes any " +
                    "portion a multiplication.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!food.barcode.isNullOrBlank()) {
                Text(
                    "Barcode ${food.barcode}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = brand,
                onValueChange = { brand = it },
                label = { Text("Brand (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecimalField(kcal, { kcal = it }, "kcal", Modifier.weight(1f))
                DecimalField(protein, { protein = it }, "Protein", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecimalField(carbs, { carbs = it }, "Carbs", Modifier.weight(1f))
                DecimalField(fat, { fat = it }, "Fat", Modifier.weight(1f))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecimalField(servingGrams, { servingGrams = it }, "Serving (g)", Modifier.weight(1f))
                OutlinedTextField(
                    value = servingLabel,
                    onValueChange = { servingLabel = it },
                    label = { Text("Called") },
                    placeholder = { Text("1 scoop") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "A serving weight is what makes \"2 scoops\" loggable. Without it you can only " +
                    "log by grams.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("This is a supplement", Modifier.weight(1f))
                Switch(checked = isSupplement, onCheckedChange = { isSupplement = it })
            }

            Button(
                enabled = name.isNotBlank() && kcal.toDoubleOrNull() != null,
                onClick = {
                    vm.save(
                        food.copy(
                            name = name.trim(),
                            brand = brand.trim().ifEmpty { null },
                            kcalPer100 = kcal.toDoubleOrNull() ?: 0.0,
                            proteinPer100 = protein.toDoubleOrNull() ?: 0.0,
                            carbsPer100 = carbs.toDoubleOrNull() ?: 0.0,
                            fatPer100 = fat.toDoubleOrNull() ?: 0.0,
                            servingGrams = servingGrams.toDoubleOrNull(),
                            servingLabel = servingLabel.trim().ifEmpty { null },
                            isSupplement = isSupplement,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            ) { Text("Save") }
        }
    }
}

private fun Double.nonZero(): String = if (this == 0.0) "" else trimZero()
