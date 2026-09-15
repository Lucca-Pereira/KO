package com.lucca.ko.data.remote.claude

import com.lucca.ko.data.db.MealSlot
import com.lucca.ko.data.db.RecipeSource
import com.lucca.ko.data.repo.MealPlanRepository
import com.lucca.ko.data.repo.PantryRepository
import com.lucca.ko.data.repo.RecipeRepository
import com.lucca.ko.data.repo.RevisionRepository
import com.lucca.ko.data.repo.ShoppingRepository
import com.lucca.ko.domain.recipe.IngredientDraft
import com.lucca.ko.domain.recipe.RecipeDiff
import com.lucca.ko.domain.recipe.RecipeDraft
import com.lucca.ko.domain.recipe.StepDraft
import com.lucca.ko.domain.recipe.toDraft
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What running a tool call produced. */
sealed interface ToolOutcome {
    /** A read-only tool: already done, feed [resultJson] back to Claude and keep going. */
    data class Immediate(val resultJson: String) : ToolOutcome

    /** A tool that would write something: nothing has happened yet, the user decides first. */
    data class NeedsConfirmation(val toolName: String, val summary: String, val argsJson: String) : ToolOutcome
}

/**
 * The tools the recipe agent can call, and what running each one actually does.
 *
 * Split the same way the old NAS brain's tools were: read-only tools ([get_pantry][GET_PANTRY])
 * run immediately and their result goes straight back to the model. Anything that would write to
 * the user's data ([save_recipe][SAVE_RECIPE], [add_to_meal_plan][ADD_TO_MEAL_PLAN],
 * [add_to_shopping_list][ADD_TO_SHOPPING_LIST]) stops instead and hands back a summary — the
 * write only happens if the user taps Apply on it.
 */
