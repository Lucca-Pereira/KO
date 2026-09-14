package com.lucca.ko.ui.nav

import kotlinx.serialization.Serializable

/*
 * Type-safe navigation routes (navigation-compose 2.8). Destinations are Kotlin objects rather
 * than strings, so an argument that changes shape is a compile error instead of a crash.
 *
 * Deliberately no nullable arguments: nav 2.8 needs a custom NavType for a nullable Long, and a
 * sentinel is cheaper to read than the ceremony. `recipeId = 0L` means "new", and a blank `date`
 * means "not planning anything, just browsing".
 */

// ---- Bottom bar ---------------------------------------------------------------------

@Serializable data object PantryRoute

@Serializable data object PlanRoute

@Serializable data object RecipesRoute

@Serializable data object ShoppingRoute

// ---- Reached from a top bar or a list -----------------------------------------------

@Serializable data object SettingsRoute

@Serializable data object DuplicatesRoute

@Serializable data class RecipeDetailRoute(val recipeId: Long)

/** [recipeId] `0L` creates a new recipe. */
@Serializable data class RecipeEditRoute(val recipeId: Long = 0L)

/** Pick an existing library recipe to plan on [date] / [slot]. */
@Serializable data class RecipePickerRoute(val date: String, val slot: String)

/** A blank [date] means "save to the library only", with nothing added to the plan. */
@Serializable data class MealSearchRoute(val date: String = "", val slot: String = "")

@Serializable data class SuggestRoute(val date: String = "", val slot: String = "")
