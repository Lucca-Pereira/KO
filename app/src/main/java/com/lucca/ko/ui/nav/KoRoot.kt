package com.lucca.ko.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.lucca.ko.ui.budget.BudgetScreen
import com.lucca.ko.ui.common.LocalOpenMenu
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
import com.lucca.ko.ui.today.HomeScreen
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

/**
 * Main-menu destinations, in the order the drawer lists them.
 *
 * A drawer rather than a bottom bar: the app has more sections than a bar's five slots, and the
 * ones still to come (a recipe browser) would have pushed something useful off it.
 */
private sealed class Dest(
    val route: Any,
    val routeClass: KClass<*>,
    val label: String,
    val icon: ImageVector,
) {
    data object Today : Dest(TodayRoute, TodayRoute::class, "Today", Icons.Filled.WbSunny)
    data object Recipes : Dest(RecipesRoute, RecipesRoute::class, "Recipes", Icons.AutoMirrored.Filled.MenuBook)
    data object Groceries : Dest(ShoppingRoute, ShoppingRoute::class, "Groceries", Icons.Filled.ShoppingCart)
    data object Pantry : Dest(PantryRoute, PantryRoute::class, "Pantry", Icons.Filled.Kitchen)
    data object Meals : Dest(PlanRoute, PlanRoute::class, "Meals", Icons.Filled.CalendarMonth)
    data object Budget : Dest(BudgetRoute, BudgetRoute::class, "Budget", Icons.Filled.AccountBalanceWallet)
    data object Gym : Dest(NutritionRoute, NutritionRoute::class, "Gym", Icons.Filled.FitnessCenter)
    data object Settings : Dest(SettingsRoute, SettingsRoute::class, "Settings", Icons.Filled.Settings)
}

private val menuDests = listOf(
    Dest.Today, Dest.Recipes, Dest.Groceries, Dest.Pantry, Dest.Meals, Dest.Budget, Dest.Gym, Dest.Settings,
)

@Composable
fun KoRoot() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentDestination = backStack?.destination
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Back closes an open menu before it leaves the screen underneath.
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    CompositionLocalProvider(LocalOpenMenu provides { scope.launch { drawerState.open() } }) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(Modifier.width(280.dp)) {
                    Text(
                        "KO Kitchen",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 28.dp, top = 20.dp, bottom = 12.dp),
                    )
                    menuDests.forEach { dest ->
                        if (dest == Dest.Settings) {
                            HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
                        }
                        NavigationDrawerItem(
                            selected = currentDestination?.hierarchy
                                ?.any { it.hasRoute(dest.routeClass) } == true,
                            onClick = {
                                scope.launch { drawerState.close() }
                                navController.switchSection(dest.route)
                            },
                            icon = { Icon(dest.icon, contentDescription = null) },
                            label = { Text(dest.label) },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        )
                    }
                }
            },
        ) {
            Column(Modifier.fillMaxSize()) {
                NavHost(
                    navController = navController,
                    startDestination = TodayRoute,
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
                    composable<TodayRoute> {
                        HomeScreen(
                            onOpenRecipe = { id -> navController.navigate(RecipeDetailRoute(id)) },
                            onOpenMeals = { navController.switchSection(PlanRoute) },
                            onOpenGroceries = { navController.switchSection(ShoppingRoute) },
                            onOpenPantry = { navController.switchSection(PantryRoute) },
                            onOpenGym = { navController.switchSection(NutritionRoute) },
                        )
                    }

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

                    composable<BudgetRoute> { BudgetScreen() }

                    composable<SettingsRoute> { SettingsScreen() }

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

/** Jumps to a main-menu section, keeping each section's own state for when you come back. */
private fun NavController.switchSection(route: Any) {
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

/** "Tuesday · Dinner", for the picker's subtitle. [date] is a fixed-week slot date. */
private fun dayLabel(date: String, slot: String): String {
    val parsed = runCatching { java.time.LocalDate.parse(date) }.getOrNull() ?: return ""
    val weekday = parsed.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
    val slotLabel = slot.lowercase().replaceFirstChar { it.uppercase() }
    return "$weekday · $slotLabel"
}