class KitchenTools(
    private val pantryRepository: PantryRepository,
    private val recipeRepository: RecipeRepository,
    private val mealPlanRepository: MealPlanRepository,
    private val shoppingRepository: ShoppingRepository,
    private val revisionRepository: RevisionRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val definitions: List<ClaudeTool> = listOf(
        ClaudeTool(
            name = GET_PANTRY,
            description = "Returns everything currently in the user's pantry, with its stock " +
                "status (IN_STOCK, LOW or OUT) and category. Call this before suggesting dishes " +
                "so you only suggest things they can actually make, and check it again if they " +
                "mention wanting to use up something specific.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {})
            },
        ),
        ClaudeTool(
            name = SAVE_RECIPE,
            description = "Saves a complete recipe to the user's library, either as a new one " +
                "or replacing the recipe they currently have open. The user reviews the exact " +
                "change before anything is written, so always send the whole recipe — title, " +
                "every ingredient and every step — not a partial edit.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "recipeId",
                            buildJsonObject {
                                put("type", "integer")
                                put(
                                    "description",
                                    "Omit to create a new recipe. Pass an id only when told the " +
                                        "current recipe's id and you are replacing it.",
                                )
                            },
                        )
                        put("title", buildJsonObject { put("type", "string") })
                        put("servings", buildJsonObject { put("type", "integer") })
                        put("prepMinutes", buildJsonObject { put("type", "integer") })
                        put("cookMinutes", buildJsonObject { put("type", "integer") })
                        put("notes", buildJsonObject { put("type", "string") })
                        put(
                            "tags",
                            buildJsonObject {
                                put("type", "array")
                                put("items", buildJsonObject { put("type", "string") })
                            },
                        )
                        put(
                            "ingredients",
                            buildJsonObject {
                                put("type", "array")
                                put(
                                    "items",
                                    buildJsonObject {
                                        put("type", "object")
                                        put(
                                            "properties",
                                            buildJsonObject {
                                                put("name", buildJsonObject { put("type", "string") })
                                                put("amount", buildJsonObject { put("type", "string") })
                                                put("optional", buildJsonObject { put("type", "boolean") })
                                            },
                                        )
                                        put("required", buildJsonArray { add("name") })
                                    },
                                )
                            },
                        )
                        put(
                            "steps",
                            buildJsonObject {
                                put("type", "array")
                                put(
                                    "items",
                                    buildJsonObject {
                                        put("type", "object")
                                        put(
                                            "properties",
                                            buildJsonObject {
                                                put("text", buildJsonObject { put("type", "string") })
                                                put("minutes", buildJsonObject { put("type", "integer") })
                                            },
                                        )
                                        put("required", buildJsonArray { add("text") })
                                    },
                                )
                            },
                        )
                    },
                )
                put("required", buildJsonArray { add("title"); add("ingredients"); add("steps") })
            },
        ),
        ClaudeTool(
            name = ADD_TO_MEAL_PLAN,
            description = "Adds a recipe to the meal plan on a specific day and slot. The " +
                "recipe is matched by title against the user's library, so call save_recipe " +
                "first if it isn't there yet.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put("title", buildJsonObject { put("type", "string") })
                        put(
                            "date",
                            buildJsonObject { put("type", "string"); put("description", "yyyy-MM-dd") },
                        )
                        put(
                            "slot",
                            buildJsonObject {
                                put("type", "string")
                                put(
                                    "enum",
                                    buildJsonArray {
                                        add("BREAKFAST"); add("LUNCH"); add("DINNER"); add("OTHER")
                                    },
                                )
                            },
                        )
                        put("servings", buildJsonObject { put("type", "number") })
                    },
                )
                put("required", buildJsonArray { add("title"); add("date"); add("slot") })
            },
        ),
        ClaudeTool(
            name = ADD_TO_SHOPPING_LIST,
            description = "Adds one or more items to the user's shopping list.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "items",
                            buildJsonObject {
                                put("type", "array")
                                put("items", buildJsonObject { put("type", "string") })
                            },
                        )
                    },
                )
                put("required", buildJsonArray { add("items") })
            },
        ),
    )

    suspend fun run(call: ClaudeBlock.ToolUse): ToolOutcome {
        val argsJson = call.input.toString()
        return when (call.name) {
            GET_PANTRY -> ToolOutcome.Immediate(pantrySnapshotJson())
            SAVE_RECIPE -> ToolOutcome.NeedsConfirmation(SAVE_RECIPE, saveRecipeSummary(call.input), argsJson)
            ADD_TO_MEAL_PLAN ->
                ToolOutcome.NeedsConfirmation(ADD_TO_MEAL_PLAN, addToPlanSummary(call.input), argsJson)
            ADD_TO_SHOPPING_LIST ->
                ToolOutcome.NeedsConfirmation(ADD_TO_SHOPPING_LIST, addToShoppingSummary(call.input), argsJson)
            else -> ToolOutcome.Immediate("""{"error":"unknown tool ${call.name}"}""")
        }
    }

    /** Runs the write a confirmed proposal describes. Returns a short line for the chat log. */
    suspend fun applyConfirmed(toolName: String, argsJson: String): String = when (toolName) {
        SAVE_RECIPE -> applySaveRecipe(json.decodeFromString(SaveRecipeArgs.serializer(), argsJson))
        ADD_TO_MEAL_PLAN -> applyAddToPlan(json.decodeFromString(AddToPlanArgs.serializer(), argsJson))
        ADD_TO_SHOPPING_LIST ->
            applyAddToShopping(json.decodeFromString(AddToShoppingArgs.serializer(), argsJson))
        else -> "Nothing to do."
    }

    /** The diff shown in the review dialog for a pending [SAVE_RECIPE] proposal. */
    suspend fun diffForSaveRecipe(argsJson: String): RecipeDiff.Result {
        val args = json.decodeFromString(SaveRecipeArgs.serializer(), argsJson)
        val current = args.recipeId.takeIf { it > 0 }?.let { recipeRepository.observeRecipe(it).first() }
        return RecipeDiff.compare(
            beforeTitle = current?.recipe?.title.orEmpty(),
            afterTitle = args.title,
            beforeServings = current?.recipe?.servings ?: args.servings,
            afterServings = args.servings,
            beforeIngredients = current?.orderedIngredients.orEmpty()
                .map { RecipeDiff.IngredientLine(it.rawName, it.measure.orEmpty()) },
            afterIngredients = args.ingredients.map { RecipeDiff.IngredientLine(it.name, it.amount) },
            beforeSteps = current?.orderedSteps.orEmpty().map { it.text },
            afterSteps = args.steps.map { it.text },
        )
    }

    // ---- get_pantry ------------------------------------------------------------------

    private suspend fun pantrySnapshotJson(): String {
        val items = pantryRepository.snapshot()
        if (items.isEmpty()) return """{"items":[],"note":"The pantry is empty."}"""
        val array = buildJsonArray {
            items.forEach { item ->
                add(
                    buildJsonObject {
                        put("name", item.name)
                        put("status", item.status.name)
                        put("category", item.category)
                    },
                )
            }
        }
        return buildJsonObject { put("items", array) }.toString()
    }

    // ---- save_recipe -------------------------------------------------------------------

    private fun saveRecipeSummary(input: JsonElement): String {
        val args = runCatching { json.decodeFromString(SaveRecipeArgs.serializer(), input.toString()) }
            .getOrNull() ?: return "Save a recipe"
        return if (args.recipeId > 0) "Update \"${args.title}\"" else "Save \"${args.title}\" to your library"
    }

    private suspend fun applySaveRecipe(args: SaveRecipeArgs): String {
        val existing = args.recipeId.takeIf { it > 0 }
            ?.let { recipeRepository.observeRecipe(it).first() }
        // Cheap insurance: the same snapshot a manual edit takes, so an edit the agent got
        // wrong is one tap from undo rather than a retyped recipe.
        existing?.let { revisionRepository.snapshot(args.recipeId, it, reason = "agent edit") }
        val base = existing?.toDraft()
        val draft = (base ?: RecipeDraft(source = RecipeSource.AI)).copy(
            title = args.title,
            servingsText = args.servings.toString(),
            prepText = args.prepMinutes?.toString().orEmpty(),
            cookText = args.cookMinutes?.toString().orEmpty(),
            notes = args.notes.orEmpty(),
            ingredients = args.ingredients.mapIndexed { i, ing ->
                IngredientDraft(key = -(i + 1L), name = ing.name, amount = ing.amount, optional = ing.optional)
            },
            steps = args.steps.mapIndexed { i, s ->
                StepDraft(key = -(i + 1L), text = s.text, minutesText = s.minutes?.toString().orEmpty())
            },
            tags = (base?.tags.orEmpty() + args.tags).distinctBy { it.lowercase() },
            // Ingredients just changed wholesale; a macro estimate against the old ones would be
            // a lie the nutrition card would happily display.
            kcalPerServing = null,
            proteinG = null,
            carbsG = null,
            fatG = null,
            macroSource = null,
            macroNote = null,
        )
        recipeRepository.saveDraft(draft)
        return if (args.recipeId > 0) "Updated \"${args.title}\"." else "Saved \"${args.title}\" to your library."
    }

    // ---- add_to_meal_plan ---------------------------------------------------------------

    private fun addToPlanSummary(input: JsonElement): String {
        val args = runCatching { json.decodeFromString(AddToPlanArgs.serializer(), input.toString()) }
            .getOrNull() ?: return "Add to the meal plan"
        val slot = args.slot.lowercase().replaceFirstChar { it.uppercase() }
        return "Add \"${args.title}\" to $slot on ${args.date}"
    }

    private suspend fun applyAddToPlan(args: AddToPlanArgs): String {
        val match = findRecipeByTitle(args.title)
            ?: return "Couldn't find \"${args.title}\" in your library — save it first."
        val date = runCatching { LocalDate.parse(args.date) }.getOrNull()
            ?: return "That date didn't parse."
        val slot = runCatching { MealSlot.valueOf(args.slot) }.getOrDefault(MealSlot.DINNER)
        mealPlanRepository.addToPlan(match, date, slot, args.servings ?: 1.0)
        return "Added \"${args.title}\" to the plan."
    }

    private suspend fun findRecipeByTitle(title: String): Long? {
        val matches = recipeRepository.searchLibrary(title).first()
        return matches.firstOrNull { it.recipe.title.equals(title, ignoreCase = true) }?.recipe?.id
            ?: matches.firstOrNull()?.recipe?.id
    }

    // ---- add_to_shopping_list ------------------------------------------------------------

    private fun addToShoppingSummary(input: JsonElement): String {
        val args = runCatching { json.decodeFromString(AddToShoppingArgs.serializer(), input.toString()) }
            .getOrNull() ?: return "Add items to the shopping list"
        return "Add ${args.items.joinToString(", ")} to your shopping list"
    }

    private suspend fun applyAddToShopping(args: AddToShoppingArgs): String {
        args.items.forEach { shoppingRepository.addManualShoppingItem(it) }
        return "Added ${args.items.size} item${if (args.items.size == 1) "" else "s"} to your shopping list."
    }

    companion object {
        const val GET_PANTRY = "get_pantry"
        const val SAVE_RECIPE = "save_recipe"
        const val ADD_TO_MEAL_PLAN = "add_to_meal_plan"
        const val ADD_TO_SHOPPING_LIST = "add_to_shopping_list"
    }
}

@Serializable
private data class SaveRecipeArgs(
    val recipeId: Long = 0,
    val title: String,
    val servings: Int = 2,
    val prepMinutes: Int? = null,
    val cookMinutes: Int? = null,
    val notes: String? = null,
    val tags: List<String> = emptyList(),
    val ingredients: List<SaveIngredientArg> = emptyList(),
    val steps: List<SaveStepArg> = emptyList(),
)

@Serializable
private data class SaveIngredientArg(val name: String, val amount: String = "", val optional: Boolean = false)

@Serializable
private data class SaveStepArg(val text: String, val minutes: Int? = null)

@Serializable
private data class AddToPlanArgs(
    val title: String,
    val date: String,
    val slot: String,
    val servings: Double? = null,
)

@Serializable
private data class AddToShoppingArgs(val items: List<String>)
