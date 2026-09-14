package com.lucca.ko.data.repo

import com.lucca.ko.data.db.dao.PantryDao
import com.lucca.ko.data.db.dao.RecipeDao
import com.lucca.ko.data.prefs.SettingsRepository
import com.lucca.ko.data.remote.MealDbClient
import com.lucca.ko.data.remote.MealSummary
import com.lucca.ko.data.remote.nas.IdeaDto
import com.lucca.ko.data.remote.nas.NasClient
import com.lucca.ko.data.remote.nas.NasStatusMonitor
import com.lucca.ko.data.remote.nas.PantryEntryDto
import com.lucca.ko.data.remote.nas.SuggestRequestDto
import com.lucca.ko.data.remote.nas.TranslateRequestDto

data class SuggestionResult(
    val usedBrain: Boolean,
    val ideas: List<IdeaDto>,
    val meals: List<MealSummary>,
    val note: String? = null,
)

/**
 * Dish suggestions and pantry translation, both through the NAS brain.
 *
 * When the brain is unreachable, suggestions fall back to matching the pantry against TheMealDB
 * directly — worse ideas, but the screen still does something useful. Translation has no such
 * fallback and is simply reported as unavailable: a fake translation would poison
 * `PantryItem.searchName`, which every future recipe match depends on.
 */
class SuggestionRepository(
    private val pantryDao: PantryDao,
    private val recipeDao: RecipeDao,
    private val mealDb: MealDbClient,
    private val nas: NasClient,
    private val nasStatus: NasStatusMonitor,
    private val settings: SettingsRepository,
) {
    /** Checks the brain at the given URL, for the Settings "Test connection" button. */
    suspend fun testConnection(baseUrl: String): Result<String> = runCatching {
        val health = nas.health(baseUrlOverride = baseUrl)
        buildString {
            append("Brain ${health.version} is up")
            if (!health.ollama.reachable) {
                append(", but it can't reach Ollama")
            } else {
                val models = health.ollama.models
                append(" with ${models.size} model${if (models.size == 1) "" else "s"}")
                if (health.ollama.missing.isNotEmpty()) {
                    append(". Not installed: ${health.ollama.missing.joinToString(", ")}")
                }
            }
        }
    }

    /**
     * Asks the brain to translate every pantry name into English and stores the result as
     * `PantryItem.searchName`. Returns how many changed.
     */
    suspend fun translatePantryToEnglish(): Int {
        val items = pantryDao.getAll()
        if (items.isEmpty()) return 0

        val response = try {
            nas.translate(TranslateRequestDto(names = items.map { it.name }))
        } catch (e: Exception) {
            nasStatus.reportUnreachable(e.message ?: "Couldn't reach the brain.")
            throw e
        }
        nasStatus.reportReachable()

        var updated = 0
        items.forEach { item ->
            val english = response.translations[item.name]?.takeIf { it.isNotBlank() } ?: return@forEach
            if (!english.equals(item.searchName, ignoreCase = true)) {
                pantryDao.update(item.copy(searchName = english))
                updated++
            }
        }
        return updated
    }

    suspend fun suggestDishes(constraints: String = ""): SuggestionResult {
        val cfg = settings.currentSettings()
        val pantryItems = pantryDao.getAll()

        var ideas: List<IdeaDto> = emptyList()
        var note: String?
        var usedBrain = false

        try {
            val response = nas.suggest(
                SuggestRequestDto(
                    pantry = pantryItems.map {
                        PantryEntryDto(
                            name = it.name,
                            searchName = it.searchName,
                            status = it.status.name,
                        )
                    },
                    count = cfg.suggestionCount,
                    // Don't suggest things already in the library; the point is new ideas.
                    exclude = recipeDao.getAllRecipes().map { it.title }.filter { it.isNotBlank() },
                    constraints = constraints,
                ),
            )
            nasStatus.reportReachable()
            ideas = response.ideas
            usedBrain = ideas.isNotEmpty()
            note = response.note
        } catch (e: Exception) {
            nasStatus.reportUnreachable(e.message ?: "Couldn't reach the brain.")
            note = "Couldn't reach the brain (${e.message}). Showing pantry matches instead."
        }

        return SuggestionResult(
            usedBrain = usedBrain,
            ideas = ideas,
            meals = findMeals(ideas, pantryItems.map { it.searchName?.ifBlank { null } ?: it.name }),
            note = note,
        )
    }

    /**
     * Turns ideas into real TheMealDB hits, falling back to pantry-driven search when there are
     * no ideas — which is what keeps the screen useful with the brain switched off.
     */
    private suspend fun findMeals(
        ideas: List<IdeaDto>,
        pantryNames: List<String>,
    ): List<MealSummary> {
        val meals = LinkedHashMap<String, MealSummary>()

        for (idea in ideas) {
            val terms = listOf(idea.query, idea.title)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            for (term in terms) {
                val hits = runCatching { mealDb.searchByName(term) }.getOrDefault(emptyList())
                    .ifEmpty { runCatching { mealDb.filterByIngredient(term) }.getOrDefault(emptyList()) }
                if (hits.isNotEmpty()) {
                    hits.take(3).forEach { if (it.id.isNotBlank()) meals.putIfAbsent(it.id, it) }
                    break
                }
            }
        }

        if (meals.size < 6) {
            for (name in pantryNames.shuffled().take(4)) {
                runCatching { mealDb.filterByIngredient(name) }.getOrDefault(emptyList())
                    .take(4)
                    .forEach { if (it.id.isNotBlank()) meals.putIfAbsent(it.id, it) }
                if (meals.size >= 12) break
            }
        }

        if (meals.isEmpty()) {
            repeat(6) {
                runCatching { mealDb.random() }.getOrNull()?.let { d ->
                    meals.putIfAbsent(d.id, MealSummary(d.id, d.title, d.thumbUrl))
                }
            }
        }

        return meals.values.toList()
    }
}
