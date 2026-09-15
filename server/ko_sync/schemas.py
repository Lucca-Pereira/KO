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


class SyncPush(BaseModel):
    recipes: list[RecipeWire] = Field(default_factory=list)
    pantryItems: list[PantryItemWire] = Field(default_factory=list)
    shoppingItems: list[ShoppingItemWire] = Field(default_factory=list)
    mealPlanEntries: list[MealPlanEntryWire] = Field(default_factory=list)


class SyncRequest(BaseModel):
    lastSyncedAt: int = 0
    """Epoch millis; 0 means the phone has never synced before."""
    push: SyncPush = Field(default_factory=SyncPush)


class SyncResponse(BaseModel):
    serverTime: int
    recipes: list[RecipeWire] = Field(default_factory=list)
    pantryItems: list[PantryItemWire] = Field(default_factory=list)
    shoppingItems: list[ShoppingItemWire] = Field(default_factory=list)
    mealPlanEntries: list[MealPlanEntryWire] = Field(default_factory=list)
