package com.lucca.ko

import android.content.Context
import com.lucca.ko.data.BackupRepository
import com.lucca.ko.data.db.KoDatabase
import com.lucca.ko.data.prefs.ProfileRepository
import com.lucca.ko.data.prefs.SecretsRepository
import com.lucca.ko.data.prefs.SettingsRepository
import com.lucca.ko.data.remote.OpenFoodFactsClient
import com.lucca.ko.data.remote.claude.ClaudeClient
import com.lucca.ko.data.remote.claude.KitchenTools
import com.lucca.ko.data.repair.StartupRepairs
import com.lucca.ko.data.repo.AgentRepository
import com.lucca.ko.data.repo.BodyRepository
import com.lucca.ko.data.repo.MealPlanRepository
import com.lucca.ko.data.repo.NutritionRepository
import com.lucca.ko.data.repo.PantryRepository
import com.lucca.ko.data.repo.RecipeRepository
import com.lucca.ko.data.repo.RevisionRepository
import com.lucca.ko.data.repo.ShoppingRepository
import com.lucca.ko.data.repo.SupplementRepository
import com.lucca.ko.data.seed.FoodSeedLoader
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

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }

    val secretsRepository: SecretsRepository by lazy { SecretsRepository(appContext) }

    val claudeClient: ClaudeClient by lazy {
        ClaudeClient(http = httpClient, apiKeyProvider = { secretsRepository.currentApiKey() })
    }

    private val openFoodFactsClient: OpenFoodFactsClient by lazy { OpenFoodFactsClient(httpClient) }

    val pantryRepository: PantryRepository by lazy {
        PantryRepository(database.pantryDao(), database.shoppingDao())
    }

    val shoppingRepository: ShoppingRepository by lazy {
        ShoppingRepository(database.shoppingDao(), database.pantryDao())
    }

    val revisionRepository: RevisionRepository by lazy { RevisionRepository(database.chatDao()) }

    val recipeRepository: RecipeRepository by lazy {
        RecipeRepository(
            recipeDao = database.recipeDao(),
            tagDao = database.tagDao(),
            pantryDao = database.pantryDao(),
            shoppingDao = database.shoppingDao(),
            mealPlanDao = database.mealPlanDao(),
        )
    }

    val mealPlanRepository: MealPlanRepository by lazy {
        MealPlanRepository(database.mealPlanDao(), database.recipeDao())
    }

    private val kitchenTools: KitchenTools by lazy {
        KitchenTools(
            pantryRepository = pantryRepository,
            recipeRepository = recipeRepository,
            mealPlanRepository = mealPlanRepository,
            shoppingRepository = shoppingRepository,
            revisionRepository = revisionRepository,
        )
    }

    val agentRepository: AgentRepository by lazy {
        AgentRepository(chatDao = database.chatDao(), claude = claudeClient, tools = kitchenTools)
    }

    val profileRepository: ProfileRepository by lazy { ProfileRepository(appContext) }

    val nutritionRepository: NutritionRepository by lazy {
        NutritionRepository(
            foodDao = database.foodDao(),
            nutritionDao = database.nutritionDao(),
            bodyDao = database.bodyDao(),
            recipeDao = database.recipeDao(),
            profileRepo = profileRepository,
            offClient = openFoodFactsClient,
            claude = claudeClient,
        )
    }

    val bodyRepository: BodyRepository by lazy {
        BodyRepository(database.bodyDao(), profileRepository)
    }

    val supplementRepository: SupplementRepository by lazy {
        SupplementRepository(database.supplementDao(), database.nutritionDao())
    }

    val foodSeedLoader: FoodSeedLoader by lazy {
        FoodSeedLoader(appContext, database.foodDao())
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
