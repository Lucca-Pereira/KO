"""The MCP surface, for Claude Desktop.

Unlike the old `ko_brain` service, these tools **do** write — that's the whole point of this
service's existence (see the plan this came out of: the NAS is now a second source of truth,
deliberately, with the phone's `SyncRepository` reconciling it on foreground). Every write here
goes through the same `store.py` functions the phone's own sync push uses, so a recipe Claude
saves shows up in the phone's next pull exactly like one it wrote itself.

There is still one boundary: nothing here deletes. Deleting stays phone-local, consistent with
the rest of the app's "propose additions, review the risky bit" pattern.
"""

from __future__ import annotations

from fastmcp import FastMCP

from . import store

mcp = FastMCP(
    name="KO Kitchen",
    instructions=(
        "Tools for lucca's personal kitchen app. You can read the pantry, search recipes, save "
        "or edit a recipe, update a pantry item's stock status, add items to the shopping list, "
        "and add a recipe to the meal plan. Changes here appear on the phone next time it syncs "
        "(on app foreground, or a manual 'Sync now'). Nothing here can delete anything — deleting "
        "stays phone-local."
    ),
)


@mcp.tool
async def get_pantry() -> list[dict]:
    """List everything currently in the pantry, with stock status."""
    return store.all_pantry_items()


@mcp.tool
async def search_recipes(query: str) -> list[dict]:
    """Find a recipe already saved in the library by title (case-insensitive, exact match).

    Args:
        query: The recipe's title.
    """
    match = store.find_recipe_by_title(query)
    return [match] if match else []


@mcp.tool
async def save_recipe(
    title: str,
    ingredients: list[dict],
    steps: list[dict],
    remote_id: str | None = None,
    servings: int = 2,
    prep_minutes: int | None = None,
    cook_minutes: int | None = None,
    notes: str | None = None,
    tags: list[str] | None = None,
    kcal_per_serving: float | None = None,
    protein_g: float | None = None,
    carbs_g: float | None = None,
    fat_g: float | None = None,
    macro_note: str | None = None,
) -> dict:
    """Save a new recipe, or edit an existing one.

    Args:
        title: The recipe's name.
        ingredients: Each item like {"name": "chicken thigh", "amount": "400 g", "optional": false}.
        steps: Each item like {"text": "Marinate for 10 minutes.", "minutes": 10}.
        remote_id: Omit to create a new recipe. Pass an existing recipe's id (from search_recipes
            or get_pantry-style listing) to replace it — send the whole recipe, not a diff.
        servings: How many servings this makes.
        prep_minutes: Prep time in minutes.
        cook_minutes: Cook time in minutes.
        notes: Freeform notes.
        tags: Short tags like ["quick", "chicken"].
        kcal_per_serving: Estimated calories per serving.
        protein_g: Estimated protein grams per serving.
        carbs_g: Estimated carb grams per serving.
        fat_g: Estimated fat grams per serving.
        macro_note: A note on where the macro estimate came from, e.g. "estimated from ingredients".
    """
    wire = {
        "remoteId": remote_id or store.new_id(),
        "title": title,
        "servings": servings,
        "prepMinutes": prep_minutes,
        "cookMinutes": cook_minutes,
        "notes": notes,
        "tags": tags or [],
        "kcalPerServing": kcal_per_serving,
        "proteinG": protein_g,
        "carbsG": carbs_g,
        "fatG": fat_g,
        "macroNote": macro_note,
        "ingredients": ingredients,
        "steps": steps,
        "updatedAt": store.now_millis(),
    }
    return store.upsert_recipe(wire)


@mcp.tool
async def update_pantry(
    name: str,
    status: str | None = None,
    category: str | None = None,
    quantity: str | None = None,
    note: str | None = None,
) -> dict:
    """Update a pantry item, or create one if it doesn't exist yet.

    Args:
        name: Matched against an existing pantry item case-insensitively.
        status: One of IN_STOCK, LOW, OUT. Omitted leaves an existing item's status alone.
        category: e.g. "produce", "spices". Omitted keeps the existing value.
        quantity: Freeform, e.g. "2 cans".
        note: Freeform note.
    """
    existing = store.find_pantry_item_by_name(name)
    wire = {
        "remoteId": existing["remoteId"] if existing else store.new_id(),
        "name": name,
        "status": status or (existing["status"] if existing else "IN_STOCK"),
        "category": category
        if category is not None
        else (existing["category"] if existing else None),
        "quantity": quantity
        if quantity is not None
        else (existing["quantity"] if existing else None),
        "note": note if note is not None else (existing["note"] if existing else None),
        "updatedAt": store.now_millis(),
    }
    return store.upsert_pantry_item(wire)


@mcp.tool
async def add_to_shopping_list(items: list[str]) -> list[dict]:
    """Add one or more items to the shopping list.

    Args:
        items: Item names, e.g. ["flour", "sugar"].
    """
    return [
        store.insert_shopping_item({"remoteId": store.new_id(), "name": i})
        for i in items
        if i.strip()
    ]


@mcp.tool
async def add_to_meal_plan(
    recipe_title: str, date: str, slot: str, servings: float | None = None
) -> dict:
    """Add a recipe to the meal plan on a given day.

    Args:
        recipe_title: Must match a recipe already saved (via save_recipe or already in the app).
        date: ISO date, e.g. "2026-09-20".
        slot: One of BREAKFAST, LUNCH, DINNER, OTHER.
        servings: How many servings to plan. Omit to use the recipe's default.
    """
    return store.insert_plan_entry(
        {
            "remoteId": store.new_id(),
            "recipeTitle": recipe_title,
            "date": date,
            "slot": slot,
            "servings": servings,
        }
    )
