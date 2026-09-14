"""The wire contract.

These are versioned independently of the app's Room entities on purpose: reusing entities as
wire types is the mistake KO's backup format already made once, where adding a column silently
changed the on-disk shape.

The models marked "LLM output" double as JSON Schemas handed to Ollama's structured-output mode,
so their field names and descriptions are prompt surface, not just types.
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

# ---- Health -------------------------------------------------------------------------


class OllamaHealth(BaseModel):
    reachable: bool
    models: list[str] = Field(default_factory=list)
    configured: dict[str, str] = Field(default_factory=dict)
    missing: list[str] = Field(default_factory=list)
    """Configured models that are not installed on the server."""


class Health(BaseModel):
    ok: bool
    version: str
    ollama: OllamaHealth
    busy: bool = False
    queue_depth: int = 0
    cache_entries: int = 0


# ---- Pantry / suggestions -------------------------------------------------------------


class PantryEntry(BaseModel):
    name: str
    search_name: str | None = None
    """The English alias, when the pantry is not in English."""
    status: Literal["IN_STOCK", "LOW", "OUT"] = "IN_STOCK"

    def best_name(self) -> str:
        return (self.search_name or "").strip() or self.name


class SuggestRequest(BaseModel):
    pantry: list[PantryEntry] = Field(default_factory=list)
    count: int = Field(default=5, ge=1, le=12)
    exclude: list[str] = Field(default_factory=list)
    """Titles already in the library, so suggestions are not things you already have."""
    constraints: str = ""
    """Free text from the user: "vegetarian", "nothing that needs an oven"."""


class Idea(BaseModel):
    """LLM output."""

    title: str = Field(description="The dish name, e.g. 'Spanish tortilla'")
    why: str = Field(description="One short sentence naming the pantry items this dish uses")
    query: str = Field(default="", description="A 2-3 word search term for a recipe database")
    tags: list[str] = Field(
        default_factory=list, description="Short tags like 'quick', 'vegetarian'"
    )
    est_minutes: int | None = Field(default=None, description="Rough total time in minutes")


class IdeaList(BaseModel):
    """LLM output wrapper. A bare array is harder for small models to keep valid."""

    suggestions: list[Idea] = Field(default_factory=list)


class SuggestResponse(BaseModel):
    ideas: list[Idea] = Field(default_factory=list)
    model: str = ""
    note: str | None = None


# ---- Translation ----------------------------------------------------------------------


class TranslateRequest(BaseModel):
    names: list[str]
    target: str = "en"


class TranslationMap(BaseModel):
    """LLM output."""

    translations: dict[str, str] = Field(default_factory=dict)


class TranslateResponse(BaseModel):
    translations: dict[str, str] = Field(default_factory=dict)
    model: str = ""


# ---- Recipes ---------------------------------------------------------------------------


class RecipeIngredientDto(BaseModel):
    name: str = Field(description="The ingredient alone, no amount: 'plain flour'")
    amount: str = Field(
        default="", description="Amount as written: '200 g', '1 1/2 cups', 'a pinch'"
    )
    optional: bool = False
    section: str | None = Field(default=None, description="Group heading like 'For the sauce'")


class RecipeStepDto(BaseModel):
    text: str = Field(description="One instruction, a sentence or two")
    minutes: int | None = Field(default=None, description="How long this step takes, if it waits")


class RecipeDto(BaseModel):
    """LLM output, and the shape the phone maps into its own entities."""

    title: str
    servings: int = Field(default=2, ge=1, le=99)
    prep_minutes: int | None = None
    cook_minutes: int | None = None
    ingredients: list[RecipeIngredientDto] = Field(default_factory=list)
    steps: list[RecipeStepDto] = Field(default_factory=list)
    tags: list[str] = Field(default_factory=list)
    notes: str | None = None
    source_url: str | None = None
    image_url: str | None = None
    mealdb_id: str | None = None


class GeneratedRecipe(BaseModel):
    """LLM output for a freshly written recipe.

    Separate from [RecipeDto] because the two have different truths about missing fields: a
    recipe someone is writing from scratch always has a prep and cook time, so making them
    required forces the model to commit to a number instead of quietly answering null. An
    imported recipe genuinely may not state them, which is why RecipeDto keeps them optional.
    """

    title: str = Field(description="A short, specific dish name")
    servings: int = Field(ge=1, le=99, description="How many people this serves")
    prep_minutes: int = Field(ge=0, description="Hands-on preparation time in minutes")
    cook_minutes: int = Field(ge=0, description="Time spent cooking, in minutes")
    ingredients: list[RecipeIngredientDto] = Field(description="Everything needed, with amounts")
    steps: list[RecipeStepDto] = Field(description="The method, in order")
    tags: list[str] = Field(description="Two or three short tags like 'quick', 'one pan'")
    notes: str | None = Field(default=None, description="Anything worth knowing, or null")

    def to_dto(self) -> RecipeDto:
        # Times pass through untouched, including zero. Folding 0 into None looks tidy and
        # throws away the difference between "no prep needed" and "the model didn't say".
        return RecipeDto(
            title=self.title,
            servings=self.servings,
            prep_minutes=self.prep_minutes,
            cook_minutes=self.cook_minutes,
            ingredients=self.ingredients,
            steps=self.steps,
            tags=self.tags,
            notes=self.notes,
        )


class GenerateRecipeRequest(BaseModel):
    prompt: str = ""
    """What the user asked for: "something with the chicken and the peppers"."""
    pantry: list[PantryEntry] = Field(default_factory=list)
    servings: int = Field(default=2, ge=1, le=99)
    constraints: str = ""


class ImportRecipeRequest(BaseModel):
    mealdb_id: str | None = None
    url: str | None = None


# ---- Meal search -------------------------------------------------------------------------


class MealSummaryDto(BaseModel):
    id: str
    title: str
    thumb_url: str | None = None


class MealSearchResponse(BaseModel):
    results: list[MealSummaryDto] = Field(default_factory=list)


# ---- Nutrition ------------------------------------------------------------------------


class Macros(BaseModel):
    kcal: float = 0.0
    protein_g: float = 0.0
    carbs_g: float = 0.0
    fat_g: float = 0.0
    fiber_g: float | None = None


class NutritionIngredient(BaseModel):
    name: str
    normalized_name: str = ""
    """The app's own normalisation, so both sides key on the same string."""
    quantity: float | None = None
    unit: str | None = None
    raw: str = ""


