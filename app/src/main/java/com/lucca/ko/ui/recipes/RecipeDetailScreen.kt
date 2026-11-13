package com.lucca.ko.ui.recipes

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lucca.ko.data.db.MealSlot
import com.lucca.ko.domain.Availability
import com.lucca.ko.domain.ResolvedIngredient
import com.lucca.ko.domain.plan.FixedWeek
import com.lucca.ko.domain.units.MeasureScaler
import com.lucca.ko.ui.common.Dot
import com.lucca.ko.ui.common.EmptyState
import com.lucca.ko.ui.common.KoTopBar
import com.lucca.ko.ui.common.LoadingBox
import com.lucca.ko.ui.common.RecipeMetaLine
import com.lucca.ko.ui.common.availabilityColor
import com.lucca.ko.ui.plan.SlotPickerFields
import com.lucca.ko.ui.plan.label
import com.lucca.ko.ui.plan.rememberPlanView
import java.time.LocalDate
import kotlin.math.roundToInt

private val scaleOptions = listOf(0.5, 1.0, 2.0, 3.0, 4.0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDetailScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    vm: RecipeDetailViewModel = viewModel(factory = RecipeDetailViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val deleted by vm.deleted.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    var planning by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var justPlanned by remember { mutableStateOf<String?>(null) }
    val snackbarHost = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateWithLifecycle()

    LaunchedEffect(message) {
        message?.let {
            snackbarHost.showSnackbar(it)
            vm.clearMessage()
        }
    }

    LaunchedEffect(deleted) { if (deleted) onBack() }
    // The plan count is only read once per load, so refresh it when we come back from planning.
    LaunchedEffect(Unit) { vm.refreshPlanCount() }

    val recipe = state.recipe
    val steps = state.cookSteps

    // The screen stays lit while you cook, and only then.
    val view = LocalView.current
    DisposableEffect(session.cooking) {
        view.keepScreenOn = session.cooking
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(enabled = session.cooking) { vm.setCooking(false) }

    if (confirmDelete && recipe != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this recipe?") },
            text = {
                Text(
                    buildString {
                        append("\"${recipe.title}\" will be removed from your library. ")
                        when (state.planCount) {
                            0 -> append("It isn't on your meal plan.")
                            1 -> append(
                                "One planned meal uses it — that day will keep the name but " +
                                    "won't open anything.",
                            )
                            else -> append(
                                "${state.planCount} planned meals use it — those days will keep " +
                                    "the name but won't open anything.",
                            )
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }

    if (planning && recipe != null) {
        PlanTargetSheet(
            onDismiss = { planning = false },
            onConfirm = { date, slot ->
                vm.addToPlan(date, slot)
                planning = false
                justPlanned = "Added to ${slot.label().lowercase()} on ${date.dayOfWeek.label()}"
            },
        )
    }

    LaunchedEffect(justPlanned) {
        if (justPlanned != null) {
            snackbarHost.showSnackbar(justPlanned!!)
            justPlanned = null
        }
    }

    if (session.cooking && recipe != null) {
        CookMode(
            title = recipe.title,
            steps = steps,
            current = session.currentStep,
            ingredients = state.ingredients,
            scale = session.scale,
            crossed = session.crossed,
            timers = session.timers,
            onStep = vm::setStep,
            onToggleIngredient = vm::toggleCrossed,
            onStartTimer = vm::startTimer,
            onCancelTimer = vm::cancelTimer,
            onClose = { vm.setCooking(false) },
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            KoTopBar(
                title = recipe?.title ?: "Recipe",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (recipe != null) {
                        IconButton(onClick = vm::toggleFavourite) {
                            Icon(
                                if (recipe.isFavourite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = if (recipe.isFavourite) {
                                    "Remove from favourites"
                                } else {
                                    "Add to favourites"
                                },
                                tint = if (recipe.isFavourite) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        IconButton(onClick = { planning = true }) {
                            Icon(Icons.Filled.CalendarMonth, contentDescription = "Add to plan")
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Edit recipe") },
                                onClick = { menuOpen = false; onEdit(recipe.id) },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete recipe") },
                                onClick = { menuOpen = false; confirmDelete = true },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = { TimersBar(session.timers, onCancel = vm::cancelTimer) },
        floatingActionButton = {
            if (recipe != null && steps.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { vm.setStep(0); vm.setCooking(true) },
                    icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    text = { Text("Start cooking") },
                )
            }
        },
    ) { padding ->
        when {
            state.loading -> LoadingBox(Modifier.padding(padding))

            recipe == null -> EmptyState(
                title = "Recipe not found",
                subtitle = "It may have been deleted.",
                modifier = Modifier.padding(padding),
            )

            else -> LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                item {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RecipeMetaLine(
                            totalMinutes = listOfNotNull(recipe.prepMinutes, recipe.cookMinutes)
                                .takeIf { it.isNotEmpty() }?.sum(),
                            servings = (recipe.servings * session.scale).roundToInt().coerceAtLeast(1),
                            timesCooked = recipe.timesCooked,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.tags.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                state.tags.take(4).forEach { tag ->
                                    SuggestionChip(onClick = {}, label = { Text(tag.name) })
                                }
                            }
                        }
                        if (!recipe.sourceUrl.isNullOrBlank()) {
                            AssistChip(
                                onClick = { openUrl(context, recipe.sourceUrl) },
                                label = { Text("Open original") },
                                leadingIcon = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                            )
                        }
                        if (!recipe.notes.isNullOrBlank()) {
                            Text(recipe.notes, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                item {
                    NutritionCard(
                        kcal = recipe.kcalPerServing,
                        proteinG = recipe.proteinG,
                        carbsG = recipe.carbsG,
                        fatG = recipe.fatG,
                        note = recipe.macroNote,
                        servings = recipe.servings,
                        onLog = { vm.logServings(1.0) },
                    )
                }

                item {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Ingredients",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.ingredients.isNotEmpty()) {
                            Text(
                                if (state.missingCount == 0) {
                                    "All in stock"
                                } else {
                                    "${state.missingCount} missing"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = availabilityColor(
                                    if (state.missingCount == 0) Availability.HAVE else Availability.MISSING,
                                ),
                            )
                        }
                    }
                }

                if (state.ingredients.isNotEmpty()) {
                    item {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Scale",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            scaleOptions.forEach { factor ->
                                FilterChip(
                                    selected = session.scale == factor,
                                    onClick = { vm.setScale(factor) },
                                    label = { Text(MeasureScaler.label(factor)) },
                                )
                            }
                        }
                    }
                }

                if (state.ingredients.isEmpty()) {
                    item {
                        Text(
                            "No ingredients yet — tap the pencil to add some.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else {
                    items(state.ingredients, key = { it.dishIngredientId }) { ing ->
                        val crossed = ing.dishIngredientId in session.crossed
                        IngredientRow(
                            ing = ing,
                            scale = session.scale,
                            crossed = crossed,
                            onToggle = { vm.toggleCrossed(ing.dishIngredientId) },
                            onRanOut = if (crossed) null else ({ vm.markRanOut(ing.dishIngredientId) }),
                            onHave = if (crossed) null else ({ vm.markHave(ing.dishIngredientId) }),
                        )
                    }
                }

                if (state.missingCount > 0) {
                    item {
                        Button(
                            onClick = vm::addAllMissingToShopping,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        ) { Text("Add ${state.missingCount} missing to shopping list") }
                    }
                }

                if (steps.isNotEmpty()) {
                    item {
                        Text(
                            "Directions",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    itemsIndexed(steps) { index, step ->
                        StepRow(
                            number = index + 1,
                            step = step,
                            highlighted = index == session.currentStep,
                            onClick = { vm.setStep(index) },
                            onStartTimer = vm::startTimer,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One ingredient: bold scaled amount then the name. Tapping crosses it off. The pantry buttons
 * are offered only when [onRanOut] / [onHave] are given.
 */
@Composable
internal fun IngredientRow(
    ing: ResolvedIngredient,
    scale: Double,
    crossed: Boolean,
    onToggle: () -> Unit,
    onRanOut: (() -> Unit)? = null,
    onHave: (() -> Unit)? = null,
) {
    val amount = MeasureScaler.scale(ing.measure, scale)?.takeIf { it.isNotBlank() }
    val text = buildAnnotatedString {
        if (amount != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(amount) }
            append("  ")
        }
        append(ing.rawName.replaceFirstChar { it.uppercase() })
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Dot(availabilityColor(ing.availability))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (crossed) TextDecoration.LineThrough else null,
            color = if (crossed) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        if (ing.availability == Availability.MISSING) {
            if (onHave != null) TextButton(onClick = onHave) { Text("Have it") }
        } else if (onRanOut != null) {
            TextButton(onClick = onRanOut) { Text("Ran out") }
        }
    }
}

/** A direction. Tap to make it the current step; times in it can be started as timers. */
@Composable
private fun StepRow(
    number: Int,
    step: CookStep,
    highlighted: Boolean,
    onClick: () -> Unit,
    onStartTimer: (String, Int) -> Unit,
) {
    Surface(
        color = if (highlighted) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "$number.",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stepText(step.text), style = MaterialTheme.typography.bodyLarge)
                TimerChips(step, onStartTimer)
            }
        }
    }
}

/** Pick a day and a slot to plan this recipe on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanTargetSheet(
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, MealSlot) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val planView = rememberPlanView()
    var week by remember(planView.activeWeek) { mutableStateOf(planView.activeWeek) }
    var day by remember { mutableStateOf(LocalDate.now().dayOfWeek) }
    var slot by remember { mutableStateOf(MealSlot.DINNER) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Add to plan", style = MaterialTheme.typography.titleLarge)

            SlotPickerFields(
                weeks = planView.weeks,
                week = week,
                onWeek = { week = it },
                day = day,
                onDay = { day = it },
                slot = slot,
                onSlot = { slot = it },
            )

            Button(
                onClick = { onConfirm(FixedWeek.dateFor(week, day), slot) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add") }
        }
    }
}

/** Per-serving macros, when Claude has estimated them via an imported recipe file. */
@Composable
private fun NutritionCard(
    kcal: Double?,
    proteinG: Double?,
    carbsG: Double?,
    fatG: Double?,
    note: String?,
    servings: Int,
    onLog: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Per serving", style = MaterialTheme.typography.labelMedium)
            if (kcal == null) {
                Text(
                    "Macros not worked out yet — ask Claude and import the numbers " +
                        "(Settings has how).",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    "${kcal.toInt()} kcal",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "P ${proteinG?.toInt() ?: 0} · C ${carbsG?.toInt() ?: 0} · " +
                        "F ${fatG?.toInt() ?: 0}   (serves $servings)",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!note.isNullOrBlank()) {
                    Text(note, style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = onLog) { Text("Log a serving") }
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    val uri: Uri = runCatching { url.toUri() }.getOrNull() ?: return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: ActivityNotFoundException) {
    }
}
