package com.lucca.ko.ui.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import com.lucca.ko.ui.common.LocalOpenSettings
import com.lucca.ko.ui.nutrition.FoodEditScreen
import com.lucca.ko.ui.nutrition.NutritionHubScreen
import com.lucca.ko.ui.nutrition.ProfileScreen
import com.lucca.ko.ui.nutrition.SupplementsScreen
import com.lucca.ko.ui.pantry.PantryScreen
import com.lucca.ko.ui.plan.PlanScreen
import com.lucca.ko.ui.recipes.DuplicatesScreen
import com.lucca.ko.ui.recipes.RecipeDetailScreen
import com.lucca.ko.ui.recipes.RecipeListScreen
import com.lucca.ko.ui.recipes.RecipePickerScreen
import com.lucca.ko.ui.recipes.edit.RecipeEditScreen
import com.lucca.ko.ui.settings.SettingsScreen
import com.lucca.ko.ui.shopping.ShoppingScreen
import kotlin.reflect.KClass

/**
 * Bottom-bar destinations.
 *
 * Settings isn't here: it left the bottom bar for a gear that [com.lucca.ko.ui.common.KoTopBar]
 * puts on every screen, root or not — a screen opened once a month didn't deserve one of
 * Material 3's five slots more than Recipes or Gym did.
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
    data object Nutrition :
        Dest(NutritionRoute, NutritionRoute::class, "Gym", Icons.Filled.FitnessCenter)
    data object Shopping :
        Dest(ShoppingRoute, ShoppingRoute::class, "Shopping", Icons.Filled.ShoppingCart)
}

private val bottomDests =
    listOf(Dest.Pantry, Dest.Plan, Dest.Recipes, Dest.Nutrition, Dest.Shopping)

@Composable
fun KoRoot() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentDestination = backStack?.destination
    val showBottomBar = bottomDests.any { dest ->
        currentDestination?.hierarchy?.any { it.hasRoute(dest.routeClass) } == true
    }

    CompositionLocalProvider(LocalOpenSettings provides { navController.navigate(SettingsRoute) }) {
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
                NavHost(
                    navController = navController,
                    startDestination = PantryRoute,
                    // Forward moves push in from the right and fade; back moves reverse that —
                    // a screen change reads as "you went somewhere," not a cut.
                    enterTransition = {
                        fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 6 }
                    },
                    exitTransition = { fadeOut(tween(150)) },
                    popEnterTransition = { fadeIn(tween(180)) },
                    popExitTransition = {
                        fadeOut(tween(150)) + slideOutHorizontally(tween(220)) { it / 6 }
                    },
                ) {
                    composable<PantryRoute> { PantryScreen() }

                    composable<PlanRoute> {
                        PlanScreen(
                            onPickFromLibrary = { d, s -> navController.navigate(RecipePickerRoute(d, s)) },
                            onNewRecipe = { _, _ -> navController.navigate(RecipeEditRoute()) },
                            onOpenRecipe = { id -> navController.navigate(RecipeDetailRoute(id)) },
                        )
                    }

                    composable<RecipesRoute> {
                        RecipeListScreen(
                            onOpenRecipe = { id -> navController.navigate(RecipeDetailRoute(id)) },
                            onNewRecipe = { navController.navigate(RecipeEditRoute()) },
                            onFindDuplicates = { navController.navigate(DuplicatesRoute) },
                        )
                    }

                    composable<NutritionRoute> {
                        NutritionHubScreen(
                            onOpenProfile = { navController.navigate(ProfileRoute) },
                            onOpenSupplements = { navController.navigate(SupplementsRoute) },
                            onNewFood = { navController.navigate(FoodEditRoute()) },
                        )
                    }

                    composable<ProfileRoute> {
                        ProfileScreen(onBack = { navController.popBackStack() })
                    }

                    composable<SupplementsRoute> {
                        SupplementsScreen(onBack = { navController.popBackStack() })
                    }

                    composable<FoodEditRoute> {
                        FoodEditScreen(
                            onBack = { navController.popBackStack() },
                            onSaved = { navController.popBackStack() },
                        )
                    }

                    composable<ShoppingRoute> { ShoppingScreen() }

                    composable<SettingsRoute> { SettingsScreen(onBack = { navController.popBackStack() }) }

                    composable<RecipeDetailRoute> {
                        RecipeDetailScreen(
                            onBack = { navController.popBackStack() },
                            onEdit = { id -> navController.navigate(RecipeEditRoute(id)) },
                        )
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
    val parsed = runCatching { java.time.LocalDate.parse(date) }.getOrNull() ?: return ""
    val weekday = parsed.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
    val month = parsed.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
    val slotLabel = slot.lowercase().replaceFirstChar { it.uppercase() }
    return "$weekday ${parsed.dayOfMonth} $month · $slotLabel"
}
