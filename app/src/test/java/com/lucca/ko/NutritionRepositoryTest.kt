package com.lucca.ko

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lucca.ko.data.db.BodyMetric
import com.lucca.ko.data.db.FoodItem
import com.lucca.ko.data.db.FoodSource
import com.lucca.ko.data.db.KoDatabase
import com.lucca.ko.data.db.LogSlot
import com.lucca.ko.data.db.LogSource
import com.lucca.ko.data.db.Supplement
import com.lucca.ko.data.db.SupplementKind
import com.lucca.ko.data.prefs.ProfileRepository
import com.lucca.ko.data.remote.MealDbClient
import com.lucca.ko.data.remote.nas.NasClient
import com.lucca.ko.data.remote.nas.NasStatusMonitor
import com.lucca.ko.data.repo.BodyRepository
import com.lucca.ko.data.repo.NutritionRepository
import com.lucca.ko.data.repo.RecipeRepository
import com.lucca.ko.data.repo.SupplementRepository
import com.lucca.ko.domain.recipe.IngredientDraft
import com.lucca.ko.domain.recipe.RecipeDraft
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The food diary against real SQLite.
 *
 * The behaviour worth pinning here is the snapshot rule: a logged entry carries its own macros,
 * so correcting a food next month must not rewrite last month's history. Get that wrong and the
 * diary quietly rewrites itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NutritionRepositoryTest {

    private lateinit var db: KoDatabase
    private lateinit var nutrition: NutritionRepository
    private lateinit var body: BodyRepository
    private lateinit var supplements: SupplementRepository
    private lateinit var recipes: RecipeRepository

    private val today = LocalDate.of(2026, 9, 14)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, KoDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val http = OkHttpClient()
        val nas = NasClient(http, http, { "http://127.0.0.1:1/" }, { "" })
        val profileRepo = ProfileRepository(context)

        nutrition = NutritionRepository(
            foodDao = db.foodDao(),
            nutritionDao = db.nutritionDao(),
            bodyDao = db.bodyDao(),
            recipeDao = db.recipeDao(),
            profileRepo = profileRepo,
            nas = nas,
            nasStatus = NasStatusMonitor(nas, CoroutineScope(UnconfinedTestDispatcher())),
        )
        body = BodyRepository(db.bodyDao(), profileRepo)
        supplements = SupplementRepository(db.supplementDao(), db.nutritionDao())
        recipes = RecipeRepository(
            recipeDao = db.recipeDao(),
            tagDao = db.tagDao(),
            pantryDao = db.pantryDao(),
            shoppingDao = db.shoppingDao(),
            mealPlanDao = db.mealPlanDao(),
            mealDb = MealDbClient(http),
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun chicken(): FoodItem {
        val food = FoodItem(
            name = "Chicken breast",
            normalizedName = "chicken breast",
            source = FoodSource.LOCAL,
            servingGrams = 174.0,
            servingLabel = "1 breast",
            kcalPer100 = 165.0,
            proteinPer100 = 31.0,
            carbsPer100 = 0.0,
            fatPer100 = 3.6,
        )
        return food.copy(id = nutrition.saveFood(food))
    }

    // ---- Logging ----------------------------------------------------------------------

    @Test
    fun `logging a food computes its macros from the weight`() = runTest {
        nutrition.logFood(chicken(), grams = 200.0, date = today, slot = LogSlot.LUNCH)

        val entry = nutrition.observeDay(today).first().single()
        assertEquals(330.0, entry.kcal, 0.01)
        assertEquals(62.0, entry.proteinG, 0.01)
        assertEquals(200.0, entry.grams!!, 0.01)
        assertEquals(LogSlot.LUNCH, entry.slot)
    }

    @Test
    fun `an entry keeps its own macros when the food is corrected later`() = runTest {
        val food = chicken()
        nutrition.logFood(food, 200.0, today, LogSlot.LUNCH)

        // Someone finds a better number for chicken next month.
        nutrition.saveFood(food.copy(kcalPer100 = 200.0))

        // Last month's line must not have moved.
        assertEquals(330.0, nutrition.observeDay(today).first().single().kcal, 0.01)
    }

    @Test
    fun `an entry survives the food being deleted`() = runTest {
        val food = FoodItem(name = "Odd snack", normalizedName = "odd snack", kcalPer100 = 500.0)
        val id = nutrition.saveFood(food)
        nutrition.logFood(food.copy(id = id), 50.0, today, LogSlot.SNACK)

        nutrition.deleteFood(id)

        val entry = nutrition.observeDay(today).first().single()
        // ON DELETE SET NULL: the line stays readable, just without provenance.
        assertNull(entry.foodItemId)
        assertEquals("Odd snack", entry.label)
        assertEquals(250.0, entry.kcal, 0.01)
    }

    @Test
    fun `the day total is the sum of its entries`() = runTest {
        val food = chicken()
        nutrition.logFood(food, 200.0, today, LogSlot.LUNCH)
        nutrition.logFood(food, 100.0, today, LogSlot.DINNER)
        nutrition.logQuick("Beer", kcal = 140.0, date = today, slot = LogSlot.SNACK)

        val totals = nutrition.observeDayTotals(today).first()
        assertEquals(330.0 + 165.0 + 140.0, totals.kcal, 0.01)
        assertEquals(93.0, totals.proteinG, 0.01)
    }

    @Test
    fun `entries on other days do not leak into the total`() = runTest {
        val food = chicken()
        nutrition.logFood(food, 200.0, today, LogSlot.LUNCH)
        nutrition.logFood(food, 200.0, today.minusDays(1), LogSlot.LUNCH)

        assertEquals(330.0, nutrition.observeDayTotals(today).first().kcal, 0.01)
    }

    @Test
    fun `a quick entry needs no food at all`() = runTest {
        nutrition.logQuick("Pub lunch", kcal = 900.0, proteinG = 35.0, date = today, slot = LogSlot.LUNCH)

        val entry = nutrition.observeDay(today).first().single()
        assertEquals(LogSource.QUICK, entry.sourceType)
        assertNull(entry.foodItemId)
        assertEquals(900.0, entry.kcal, 0.01)
    }

    @Test
    fun `logging by the food's own serving records both grams and servings`() = runTest {
        nutrition.logFood(chicken(), grams = 348.0, date = today, slot = LogSlot.DINNER)
        val entry = nutrition.observeDay(today).first().single()
        assertEquals(2.0, entry.servings!!, 0.01)
    }

    // ---- Recipes ----------------------------------------------------------------------

    @Test
    fun `a recipe cannot be logged until its macros are known`() = runTest {
        val recipeId = recipes.saveDraft(
            RecipeDraft(title = "Stew", ingredients = listOf(IngredientDraft(-1, "beef", "500 g"))),
        )
        // Refusing is correct: inventing a calorie figure is worse than asking.
        assertNull(nutrition.logRecipe(recipeId, 1.0, today, LogSlot.DINNER))
    }

    @Test
    fun `a recipe with macros logs a scaled portion`() = runTest {
        val recipeId = recipes.saveDraft(RecipeDraft(title = "Stew", servingsText = "4"))
        db.recipeDao().updateRecipe(
            db.recipeDao().recipeById(recipeId)!!.copy(
                kcalPerServing = 500.0, proteinG = 30.0, carbsG = 40.0, fatG = 20.0,
            ),
        )

        nutrition.logRecipe(recipeId, servings = 1.5, date = today, slot = LogSlot.DINNER)

        val entry = nutrition.observeDay(today).first().single()
        assertEquals(750.0, entry.kcal, 0.01)
        assertEquals(45.0, entry.proteinG, 0.01)
        assertEquals(recipeId, entry.dishId)
    }

    // ---- Supplements -------------------------------------------------------------------

    @Test
    fun `creatine records adherence without touching the food diary`() = runTest {
        val creatine = Supplement(
            name = "Creatine", kind = SupplementKind.CREATINE, doseAmount = 5.0, doseUnit = "g",
        )
        val id = supplements.save(creatine)

        supplements.logDose(creatine.copy(id = id), today)

        // A zero-calorie row in a food diary says nothing; the streak is the point.
        assertTrue(nutrition.observeDay(today).first().isEmpty())
        assertTrue(supplements.statusFor(today).single().takenToday)
    }

    @Test
    fun `a protein shake lands in both the streak and the macros`() = runTest {
        val whey = Supplement(
            name = "Whey", kind = SupplementKind.PROTEIN, doseAmount = 1.0, doseUnit = "scoop",
            kcalPerDose = 112.0, proteinPerDose = 24.0,
        )
        val id = supplements.save(whey)

        supplements.logDose(whey.copy(id = id), today)

        val entry = nutrition.observeDay(today).first().single()
        assertEquals(112.0, entry.kcal, 0.01)
        assertEquals(24.0, entry.proteinG, 0.01)
        assertEquals(LogSlot.SUPPLEMENT, entry.slot)
        assertTrue(supplements.statusFor(today).single { it.supplement.id == id }.takenToday)
    }

    @Test
    fun `logging the same dose twice corrects rather than doubles`() = runTest {
        val whey = Supplement(name = "Whey", kcalPerDose = 112.0, proteinPerDose = 24.0)
        val id = supplements.save(whey)

        supplements.logDose(whey.copy(id = id), today)
        supplements.logDose(whey.copy(id = id), today)

        assertEquals(1, nutrition.observeDay(today).first().size)
    }

    @Test
    fun `un-ticking removes both the streak entry and the macros`() = runTest {
        val whey = Supplement(name = "Whey", kcalPerDose = 112.0, proteinPerDose = 24.0)
        val id = supplements.save(whey)
        supplements.logDose(whey.copy(id = id), today)

        supplements.unlogDose(whey.copy(id = id), today)

        assertTrue(nutrition.observeDay(today).first().isEmpty())
        assertFalse(supplements.statusFor(today).single().takenToday)
    }

    @Test
    fun `a streak counts consecutive days`() = runTest {
        val creatine = Supplement(name = "Creatine", kind = SupplementKind.CREATINE)
        val id = supplements.save(creatine)
        val stored = creatine.copy(id = id)
        (0..4).forEach { supplements.logDose(stored, today.minusDays(it.toLong())) }

        assertEquals(5, supplements.statusFor(today).single().streakDays)
    }

    @Test
    fun `the presets are added once and not again`() = runTest {
        assertTrue(supplements.seedDefaultsIfEmpty())
        assertFalse(supplements.seedDefaultsIfEmpty())

        val names = supplements.observeAll().first().map { it.name }
        assertTrue("Creatine" in names)
        assertTrue(names.any { it.contains("Whey") })
    }

    // ---- Body -------------------------------------------------------------------------

    @Test
    fun `a second weigh-in on the same day replaces the first`() = runTest {
        body.save(BodyMetric(date = today.toString(), weightKg = 80.0))
        body.save(BodyMetric(date = today.toString(), weightKg = 80.4))

        val all = body.observeAll().first()
        assertEquals(1, all.size)
        assertEquals(80.4, all.single().weightKg!!, 0.01)
    }

    @Test
    fun `the weight trend needs about ten readings before it claims anything`() = runTest {
        (0..4).forEach {
            body.save(BodyMetric(date = today.minusDays(it.toLong()).toString(), weightKg = 80.0 - it * 0.1))
        }
        val trend = body.observeWeightTrend().first()
        assertFalse(trend.hasEnoughData)
        assertNull(trend.changePerWeekKg)
    }

    @Test
    fun `a sustained loss shows as a negative weekly change`() = runTest {
        (0..29).forEach {
            body.save(
                BodyMetric(
                    date = today.minusDays(it.toLong()).toString(),
                    weightKg = 80.0 + it * 0.05,
                ),
            )
        }
        val trend = body.observeWeightTrend().first()
        assertTrue(trend.hasEnoughData)
        assertNotNull(trend.currentTrendKg)
        assertTrue("weight going down should read negative", trend.changePerWeekKg!! < 0)
    }

    @Test
    fun `a weigh-in updates the profile the calorie maths reads`() = runTest {
        body.save(BodyMetric(date = today.toString(), weightKg = 78.5, bodyFatPct = 18.0))
        val profile = nutrition.profile()
        assertEquals(78.5, profile.weightKg, 0.01)
        assertEquals(18.0, profile.bodyFatPct!!, 0.01)
    }

    // ---- Barcodes ----------------------------------------------------------------------

    @Test
    fun `a locally known barcode needs no network`() = runTest {
        val food = FoodItem(
            name = "Test yoghurt",
            normalizedName = "test yoghurt",
            barcode = "1234567890123",
            kcalPer100 = 61.0,
        )
        nutrition.saveFood(food)

        // The NAS client points at a dead port, so anything returned came from the table.
        val found = nutrition.lookupBarcode("1234567890123")
        assertNotNull(found)
        assertEquals("Test yoghurt", found!!.name)
    }

    @Test
    fun `an unknown barcode with no network is simply not found`() = runTest {
        assertNull(nutrition.lookupBarcode("9999999999999"))
    }
}
