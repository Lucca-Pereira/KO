package com.lucca.ko

import android.content.Context
import com.lucca.ko.data.KitchenRepository
import com.lucca.ko.data.db.KoDatabase
import com.lucca.ko.data.prefs.SettingsRepository
import com.lucca.ko.data.remote.MealDbClient
import com.lucca.ko.data.remote.OllamaClient
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

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

    val repository: KitchenRepository by lazy {
        KitchenRepository(
            pantryDao = database.pantryDao(),
            dishDao = database.dishDao(),
            mealPlanDao = database.mealPlanDao(),
            shoppingDao = database.shoppingDao(),
            mealDb = MealDbClient(httpClient),
            ollama = OllamaClient(httpClient),
            settings = settingsRepository,
        )
    }
}
