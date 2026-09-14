"""The MCP surface, for Claude.

Same functions the phone's REST API calls — this file is adapters, not logic.

**Read and compute only.** These tools cannot touch your pantry, shopping list or meal plan.
That is a design rule, not an omission: the phone's Room database is the single source of truth
and the NAS is stateless apart from caches. Letting Claude write would make the NAS a second
source of truth and turn this into a bidirectional offline-sync problem, which is a bigger
project than everything else here put together.
"""

from __future__ import annotations

from fastmcp import FastMCP

from .parsing import parse_line
from .schemas import (
    EstimateRequest,
    GenerateRecipeRequest,
    ImportRecipeRequest,
    NutritionIngredient,
    PantryEntry,
    SuggestRequest,
    TranslateRequest,
)
from .sources.foodtable import normalize
from .tools import foods as foods_tool
from .tools import nutrition as nutrition_tool
from .tools import recipes as recipes_tool
from .tools import suggest as suggest_tool
from .tools import translate as translate_tool

mcp = FastMCP(
    name="KO Kitchen",
    instructions=(
        "Tools for a personal kitchen: suggest dishes from a pantry, write recipes, look up "
        "recipes and packaged foods, and estimate nutrition. Everything is read-only — you "
        "cannot change the user's pantry, shopping list or meal plan from here."
    ),
)


def _pantry(names: list[str]) -> list[PantryEntry]:
    return [PantryEntry(name=n) for n in names if n.strip()]


@mcp.tool
async def suggest_dishes(
    pantry: list[str],
    count: int = 5,
    constraints: str = "",
    exclude: list[str] | None = None,
) -> dict:
    """Suggest dishes someone could cook from the ingredients they already have.

    Args:
        pantry: Ingredient names currently in stock.
        count: How many dishes to suggest (1-12).
        constraints: Free text, e.g. "vegetarian" or "nothing that needs an oven".
        exclude: Dish titles to avoid suggesting again.
    """
    result = await suggest_tool.suggest(
        SuggestRequest(
            pantry=_pantry(pantry),
            count=max(1, min(count, 12)),
            constraints=constraints,
            exclude=exclude or [],
        )
    )
    return result.model_dump()


@mcp.tool
async def write_recipe(
    prompt: str,
    pantry: list[str] | None = None,
    servings: int = 2,
    constraints: str = "",
) -> dict:
    """Write a complete recipe: real amounts and step-by-step method.

    Args:
        prompt: What to cook, e.g. "something with chicken thighs and rice".
        pantry: Ingredients already available, to prefer.
        servings: How many people it should serve.
        constraints: Dietary or equipment limits.
    """
    recipe = await recipes_tool.generate_recipe(
        GenerateRecipeRequest(
            prompt=prompt,
            pantry=_pantry(pantry or []),
            servings=servings,
            constraints=constraints,
        )
    )
    return recipe.model_dump()


@mcp.tool
async def search_recipes(query: str, limit: int = 10) -> dict:
    """Search TheMealDB for recipes by dish name, falling back to a main ingredient.

    Args:
        query: A dish name like "beef wellington", or an ingredient like "salmon".
        limit: Maximum results.
    """
    return (await recipes_tool.search_meals(query, limit)).model_dump()


@mcp.tool
async def get_recipe(mealdb_id: str) -> dict | None:
    """Fetch one full TheMealDB recipe by id, including ingredients and method.

    Args:
        mealdb_id: The id from search_recipes, e.g. "52772".
    """
    recipe = await recipes_tool.import_recipe(ImportRecipeRequest(mealdb_id=mealdb_id))
    return recipe.model_dump() if recipe else None


@mcp.tool
async def translate_foods(names: list[str]) -> dict:
    """Translate grocery or food names into common English supermarket terms.

    Args:
        names: Names in any language, e.g. ["mantequilla", "cebolla"].
    """
    return (await translate_tool.translate(TranslateRequest(names=names))).model_dump()


@mcp.tool
async def estimate_nutrition(
    ingredients: list[str],
    servings: int = 1,
    title: str = "",
) -> dict:
    """Estimate calories and macros for a list of ingredients.

    Matches a local food table first and only estimates the remainder with a model, so the
    response says per ingredient whether the number was looked up or guessed.

    Args:
        ingredients: Lines like "200 g chicken breast", "1 tbsp olive oil".
        servings: How many servings the total divides into.
        title: Optional dish name, for context.
    """
    # Claude sends whole lines; the phone sends structured fields. Parsing here is what lets
    # the MCP path reach the deterministic food table instead of asking the model to guess
    # every ingredient.
    parsed = []
    for line in ingredients:
        if not line.strip():
            continue
        quantity, unit, name = parse_line(line)
        parsed.append(
            NutritionIngredient(
                name=name or line.strip(),
                normalized_name=normalize(name or line),
                quantity=quantity,
                unit=unit,
                raw=line.strip(),
            )
        )
    result = await nutrition_tool.estimate(
        EstimateRequest(title=title, servings=servings, ingredients=parsed)
    )
    return result.model_dump()


@mcp.tool
async def lookup_barcode(barcode: str) -> dict | None:
    """Look up a packaged food by barcode via Open Food Facts.

    Returns null when the product is unknown, or known but carrying no nutrition data.

    Args:
        barcode: An EAN or UPC, digits only.
    """
    food = await foods_tool.by_barcode(barcode)
    return food.model_dump() if food else None


@mcp.tool
async def search_foods(query: str, limit: int = 25) -> dict:
    """Search the local table of common whole foods and supplements, with macros per 100 g.

    Args:
        query: Part of a food name, e.g. "chicken" or "whey".
        limit: Maximum results.
    """
    return (await foods_tool.search(query, limit)).model_dump()
