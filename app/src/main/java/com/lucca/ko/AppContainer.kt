package com.lucca.ko

import android.content.Context
import com.lucca.ko.data.BackupRepository
import com.lucca.ko.data.db.KoDatabase
import com.lucca.ko.data.prefs.SettingsRepository
import com.lucca.ko.data.remote.MealDbClient
import com.lucca.ko.data.remote.OllamaClient
import com.lucca.ko.data.repair.StartupRepairs
import com.lucca.ko.data.repo.MealPlanRepository
import com.lucca.ko.data.repo.PantryRepository
import com.lucca.ko.data.repo.RecipeRepository
import com.lucca.ko.data.repo.ShoppingRepository
import com.lucca.ko.data.repo.SuggestionRepository
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** Manual dependency container held by [KoApp] — no DI framework needed. */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val database: KoDatabase by lazy { KoDatabase.get(appContext) }

    private val mealDb: MealDbClient by lazy { MealDbClient(httpClient) }
    private val ollama: OllamaClient by lazy { OllamaClient(httpClient) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }

    val pantryRepository: PantryRepository by lazy {
        PantryRepository(database.pantryDao(), database.shoppingDao())
    }

    val shoppingRepository: ShoppingRepository by lazy {
        ShoppingRepository(database.shoppingDao(), database.pantryDao())
    }

    val recipeRepository: RecipeRepository by lazy {
        RecipeRepository(
            recipeDao = database.recipeDao(),
            tagDao = database.tagDao(),
            pantryDao = database.pantryDao(),
            shoppingDao = database.shoppingDao(),
            mealPlanDao = database.mealPlanDao(),
            mealDb = mealDb,
        )
    }

    val mealPlanRepository: MealPlanRepository by lazy {
        MealPlanRepository(database.mealPlanDao(), database.recipeDao())
    }

    val suggestionRepository: SuggestionRepository by lazy {
        SuggestionRepository(
            pantryDao = database.pantryDao(),
            mealDb = mealDb,
            ollama = ollama,
            settings = settingsRepository,
        )
    }

    val backupRepository: BackupRepository by lazy {
        BackupRepository(database, settingsRepository)
    }

    val startupRepairs: StartupRepairs by lazy {
        StartupRepairs(
            pantryDao = database.pantryDao(),
            shoppingDao = database.shoppingDao(),
            recipeDao = database.recipeDao(),
            tagDao = database.tagDao(),
            settings = settingsRepository,
        )
    }
}
