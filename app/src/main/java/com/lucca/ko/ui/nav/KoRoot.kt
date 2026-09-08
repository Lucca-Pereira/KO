package com.lucca.ko.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lucca.ko.ui.dish.DishScreen
import com.lucca.ko.ui.mealsearch.MealSearchScreen
import com.lucca.ko.ui.pantry.PantryScreen
import com.lucca.ko.ui.plan.ManualDishScreen
import com.lucca.ko.ui.plan.PlanScreen
import com.lucca.ko.ui.settings.SettingsScreen
import com.lucca.ko.ui.shopping.ShoppingScreen
import com.lucca.ko.ui.suggest.SuggestScreen

private const val ARG_DATE = "date"
private const val ARG_SLOT = "slot"
private const val ARG_DISH = "dishId"

sealed class Dest(val route: String, val label: String, val icon: ImageVector) {
    data object Pantry : Dest("pantry", "Pantry", Icons.Filled.Kitchen)
    data object Plan : Dest("plan", "Plan", Icons.Filled.CalendarMonth)
    data object Shopping : Dest("shopping", "Shopping", Icons.Filled.ShoppingCart)
    data object Settings : Dest("settings", "Settings", Icons.Filled.Settings)
}

private val bottomDests = listOf(Dest.Pantry, Dest.Plan, Dest.Shopping, Dest.Settings)

object Routes {
    fun suggest(date: String, slot: String) = "suggest/$date/$slot"
    fun mealSearch(date: String, slot: String) = "mealSearch/$date/$slot"
    fun manualDish(date: String, slot: String) = "manualDish/$date/$slot"
    fun dish(dishId: Long) = "dish/$dishId"
}

@Composable
fun KoRoot() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = currentRoute in bottomDests.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    val currentDestination = backStack?.destination
                    bottomDests.forEach { dest ->
                        val selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Dest.Pantry.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Dest.Pantry.route) { PantryScreen() }

            composable(Dest.Plan.route) {
                PlanScreen(
                    onAddSuggested = { d, s -> navController.navigate(Routes.suggest(d, s)) },
                    onSearchMeal = { d, s -> navController.navigate(Routes.mealSearch(d, s)) },
                    onAddManual = { d, s -> navController.navigate(Routes.manualDish(d, s)) },
                    onOpenDish = { id -> navController.navigate(Routes.dish(id)) },
                )
            }

            composable(Dest.Shopping.route) { ShoppingScreen() }

            composable(Dest.Settings.route) { SettingsScreen() }

            composable(
                route = "suggest/{$ARG_DATE}/{$ARG_SLOT}",
                arguments = listOf(
                    navArgument(ARG_DATE) { type = NavType.StringType },
                    navArgument(ARG_SLOT) { type = NavType.StringType },
                ),
            ) { entry ->
                SuggestScreen(
                    date = entry.arguments?.getString(ARG_DATE).orEmpty(),
                    slot = entry.arguments?.getString(ARG_SLOT).orEmpty(),
                    onBack = { navController.popBackStack() },
                    onSaved = { dishId ->
                        navController.popBackStack()
                        navController.navigate(Routes.dish(dishId))
                    },
                )
            }

            composable(
                route = "mealSearch/{$ARG_DATE}/{$ARG_SLOT}",
                arguments = listOf(
                    navArgument(ARG_DATE) { type = NavType.StringType },
                    navArgument(ARG_SLOT) { type = NavType.StringType },
                ),
            ) { entry ->
                MealSearchScreen(
                    date = entry.arguments?.getString(ARG_DATE).orEmpty(),
                    slot = entry.arguments?.getString(ARG_SLOT).orEmpty(),
                    onBack = { navController.popBackStack() },
                    onSaved = { dishId ->
                        navController.popBackStack()
                        navController.navigate(Routes.dish(dishId))
                    },
                )
            }

            composable(
                route = "manualDish/{$ARG_DATE}/{$ARG_SLOT}",
                arguments = listOf(
                    navArgument(ARG_DATE) { type = NavType.StringType },
                    navArgument(ARG_SLOT) { type = NavType.StringType },
                ),
            ) { entry ->
                ManualDishScreen(
                    date = entry.arguments?.getString(ARG_DATE).orEmpty(),
                    slot = entry.arguments?.getString(ARG_SLOT).orEmpty(),
                    onBack = { navController.popBackStack() },
                    onSaved = { dishId ->
                        navController.popBackStack()
                        navController.navigate(Routes.dish(dishId))
                    },
                )
            }

            composable(
                route = "dish/{$ARG_DISH}",
                arguments = listOf(navArgument(ARG_DISH) { type = NavType.LongType }),
            ) { entry ->
                DishScreen(
                    dishId = entry.arguments?.getLong(ARG_DISH) ?: 0L,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
