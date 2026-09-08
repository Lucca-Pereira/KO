package com.lucca.ko.ui.mealsearch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.lucca.ko.data.remote.MealSummary
import com.lucca.ko.ui.common.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealSearchScreen(
    date: String,
    slot: String,
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
    vm: MealSearchViewModel = viewModel(factory = MealSearchViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    androidx.compose.runtime.LaunchedEffect(state.savedDishId) {
        state.savedDishId?.let(onSaved)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search recipes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("e.g. chicken curry") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(query) }),
            )

            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.error != null -> EmptyState(title = "Search failed", subtitle = state.error)
                state.results.isEmpty() && state.searched ->
                    EmptyState(title = "No recipes found", subtitle = "Try a different dish name.")
                state.results.isEmpty() ->
                    EmptyState(title = "Search TheMealDB", subtitle = "Look up a recipe by name to add it to your plan.")
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(state.results, key = { it.id }) { meal ->
                        MealRow(meal, saving = state.savingId == meal.id) {
                            vm.pick(meal.id, date, slot)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MealRow(meal: MealSummary, saving: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !saving, onClick = onClick).padding(16.dp, 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = meal.thumbUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
        )
        Text(meal.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (saving) CircularProgressIndicator(Modifier.size(20.dp))
    }
}
