"""Authoring and importing whole recipes.

This is the capability the app never had: Ollama used to produce only a dish *name* and a search
term, and every actual recipe came from TheMealDB. Here it can write one.
"""

from __future__ import annotations

import logging

from ..config import settings
from ..ollama import generate as llm
from ..prompts import load
from ..schemas import (
    GeneratedRecipe,
    GenerateRecipeRequest,
    ImportRecipeRequest,
    MealSearchResponse,
    RecipeDto,
)
from ..sources import mealdb

log = logging.getLogger(__name__)


async def generate_recipe(req: GenerateRecipeRequest) -> RecipeDto:
    cfg = settings()
    have = [e.best_name() for e in req.pantry if e.status != "OUT"]

    lines = []
    if req.prompt.strip():
        lines.append(f"Write a recipe for: {req.prompt.strip()}")
    else:
        lines.append("Write a recipe using mostly what is in the pantry.")
    if have:
        lines.append(f"Pantry: {', '.join(have)}")
    if req.constraints.strip():
        lines.append(f"Constraints: {req.constraints.strip()}")
    lines.append(f"Serves {req.servings}.")

    written = await llm(
        model=cfg.model_recipe,
        system=load("generate"),
        user="\n".join(lines),
        schema=GeneratedRecipe,
        temperature=0.6,
    )
    # Servings is pinned to what was asked for: the amounts were generated for that number, and
    # letting the model also name a servings count is how you get amounts for four labelled as
    # two. GeneratedRecipe has no url fields at all, so there is nothing to invent.
    return written.to_dto().model_copy(update={"servings": req.servings})


async def import_recipe(req: ImportRecipeRequest) -> RecipeDto | None:
    if req.mealdb_id:
        return await mealdb.lookup(req.mealdb_id.strip())
    # Importing from an arbitrary URL means scraping, which is a different problem (and a
    # different set of failure modes) than everything else here. Deliberately unimplemented
    # rather than half-implemented.
    return None


async def search_meals(query: str, limit: int = 20) -> MealSearchResponse:
    results = await mealdb.search_by_name(query)
    if not results:
        results = await mealdb.filter_by_ingredient(query)
    return MealSearchResponse(results=results[:limit])


async def meals_for_ideas(queries: list[str], per_query: int = 3) -> MealSearchResponse:
    """Looks up every suggestion's search term at once.

    The phone used to do this serially, one idea at a time, which is where most of the wait in
    a suggestion refresh came from.
    """
    return MealSearchResponse(results=await mealdb.find_for_terms(queries, per_query))
