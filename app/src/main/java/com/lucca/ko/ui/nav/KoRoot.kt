package com.lucca.ko.ui.nav

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.lucca.ko.KoApp
import com.lucca.ko.ui.common.NasStatusBanner
import com.lucca.ko.ui.mealsearch.MealSearchScreen
import com.lucca.ko.ui.pantry.PantryScreen
import com.lucca.ko.ui.plan.PlanScreen
import com.lucca.ko.ui.recipes.DuplicatesScreen
import com.lucca.ko.ui.recipes.RecipeDetailScreen
import com.lucca.ko.ui.recipes.RecipeListScreen
import com.lucca.ko.ui.recipes.RecipePickerScreen
import com.lucca.ko.ui.recipes.chat.RecipeChatScreen
import com.lucca.ko.ui.recipes.edit.RecipeEditScreen
import com.lucca.ko.ui.settings.SettingsScreen
import com.lucca.ko.ui.shopping.ShoppingScreen
import com.lucca.ko.ui.suggest.SuggestScreen
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.reflect.KClass

/**
 * Bottom-bar destinations.
 *
 * Settings left the bottom bar for a gear in each root screen's top bar: it gets opened about
 * once a month, while Recipes — which took the slot — is a daily screen. Nutrition joins here
 * in Phase 5, which is the fifth and last slot Material 3 allows.
 */
private sealed class Dest(
    val route: Any,
    val routeClass: KClass<*>,
    val label: String,
    val icon: ImageVector,
) {
    data object Pantry : Dest(PantryRoute, PantryRoute::class, "Pantry", Icons.Filled.Kitchen)
    data object Plan : Dest(PlanRoute, PlanRoute::class, "Plan", Icons.Filled.CalendarMonth)
    data object Recipes : Dest(RecipesRoute, RecipesRoute::class, "Recipes", Icons.AutoMirrored.Filled.MenuBook)
    data object Shopping :
        Dest(ShoppingRoute, ShoppingRoute::class, "Shopping", Icons.Filled.ShoppingCart)
}

private val bottomDests = listOf(Dest.Pantry, Dest.Plan, Dest.Recipes, Dest.Shopping)

@Composable
fun KoRoot() {
    val navController = rememberNavController()
    // One health check for the whole app; every screen used to invent its own error string, so
    // one NAS being off looked like five unrelated problems.
    val nasStatus = LocalContext.current.applicationContext.let { (it as KoApp).container.nasStatus }
    LaunchedEffect(Unit) { nasStatus.refreshIfStale() }
    val backStack by navController.currentBackStackEntryAsState()
    val currentDestination = backStack?.destination
    val showBottomBar = bottomDests.any { dest ->
        currentDestination?.hierarchy?.any { it.hasRoute(dest.routeClass) } == true
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomDests.forEach { dest ->
                        val selected =
                            currentDestination?.hierarchy?.any { it.hasRoute(dest.routeClass) } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.switchTab(dest.route) },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            NasStatusBanner(nasStatus)
            NavHost(
                navController = navController,
                startDestination = PantryRoute,
            ) {
                composable<PantryRoute> { PantryScreen() }

                composable<PlanRoute> {
                    PlanScreen(
                        onPickFromLibrary = { d, s -> navController.navigate(RecipePickerRoute(d, s)) },
                        onAddSuggested = { d, s -> navController.navigate(SuggestRoute(d, s)) },
                        onSearchMeal = { d, s -> navController.navigate(MealSearchRoute(d, s)) },
                        onNewRecipe = { _, _ -> navController.navigate(RecipeEditRoute()) },
                        onOpenRecipe = { id -> navController.navigate(RecipeDetailRoute(id)) },
                        onOpenSettings = { navController.navigate(SettingsRoute) },
                    )
                }

                composable<RecipesRoute> {
                    RecipeListScreen(
                        onOpenRecipe = { id -> navController.navigate(RecipeDetailRoute(id)) },
                        onNewRecipe = { navController.navigate(RecipeEditRoute()) },
                        onSearchMealDb = { navController.navigate(MealSearchRoute()) },
                        onFindDuplicates = { navController.navigate(DuplicatesRoute) },
                        onOpenSettings = { navController.navigate(SettingsRoute) },
                    )
                }

                composable<ShoppingRoute> { ShoppingScreen() }

                composable<SettingsRoute> { SettingsScreen() }

                composable<RecipeDetailRoute> {
                    RecipeDetailScreen(
                        onBack = { navController.popBackStack() },
                        onEdit = { id -> navController.navigate(RecipeEditRoute(id)) },
                        onChat = { id -> navController.navigate(RecipeChatRoute(id)) },
                    )
                }

                composable<RecipeChatRoute> {
                    RecipeChatScreen(onBack = { navController.popBackStack() })
                }

                composable<RecipeEditRoute> {
                    RecipeEditScreen(
                        onBack = { navController.popBackStack() },
                        onSaved = { id -> navController.openRecipeReplacingCurrent(id) },
                    )
                }

                composable<RecipePickerRoute> { entry ->
                    val route = entry.toRoute<RecipePickerRoute>()
                    RecipePickerScreen(
                        date = route.date,
                        slot = route.slot,
                        dayLabel = dayLabel(route.date, route.slot),
                        onBack = { navController.popBackStack() },
                        onAdded = { navController.popBackStack() },
                    )
                }

                composable<DuplicatesRoute> {
                    DuplicatesScreen(
                        onBack = { navController.popBackStack() },
                        onOpenRecipe = { id -> navController.navigate(RecipeDetailRoute(id)) },
                    )
                }

                composable<SuggestRoute> { entry ->
                    val route = entry.toRoute<SuggestRoute>()
                    SuggestScreen(
                        date = route.date,
                        slot = route.slot,
                        onBack = { navController.popBackStack() },
                        onSaved = { id -> navController.openRecipeReplacingCurrent(id) },
                    )
                }

                composable<MealSearchRoute> { entry ->
                    val route = entry.toRoute<MealSearchRoute>()
                    MealSearchScreen(
                        date = route.date,
                        slot = route.slot,
                        onBack = { navController.popBackStack() },
                        onSaved = { id -> navController.openRecipeReplacingCurrent(id) },
                    )
                }
            }
        }
    }
}

private fun NavController.switchTab(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * After saving or importing a recipe, show it — but leave the back button going where the user
 * came from rather than back into the form they just finished with.
 */
private fun NavController.openRecipeReplacingCurrent(recipeId: Long) {
    popBackStack()
    navigate(RecipeDetailRoute(recipeId))
}

/** "Tuesday 16 Sep · Dinner", for the picker's subtitle. */
private fun dayLabel(date: String, slot: String): String {
    val parsed = runCatching { LocalDate.parse(date) }.getOrNull() ?: return ""
    val weekday = parsed.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
    val month = parsed.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    val slotLabel = slot.lowercase().replaceFirstChar { it.uppercase() }
    return "$weekday ${parsed.dayOfMonth} $month · $slotLabel"
}
