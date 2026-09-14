package com.lucca.ko

import android.content.Context
import com.lucca.ko.data.BackupRepository
import com.lucca.ko.data.db.KoDatabase
import com.lucca.ko.data.prefs.SecretsRepository
import com.lucca.ko.data.prefs.SettingsRepository
import com.lucca.ko.data.remote.MealDbClient
import com.lucca.ko.data.remote.nas.NasClient
import com.lucca.ko.data.remote.nas.NasStatusMonitor
import com.lucca.ko.data.repair.StartupRepairs
import com.lucca.ko.data.repo.MealPlanRepository
import com.lucca.ko.data.repo.PantryRepository
import com.lucca.ko.data.repo.RecipeRepository
import com.lucca.ko.data.repo.ShoppingRepository
import com.lucca.ko.data.repo.SuggestionRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient

/** Manual dependency container held by [KoApp] — no DI framework needed. */
class AppContainer(context: Context, private val appScope: CoroutineScope) {

    private val appContext = context.applicationContext

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * A second client for server-sent events.
     *
     * The shared client's 90-second `callTimeout` severs a connection after 90 seconds no matter
     * how healthy it is, and its 60-second read timeout kills it during any pause the model
     * takes while thinking — both fatal for a stream that legitimately runs for minutes.
     * `newBuilder()` shares the connection pool and dispatcher, so this costs almost nothing.
     */
    private val streamingHttpClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    private val database: KoDatabase by lazy { KoDatabase.get(appContext) }

    private val mealDb: MealDbClient by lazy { MealDbClient(httpClient) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }

    val secretsRepository: SecretsRepository by lazy { SecretsRepository(appContext) }

    val nasClient: NasClient by lazy {
        NasClient(
            http = httpClient,
            streamingHttp = streamingHttpClient,
            baseUrlProvider = { settingsRepository.currentSettings().nasBaseUrl },
            tokenProvider = { secretsRepository.currentToken() },
        )
    }

    val nasStatus: NasStatusMonitor by lazy { NasStatusMonitor(nasClient, appScope) }

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
            recipeDao = database.recipeDao(),
            mealDb = mealDb,
            nas = nasClient,
            nasStatus = nasStatus,
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
