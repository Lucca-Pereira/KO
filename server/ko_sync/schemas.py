"""Wire shapes for the sync endpoint and the MCP tools.

These mirror the phone's own DTOs (`AgentImportRepository.kt`'s `RecipeImportEntry` and friends)
field-for-field wherever the two overlap, plus the `remoteId`/`updatedAt` pair every syncable row
carries once sync exists. Keeping the shape identical to the file-import format is deliberate —
`store.py` is the same "one shape, several entry points" the phone's import path already uses.
"""

from __future__ import annotations

from pydantic import BaseModel, Field


class Health(BaseModel):
    ok: bool = True
    version: str


class RecipeIngredientWire(BaseModel):
    name: str
    amount: str = ""
    optional: bool = False


class RecipeStepWire(BaseModel):
    text: str
    minutes: int | None = None


class RecipeWire(BaseModel):
    remoteId: str
    title: str
    servings: int = 2
    prepMinutes: int | None = None
    cookMinutes: int | None = None
    notes: str | None = None
    tags: list[str] = Field(default_factory=list)
    kcalPerServing: float | None = None
    proteinG: float | None = None
    carbsG: float | None = None
    fatG: float | None = None
    macroNote: str | None = None
    ingredients: list[RecipeIngredientWire] = Field(default_factory=list)
    steps: list[RecipeStepWire] = Field(default_factory=list)
    updatedAt: int
    """Epoch millis. Last-write-wins against the stored row's own `updatedAt` on conflict."""


class PantryItemWire(BaseModel):
    remoteId: str
    name: str
    status: str = "IN_STOCK"
    category: str | None = None
    quantity: str | None = None
    note: str | None = None
    updatedAt: int


class ShoppingItemWire(BaseModel):
    remoteId: str
    name: str


class MealPlanEntryWire(BaseModel):
    remoteId: str
    recipeTitle: str
    date: str
    slot: str
    servings: float | None = None


class NutritionEntryWire(BaseModel):
    """One food-diary line. Carries its own macros, like the phone's row."""

    remoteId: str
    date: str
    slot: str = "SNACK"
    sourceType: str = "QUICK"
    supplementName: str | None = None
    label: str
    grams: float | None = None
    servings: float | None = None
    kcal: float = 0.0
    proteinG: float = 0.0
    carbsG: float = 0.0
    fatG: float = 0.0
    fiberG: float | None = None
    note: str | None = None
    deleted: bool = False
    updatedAt: int


class SupplementLogWire(BaseModel):
    remoteId: str
    date: str
    supplementName: str
    doses: float = 1.0
    deleted: bool = False
    updatedAt: int


class BodyMetricWire(BaseModel):
    date: str
    """The key: one row per date."""
    weightKg: float | None = None
    bodyFatPct: float | None = None
    waistCm: float | None = None
    chestCm: float | None = None
    hipCm: float | None = None
    armCm: float | None = None
    thighCm: float | None = None
    neckCm: float | None = None
    note: str | None = None
    updatedAt: int


class SupplementWire(BaseModel):
    name: str
    kind: str = "OTHER"
    doseAmount: float = 1.0
    doseUnit: str = "g"
    kcalPerDose: float = 0.0
    proteinPerDose: float = 0.0
    carbsPerDose: float = 0.0
    fatPerDose: float = 0.0
    dosesPerDay: int = 1
    active: bool = True


class TargetWire(BaseModel):
    effectiveFrom: str
    kcal: float
    proteinG: float
    carbsG: float
    fatG: float
    source: str = "FORMULA"


class PlanViewWire(BaseModel):
    """How the meal-plan screen is laid out. `weeks` is 1 or 2; `calendar` False means just the
    current week(s), with no browsing to other weeks."""

    weeks: int = 1
    calendar: bool = True
    updatedAt: int


class DeletedWire(BaseModel):
    """remoteIds Claude deleted, per kitchen collection, for the phone to drop."""

    recipes: list[str] = Field(default_factory=list)
    pantryItems: list[str] = Field(default_factory=list)
    shoppingItems: list[str] = Field(default_factory=list)
    mealPlanEntries: list[str] = Field(default_factory=list)


class SyncPush(BaseModel):
    recipes: list[RecipeWire] = Field(default_factory=list)
    pantryItems: list[PantryItemWire] = Field(default_factory=list)
    shoppingItems: list[ShoppingItemWire] = Field(default_factory=list)
    mealPlanEntries: list[MealPlanEntryWire] = Field(default_factory=list)
    nutritionEntries: list[NutritionEntryWire] = Field(default_factory=list)
    supplementLogs: list[SupplementLogWire] = Field(default_factory=list)
    bodyMetrics: list[BodyMetricWire] = Field(default_factory=list)
    planView: PlanViewWire | None = None


class SyncPresent(BaseModel):
    """Every remoteId the phone currently has, per collection — how deletions reach the server.
    Shopping items ticked off on the phone are left out, so they drop off Claude's list too.
    Body metrics are listed by date, their key."""

    recipes: list[str] = Field(default_factory=list)
    pantryItems: list[str] = Field(default_factory=list)
    shoppingItems: list[str] = Field(default_factory=list)
    mealPlanEntries: list[str] = Field(default_factory=list)
    nutritionEntries: list[str] | None = None
    supplementLogs: list[str] | None = None
    bodyMetrics: list[str] | None = None
    """None (from app versions before 0.11) means "not reported" — nothing pruned there."""


class SyncRequest(BaseModel):
    lastSyncedAt: int = 0
    """Epoch millis; 0 means the phone has never synced before."""
    push: SyncPush = Field(default_factory=SyncPush)
    present: SyncPresent | None = None
    """Omitted by app versions before 0.10 — then nothing is pruned."""
    supplements: list[SupplementWire] | None = None
    """The phone's whole supplement list, replacing the server's copy. None leaves it alone."""
    targets: list[TargetWire] | None = None
    """The phone's whole target history, replacing the server's copy. None leaves it alone."""


class SyncResponse(BaseModel):
    serverTime: int
    recipes: list[RecipeWire] = Field(default_factory=list)
    pantryItems: list[PantryItemWire] = Field(default_factory=list)
    shoppingItems: list[ShoppingItemWire] = Field(default_factory=list)
    mealPlanEntries: list[MealPlanEntryWire] = Field(default_factory=list)
    nutritionEntries: list[NutritionEntryWire] = Field(default_factory=list)
    supplementLogs: list[SupplementLogWire] = Field(default_factory=list)
    bodyMetrics: list[BodyMetricWire] = Field(default_factory=list)
    planView: PlanViewWire | None = None
    """Set only when it changed since the phone's lastSyncedAt."""
    deleted: DeletedWire = Field(default_factory=DeletedWire)
