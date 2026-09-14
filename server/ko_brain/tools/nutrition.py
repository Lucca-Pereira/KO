"""Estimating a recipe's macros.

**Table first, model second.** A 7B model's macro guesses vary by 20% or more between runs on
the same input, which makes a calorie tracker decorative rather than useful. So every ingredient
is matched against the local food table deterministically, and only the leftovers — the
ingredients the table has never heard of — go to the model.

The response says which method produced each line and what fraction of the recipe is actually
accounted for, so the UI can be honest about a number it half-guessed.
"""

from __future__ import annotations

import logging

from ..config import settings
from ..ollama import OllamaError
from ..ollama import generate as llm
from ..prompts import load
from ..schemas import (
    AiPortionGuesses,
    EstimateRequest,
    EstimateResponse,
    IngredientEstimate,
    Macros,
    NutritionIngredient,
)
from ..sources import foodtable

log = logging.getLogger(__name__)


def _sum(estimates: list[IngredientEstimate]) -> Macros:
    fibers = [e.macros.fiber_g for e in estimates if e.macros.fiber_g is not None]
    return Macros(
        kcal=round(sum(e.macros.kcal for e in estimates), 1),
        protein_g=round(sum(e.macros.protein_g for e in estimates), 1),
        carbs_g=round(sum(e.macros.carbs_g for e in estimates), 1),
        fat_g=round(sum(e.macros.fat_g for e in estimates), 1),
        fiber_g=round(sum(fibers), 1) if fibers else None,
    )


def _divide(macros: Macros, servings: int) -> Macros:
    n = max(1, servings)
    return Macros(
        kcal=round(macros.kcal / n, 1),
        protein_g=round(macros.protein_g / n, 1),
        carbs_g=round(macros.carbs_g / n, 1),
        fat_g=round(macros.fat_g / n, 1),
        fiber_g=round(macros.fiber_g / n, 1) if macros.fiber_g is not None else None,
    )


def _from_table(ingredient: NutritionIngredient) -> IngredientEstimate | None:
    key = ingredient.normalized_name.strip() or foodtable.normalize(ingredient.name)
    food = foodtable.find(key)
    if food is None:
        return None
    grams = foodtable.grams_for(ingredient.quantity, ingredient.unit, food)
    if grams is None:
        # The table knows the food but not how much of it: "some chicken" is a real ingredient
        # with no weight. Better to report it unmatched than to invent a portion.
        return None
    macros = foodtable.macros_for_grams(food, grams)
    return IngredientEstimate(
        name=ingredient.name,
        grams=round(grams, 1),
        macros=Macros(**macros),
        method="TABLE",
        confidence=0.9,
    )


async def _from_model(unmatched: list[NutritionIngredient]) -> dict[str, IngredientEstimate]:
    if not unmatched:
        return {}

    def amount_of(i: NutritionIngredient) -> str:
        if i.raw.strip():
            return i.raw.strip()
        written = f"{i.quantity or ''} {i.unit or ''}".strip()
        return written or "amount unspecified"

    lines = [f"- {i.name}: {amount_of(i)}" for i in unmatched]
    try:
        guesses = await llm(
            model=settings().model_nutrition,
            system=load("nutrition"),
            user="Estimate weight and macros for each:\n" + "\n".join(lines),
            schema=AiPortionGuesses,
            temperature=0.1,
        )
    except OllamaError as exc:
        log.warning("Nutrition estimate failed: %s", exc)
        return {}

    out: dict[str, IngredientEstimate] = {}
    for guess in guesses.items:
        out[guess.name.strip().lower()] = IngredientEstimate(
            name=guess.name,
            grams=round(guess.grams, 1),
            macros=Macros(
                kcal=round(guess.kcal, 1),
                protein_g=round(guess.protein_g, 1),
                carbs_g=round(guess.carbs_g, 1),
                fat_g=round(guess.fat_g, 1),
            ),
            method="AI",
            confidence=0.4,
        )
    return out


async def estimate(req: EstimateRequest) -> EstimateResponse:
    if not req.ingredients:
        return EstimateResponse(
            per_serving=Macros(), total=Macros(), coverage=0.0, note="No ingredients to estimate."
        )

    estimates: list[IngredientEstimate] = []
    unmatched: list[NutritionIngredient] = []

    for ingredient in req.ingredients:
        hit = _from_table(ingredient)
        if hit is not None:
            estimates.append(hit)
        else:
            unmatched.append(ingredient)

    guessed = await _from_model(unmatched)
    for ingredient in unmatched:
        guess = guessed.get(ingredient.name.strip().lower())
        estimates.append(
            guess
            if guess is not None
            else IngredientEstimate(name=ingredient.name, method="UNKNOWN", confidence=0.0)
        )

    # Order the results the way the recipe lists them, not the order they were resolved in.
    order = {i.name: n for n, i in enumerate(req.ingredients)}
    estimates.sort(key=lambda e: order.get(e.name, 999))

    accounted = [e for e in estimates if e.method != "UNKNOWN"]
    total = _sum(accounted)
    coverage = len(accounted) / len(req.ingredients)

    from_table = sum(1 for e in accounted if e.method == "TABLE")
    note_parts = [f"{len(accounted)} of {len(req.ingredients)} ingredients counted"]
    if from_table < len(accounted):
        note_parts.append(f"{len(accounted) - from_table} estimated by the model")
    if coverage < 1.0:
        note_parts.append("the rest could not be weighed")

    return EstimateResponse(
        per_serving=_divide(total, req.servings),
        total=total,
        per_ingredient=estimates,
        coverage=round(coverage, 2),
        note="; ".join(note_parts) + ".",
    )
