"""The MCP surface, for Claude (a claude.ai connector, so the phone/desktop/web apps alike).

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

# Hints for the Claude apps' permission prompts: reads need no approval, writes are additive or
# undoable on the phone, and only the gym tools that remove a log entry are marked destructive.
READ = {"readOnlyHint": True, "openWorldHint": False}
WRITE = {"readOnlyHint": False, "destructiveHint": False, "openWorldHint": False}
DELETE = {"readOnlyHint": False, "destructiveHint": True, "openWorldHint": False}

mcp = FastMCP(
    name="KO Kitchen",
    instructions=(
        "Tools for lucca's personal kitchen and gym app (KO Kitchen). Changes appear on the phone "
        "next time the app syncs (whenever it's opened); what you read is as fresh as the phone's "
        "last sync.\n"
        "KITCHEN — suggest meals from what is actually in the pantry, plan the week, keep the "
        "shopping list current. Read: get_pantry (IN_STOCK / LOW / OUT), list_recipes / "
        "search_recipes, get_meal_plan, get_shopping_list. Write: save_recipe, update_pantry, "
        "add_to_meal_plan (save the recipe first), add_to_shopping_list. Nothing in the kitchen "
        "can be deleted from here.\n"
        "GYM — log what the user tells you they ate, took, or weighed, without asking them for "
        "numbers they didn't give: estimate calories and macros yourself for ordinary foods and "
        "say it's an estimate. Read: get_day (diary, totals vs target, supplements, weight), "
        "get_gym_history, get_supplements. Write: log_food, log_supplement (by the name in "
        "get_supplements — it adds the diary line itself when the supplement has calories, so "
        "don't also log_food it), log_body_metrics. Fix mistakes with update_food_entry, "
        "delete_food_entry, unlog_supplement. Pass the user's local date when they say "
        "'yesterday' etc.; omit it for today. The phone owns targets and supplement definitions."
    ),
)


@mcp.tool(annotations=READ)
async def get_pantry() -> list[dict]:
    """List everything currently in the pantry, with stock status."""
    return store.all_pantry_items()


@mcp.tool(annotations=READ)
async def list_recipes() -> list[dict]:
    """List every saved recipe in brief: title, tags, times, servings, macros and ingredient
    names. Use search_recipes for a recipe's full ingredients with amounts and its steps."""
    return [
        {
            "remoteId": r["remoteId"],
            "title": r["title"],
            "tags": r["tags"],
            "servings": r["servings"],
            "prepMinutes": r["prepMinutes"],
            "cookMinutes": r["cookMinutes"],
            "kcalPerServing": r["kcalPerServing"],
            "proteinG": r["proteinG"],
            "ingredients": [i.get("name") for i in r["ingredients"]],
        }
        for r in store.all_recipes()
    ]


@mcp.tool(annotations=READ)
async def search_recipes(query: str) -> list[dict]:
    """Find saved recipes whose title contains the query (case-insensitive), with full
    ingredients, amounts and steps.

    Args:
        query: All or part of the recipe's title, e.g. "teriyaki".
    """
    return store.search_recipes_by_title(query)


@mcp.tool(annotations=READ)
async def get_meal_plan(start_date: str | None = None, end_date: str | None = None) -> list[dict]:
    """List what's planned, ordered by date. Both bounds are inclusive and optional.

    Args:
        start_date: ISO date, e.g. "2026-09-28". Omit for no lower bound.
        end_date: ISO date, e.g. "2026-10-04". Omit for no upper bound.
    """
    return store.plan_between(start_date, end_date)


@mcp.tool(annotations=READ)
async def get_shopping_list() -> list[dict]:
    """List what's on the shopping list (items already ticked off on the phone are left out)."""
    return store.all_shopping_items()


@mcp.tool(annotations=WRITE)
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
        remote_id: Omit to create a new recipe. Pass an existing recipe's remoteId (from
            list_recipes or search_recipes) to replace it — send the whole recipe, not a diff.
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


@mcp.tool(annotations=WRITE)
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


@mcp.tool(annotations=WRITE)
async def add_to_shopping_list(items: list[str]) -> list[dict]:
    """Add one or more items to the shopping list. Items already on it are skipped.

    Args:
        items: Item names, e.g. ["flour", "sugar"].
    """
    on_list = {i["name"].strip().lower() for i in store.all_shopping_items()}
    added = []
    for name in items:
        key = name.strip().lower()
        if key and key not in on_list:
            on_list.add(key)
            added.append(store.insert_shopping_item({"remoteId": store.new_id(), "name": name}))
    return added


@mcp.tool(annotations=WRITE)
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


# Registers the gym tools on the same server; imported last because it imports mcp from here.
from . import mcp_gym  # noqa: E402, F401
