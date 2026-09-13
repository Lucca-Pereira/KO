package com.lucca.ko.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lucca.ko.data.repo.MealPlanRepository
import com.lucca.ko.data.repo.RecipeRepository
import com.lucca.ko.data.db.MealSlot
import com.lucca.ko.ui.koFactory
import java.time.LocalDate
import kotlinx.coroutines.launch

class ManualDishViewModel(
    private val recipes: RecipeRepository,
    private val plan: MealPlanRepository,
) : ViewModel() {
    fun save(
        title: String,
        url: String,
        ingredientsText: String,
        date: String,
        slot: String,
        onSaved: (Long) -> Unit,
    ) = viewModelScope.launch {
        // The recipe goes into the library; the plan gets a reference to it.
        val id = recipes.saveManualRecipe(
            title = title,
            url = url.ifBlank { null },
            ingredientLines = ingredientsText.lines(),
        )
        plan.addToPlan(
            recipeId = id,
            date = LocalDate.parse(date),
            slot = runCatching { MealSlot.valueOf(slot) }.getOrDefault(MealSlot.DINNER),
        )
        onSaved(id)
    }

    companion object {
        val Factory = koFactory { ManualDishViewModel(it.recipeRepository, it.mealPlanRepository) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualDishScreen(
    date: String,
    slot: String,
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
    vm: ManualDishViewModel = viewModel(factory = ManualDishViewModel.Factory),
) {
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var ingredients by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add dish manually") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Dish name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Recipe link (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = ingredients,
                onValueChange = { ingredients = it },
                label = { Text("Ingredients — one per line") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
            )
            Text(
                "Each line becomes an ingredient that is checked against your pantry.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { vm.save(title, url, ingredients, date, slot, onSaved) },
                enabled = title.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save to plan") }
        }
    }
}
