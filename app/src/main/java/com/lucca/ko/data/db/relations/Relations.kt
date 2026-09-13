package com.lucca.ko.data.db.relations

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import com.lucca.ko.data.db.MealPlanEntry
import com.lucca.ko.data.db.Recipe
import com.lucca.ko.data.db.RecipeIngredient
import com.lucca.ko.data.db.RecipeStep
import com.lucca.ko.data.db.RecipeTag
import com.lucca.ko.data.db.Tag

/** A recipe with everything needed to render or edit it. */
data class RecipeWithDetails(
    @Embedded val recipe: Recipe,
    @Relation(parentColumn = "id", entityColumn = "dishId")
    val ingredients: List<RecipeIngredient>,
    @Relation(parentColumn = "id", entityColumn = "dishId")
    val steps: List<RecipeStep>,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(RecipeTag::class, parentColumn = "dishId", entityColumn = "tagId"),
    )
    val tags: List<Tag>,
) {
    /** Ingredients in the order the user arranged them. */
    val orderedIngredients: List<RecipeIngredient> get() = ingredients.sortedBy { it.sortOrder }

    /** Steps in cooking order. */
    val orderedSteps: List<RecipeStep> get() = steps.sortedBy { it.position }
}

/**
 * A planned meal joined to its recipe. [recipe] is null when the recipe has since been deleted —
 * the entry still renders from [MealPlanEntry.titleSnapshot], just not tappable.
 */
data class PlannedRecipe(
    @Embedded val entry: MealPlanEntry,
    @Relation(parentColumn = "dishId", entityColumn = "id")
    val recipe: Recipe?,
) {
    /** What to show on the plan: the live title if the recipe still exists, else the snapshot. */
    val displayTitle: String get() = recipe?.title ?: entry.titleSnapshot
}
