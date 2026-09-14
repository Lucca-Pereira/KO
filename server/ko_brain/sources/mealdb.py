"""TheMealDB.

The phone used to do this fan-out itself, one request at a time: up to twenty serial round trips
per suggestion refresh, none of them cached. Here they run concurrently and the results stick
around, which is most of why suggestions stopped feeling slow.
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

import httpx

from .. import cache
from ..config import settings
from ..schemas import MealSummaryDto, RecipeDto, RecipeIngredientDto, RecipeStepDto

log = logging.getLogger(__name__)


def _client() -> httpx.AsyncClient:
    return httpx.AsyncClient(
        base_url=settings().mealdb_url,
        timeout=httpx.Timeout(15.0, connect=5.0),
        headers={"User-Agent": settings().user_agent},
    )


def _str(value: Any) -> str | None:
    """TheMealDB writes absent fields as an empty string or the literal text "null"."""
    if value is None:
        return None
    text = str(value).strip()
    if not text or text.lower() == "null":
        return None
    return text


async def _get(path: str, params: dict[str, str], cache_key: str) -> dict[str, Any]:
    cached = cache.get(cache_key)
    if cached is not None:
        return cached
    try:
        async with _client() as http:
            resp = await http.get(path, params=params)
            resp.raise_for_status()
            data = resp.json()
    except (httpx.HTTPError, ValueError) as exc:
        log.warning("TheMealDB %s %s failed: %s", path, params, exc)
        return {}
    cache.put(cache_key, data)
    return data


def _summaries(data: dict[str, Any]) -> list[MealSummaryDto]:
    meals = data.get("meals") or []
    out = []
    for meal in meals:
        meal_id = _str(meal.get("idMeal"))
        title = _str(meal.get("strMeal"))
        if meal_id and title:
            out.append(
                MealSummaryDto(id=meal_id, title=title, thumb_url=_str(meal.get("strMealThumb")))
            )
    return out


async def search_by_name(query: str) -> list[MealSummaryDto]:
    if not query.strip():
        return []
    return _summaries(await _get("search.php", {"s": query}, f"mealdb:name:{query.lower()}"))


async def filter_by_ingredient(ingredient: str) -> list[MealSummaryDto]:
    if not ingredient.strip():
        return []
    return _summaries(
        await _get("filter.php", {"i": ingredient}, f"mealdb:ing:{ingredient.lower()}")
    )


async def random_meal() -> RecipeDto | None:
    # Never cached: a cached random meal is the same meal forever.
    try:
        async with _client() as http:
            resp = await http.get("random.php")
            resp.raise_for_status()
            meals = resp.json().get("meals") or []
    except (httpx.HTTPError, ValueError):
        return None
    return _to_recipe(meals[0]) if meals else None


async def lookup(meal_id: str) -> RecipeDto | None:
    data = await _get("lookup.php", {"i": meal_id}, f"mealdb:id:{meal_id}")
    meals = data.get("meals") or []
    return _to_recipe(meals[0]) if meals else None


def _to_recipe(meal: dict[str, Any]) -> RecipeDto:
    ingredients: list[RecipeIngredientDto] = []
    # The API stores ingredients as twenty numbered column pairs rather than a list.
    for i in range(1, 21):
        name = _str(meal.get(f"strIngredient{i}"))
        if not name:
            continue
        ingredients.append(
            RecipeIngredientDto(name=name, amount=_str(meal.get(f"strMeasure{i}")) or "")
        )

    instructions = _str(meal.get("strInstructions")) or ""
    steps = [
        RecipeStepDto(text=line.strip())
        for line in instructions.replace("\r\n", "\n").split("\n")
        if line.strip()
    ]

    tags = [t.strip() for t in (_str(meal.get("strTags")) or "").split(",") if t.strip()]
    for key in ("strCategory", "strArea"):
        value = _str(meal.get(key))
        if value and value not in tags:
            tags.append(value)

    meal_id = _str(meal.get("idMeal")) or ""
    return RecipeDto(
        title=_str(meal.get("strMeal")) or "Untitled",
        ingredients=ingredients,
        steps=steps,
        tags=tags,
        source_url=_str(meal.get("strSource"))
        or _str(meal.get("strYoutube"))
        or f"https://www.themealdb.com/meal/{meal_id}",
        image_url=_str(meal.get("strMealThumb")),
        mealdb_id=meal_id or None,
    )


async def find_for_terms(terms: list[str], per_term: int = 3) -> list[MealSummaryDto]:
    """Searches every term at once and returns de-duplicated hits in the order asked for.

    Each term is tried by name first and by ingredient second, because a model asked for a
    "2-3 word search term" sometimes returns an ingredient.
    """

    async def one(term: str) -> list[MealSummaryDto]:
        hits = await search_by_name(term)
        if not hits:
            hits = await filter_by_ingredient(term)
        return hits[:per_term]

    results = await asyncio.gather(*(one(t) for t in terms if t.strip()), return_exceptions=True)

    seen: dict[str, MealSummaryDto] = {}
    for result in results:
        if isinstance(result, BaseException):
            continue
        for meal in result:
            seen.setdefault(meal.id, meal)
    return list(seen.values())
