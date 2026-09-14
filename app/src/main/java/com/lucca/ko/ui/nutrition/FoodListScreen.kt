package com.lucca.ko.ui.nutrition

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lucca.ko.data.db.FoodItem
import com.lucca.ko.data.scan.BarcodeScanner
import com.lucca.ko.ui.common.EmptyState
import com.lucca.ko.ui.common.LoadingBox
import com.lucca.ko.ui.common.SectionHeader
import kotlinx.coroutines.launch

@Composable
fun FoodListScreen(
    onPickFood: (FoodItem) -> Unit,
    onNewFood: () -> Unit,
    vm: FoodListViewModel = viewModel(factory = FoodListViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scanned by vm.scanned.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // startScan() launches an activity, so the scanner belongs to the composable layer rather
    // than to a repository holding the application context.
    val scanner = remember(context) { BarcodeScanner(context) }

    LaunchedEffect(Unit) { scanner.prefetchModule() }

    LaunchedEffect(scanned) {
        scanned?.let {
            onPickFood(it)
            vm.clearScanned()
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = vm::setQuery,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text("Search foods") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                enabled = !state.scanning,
                onClick = {
                    scope.launch {
                        when (val outcome = scanner.scan()) {
                            is BarcodeScanner.Outcome.Scanned -> vm.onBarcode(outcome.barcode)
                            is BarcodeScanner.Outcome.Failed -> vm.onScanFailed(outcome.message)
                            // Backing out of the scanner is not an error worth reporting.
                            BarcodeScanner.Outcome.Cancelled -> Unit
                        }
                    }
                },
            ) {
                if (state.scanning) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Filled.QrCodeScanner,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text("Scan barcode", Modifier.padding(start = 8.dp))
            }
            OutlinedButton(onClick = onNewFood) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Add by hand", Modifier.padding(start = 8.dp))
            }
        }

        state.scanError?.let { error ->
            Row(
                Modifier.fillMaxWidth().padding(16.dp, 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { vm.dismissError(); onNewFood() }) { Text("Add") }
            }
        }

        when {
            state.loading -> LoadingBox()

            state.results.isEmpty() && state.recent.isEmpty() && state.query.isNotBlank() ->
                EmptyState(
                    title = "No match",
                    subtitle = "Scan the packet, or add it by hand once and it's there forever.",
                )

            else -> LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                if (state.recent.isNotEmpty()) {
                    item {
                        SectionHeader("Recent", Modifier.padding(horizontal = 16.dp))
                    }
                    items(state.recent, key = { "recent-${it.id}" }) { food ->
                        FoodRow(food, onClick = { onPickFood(food) }, onFavourite = { vm.toggleFavourite(food) })
                    }
                }
                if (state.results.isNotEmpty()) {
                    item {
                        SectionHeader(
                            if (state.query.isBlank()) "All foods" else "Matches",
                            Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    items(state.results, key = { it.id }) { food ->
                        FoodRow(food, onClick = { onPickFood(food) }, onFavourite = { vm.toggleFavourite(food) })
                    }
                }
            }
        }
    }
}

@Composable
private fun FoodRow(food: FoodItem, onClick: () -> Unit, onFavourite: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(food.brand, food.name).joinToString(" "),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                buildList {
                    add("${food.kcalPer100.toInt()} kcal / 100 g")
                    add("P ${food.proteinPer100.toInt()}")
                    add("C ${food.carbsPer100.toInt()}")
                    add("F ${food.fatPer100.toInt()}")
                    food.servingLabel?.let { add(it) }
                }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onFavourite, modifier = Modifier.size(40.dp)) {
            Icon(
                if (food.isFavourite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (food.isFavourite) "Unfavourite" else "Favourite",
                modifier = Modifier.size(18.dp),
                tint = if (food.isFavourite) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