class EstimateRequest(BaseModel):
    title: str = ""
    servings: int = Field(default=1, ge=1, le=99)
    ingredients: list[NutritionIngredient] = Field(default_factory=list)


class IngredientEstimate(BaseModel):
    name: str
    grams: float | None = None
    macros: Macros = Field(default_factory=Macros)
    method: Literal["TABLE", "AI", "UNKNOWN"] = "UNKNOWN"
    confidence: float = 0.0


class EstimateResponse(BaseModel):
    per_serving: Macros
    total: Macros
    per_ingredient: list[IngredientEstimate] = Field(default_factory=list)
    coverage: float = 0.0
    """Fraction of ingredients the estimate actually accounts for, 0..1."""
    note: str = ""


class AiPortionGuess(BaseModel):
    """LLM output: grams and macros for the ingredients the table could not match."""

    name: str = Field(description="The ingredient name, copied exactly from the request")
    grams: float = Field(description="Best estimate of the weight in grams")
    kcal: float
    protein_g: float
    carbs_g: float
    fat_g: float


class AiPortionGuesses(BaseModel):
    """LLM output wrapper."""

    items: list[AiPortionGuess] = Field(default_factory=list)


# ---- Foods -----------------------------------------------------------------------------


class FoodDto(BaseModel):
    barcode: str | None = None
    name: str
    brand: str | None = None
    source: Literal["OFF", "LOCAL", "AI", "MANUAL"] = "LOCAL"
    serving_label: str | None = None
    serving_grams: float | None = None
    kcal_per_100: float = 0.0
    protein_per_100: float = 0.0
    carbs_per_100: float = 0.0
    fat_per_100: float = 0.0
    fiber_per_100: float | None = None
    sugar_per_100: float | None = None
    sat_fat_per_100: float | None = None
    sodium_mg_per_100: float | None = None
    is_supplement: bool = False
    """Protein powder, creatine and friends: logged for adherence as well as for macros."""
    image_url: str | None = None

    # NOTE: no `complete` flag — a food with no macros is returned as not-found instead, so the
    # phone opens manual entry rather than silently logging a zero-calorie yoghurt.


class FoodSearchResponse(BaseModel):
    results: list[FoodDto] = Field(default_factory=list)


class FoodSeedResponse(BaseModel):
    updated_at: int
    foods: list[FoodDto] = Field(default_factory=list)


# ---- Chat --------------------------------------------------------------------------------


class ChatMessageDto(BaseModel):
    role: Literal["user", "assistant"]
    content: str


class RecipeSnapshot(BaseModel):
    """The recipe as the phone currently has it. Resent every turn: the phone owns the data
    and it may have changed between messages."""

    title: str
    servings: int = 2
    ingredients: list[RecipeIngredientDto] = Field(default_factory=list)
    steps: list[RecipeStepDto] = Field(default_factory=list)
    notes: str | None = None


class ChatRequest(BaseModel):
    recipe: RecipeSnapshot
    history: list[ChatMessageDto] = Field(default_factory=list)
    message: str
    pantry: list[PantryEntry] = Field(default_factory=list)


class RecipePatch(BaseModel):
    """LLM output: a concrete edit the user can accept or reject.

    Deliberately a whole replacement recipe rather than a list of surgical operations. A 7B model
    reliably rewrites a short recipe; it does not reliably emit ``{"op": "replaceIngredient",
    "id": 42}`` against ids it cannot see.
    """

    summary: str = Field(
        description="One line describing the change, e.g. 'Swapped cream for coconut cream'"
    )
    recipe: RecipeDto
