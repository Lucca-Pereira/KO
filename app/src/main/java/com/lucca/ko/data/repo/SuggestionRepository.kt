package com.lucca.ko.data.repo

import com.lucca.ko.data.db.dao.PantryDao
import com.lucca.ko.data.prefs.SettingsRepository
import com.lucca.ko.data.remote.MealDbClient
import com.lucca.ko.data.remote.MealSummary
import com.lucca.ko.data.remote.OllamaClient
import com.lucca.ko.data.remote.RecipeIdea
import kotlinx.coroutines.flow.first

data class SuggestionResult(
    val usedOllama: Boolean,
    val ideas: List<RecipeIdea>,
    val meals: List<MealSummary>,
    val note: String? = null,
)

/**
 * Dish suggestions, and the pantry translation that makes them work for a non-English pantry.
 *
 * Both still talk to Ollama directly; Phase 3 repoints them at the NAS brain service and deletes
 * [OllamaClient]. The TheMealDB fan-out below stays as the offline fallback.
 */
class SuggestionRepository(
    private val pantryDao: PantryDao,
    private val mealDb: MealDbClient,
    private val ollama: OllamaClient,
    private val settings: SettingsRepository,
) {
    suspend fun testOllama(baseUrl: String): Result<List<String>> = runCatching {
        ollama.listModels(baseUrl)
    }

    /**
     * Asks the bot to translate every pantry item name into English and stores it as
     * `PantryItem.searchName`. Returns how many items changed; 0 if the bot is unreachable.
     */
    suspend fun translatePantryToEnglish(): Int {
        val cfg = settings.settings.first()
        if (!cfg.ollamaConfigured) return 0
        val items = pantryDao.getAll()
        if (items.isEmpty()) return 0
        val translations = ollama.translateFoods(
            baseUrl = cfg.ollamaBaseUrl,
            model = cfg.ollamaModel,
            names = items.map { it.name },
        )
        if (translations.isEmpty()) return 0
        var updated = 0
        items.forEach { item ->
            val english = translations[item.name]?.takeIf { it.isNotBlank() } ?: return@forEach
            if (!english.equals(item.searchName, ignoreCase = true)) {
                pantryDao.update(item.copy(searchName = english))
                updated++
            }
        }
        return updated
    }

    suspend fun suggestDishes(): SuggestionResult {
        val cfg = settings.settings.first()
        val pantryItems = pantryDao.getAll()
        // Prefer the English alias so bot ideas + TheMealDB search work for a non-English pantry.
        val pantryNames = pantryItems.map { it.searchName?.takeIf(String::isNotBlank) ?: it.name }

        var usedOllama = false
        var note: String? = null
        var ideas: List<RecipeIdea> = emptyList()

        if (cfg.ollamaConfigured) {
            try {
                ideas = ollama.suggestRecipes(
                    baseUrl = cfg.ollamaBaseUrl,
                    model = cfg.ollamaModel,
                    pantry = pantryNames,
                    count = cfg.suggestionCount,
                )
                usedOllama = ideas.isNotEmpty()
                if (ideas.isEmpty()) {
                    note = "The model didn't return any ideas — showing pantry matches instead."
                }
            } catch (e: Exception) {
                note = "Couldn't reach Ollama (${e.message}). Showing pantry matches instead."
            }
        } else {
            note = "Set your Ollama server in Settings for AI suggestions. Showing pantry matches."
        }

        val meals = LinkedHashMap<String, MealSummary>()

        for (idea in ideas.take(cfg.suggestionCount)) {
            // Small models often return a whole sentence as the "query"; fall back to the dish
            // name so the recipe search still lands on something.
            val terms = listOf(idea.query, idea.dish)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            var hits: List<MealSummary> = emptyList()
            for (term in terms) {
                hits = runCatching { mealDb.searchByName(term) }.getOrDefault(emptyList())
                    .ifEmpty { runCatching { mealDb.filterByIngredient(term) }.getOrDefault(emptyList()) }
                if (hits.isNotEmpty()) break
            }
            hits.take(3).forEach { if (it.id.isNotBlank()) meals.putIfAbsent(it.id, it) }
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

        return SuggestionResult(
            usedOllama = usedOllama,
            ideas = ideas,
            meals = meals.values.toList(),
            note = note,
        )
    }
}
