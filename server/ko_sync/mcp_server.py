"""The MCP surface, for Claude (a claude.ai connector, so the phone/desktop/web apps alike).

Unlike the old `ko_brain` service, these tools **do** write — that's the whole point of this
service's existence (see the plan this came out of: the NAS is now a second source of truth,
deliberately, with the phone's `SyncRepository` reconciling it on foreground). Every write here
goes through the same `store.py` functions the phone's own sync push uses, so a recipe Claude
saves shows up in the phone's next pull exactly like one it wrote itself.

Kitchen deletions go out as tombstones (`store.delete_kitchen_row`) that the phone applies on its
next pull, like the gym's `deleted` flag; the delete tools are marked destructive so the Claude
apps ask before running them. The phone's own deletions reach this store the other way, through
the `present` id lists (`store.prune_absent`).
"""

from __future__ import annotations

from fastmcp import FastMCP
from fastmcp.exceptions import ToolError

from . import fixed_week, store

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
        "add_to_meal_plan (save the recipe first), add_to_shopping_list. Remove with "
        "delete_recipe, delete_pantry_item, remove_from_shopping_list, remove_from_meal_plan, "
        "clear_meal_plan.\n"
        "The meal plan is a FIXED week, not a calendar: meals sit on a weekday (Monday to "
        "Sunday) of week 1, or of week 1 or 2 when the user keeps a two-week plan. There are no "
        "dates. get_plan_view says how many weeks there are and which one is the current week; "
        "'tonight' means today's weekday in that week.\n"
        "GYM — log what the user tells you they ate, took, or weighed, without asking them for "
        "numbers they didn't give: estimate calories and macros yourself for ordinary foods and "
        "say it's an estimate. Read: get_day (diary, totals vs target, supplements, weight), "
        "get_gym_history, get_supplements. Write: log_food, log_supplement (by the name in "
        "get_supplements — it adds the diary line itself when the supplement has calories, so "
        "don't also log_food it), log_body_metrics. Fix mistakes with update_food_entry, "
        "delete_food_entry, unlog_supplement. Pass the user's local date when they say "
        "'yesterday' etc.; omit it for today. The phone owns targets and supplement definitions.\n"
        "BUDGET - one currency, amounts as decimals. add_expense, list_expenses, update_expense, "
        "delete_expense, set_budget_limit (0 removes), get_budget_summary (per category spent / "
        "limit / remaining for a YYYY-MM month)."
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
async def get_meal_plan(week: int | None = None) -> list[dict]:
    """List the fixed meal plan, ordered by week, weekday and meal. Each entry has `week`,
    `weekday` (MONDAY..SUNDAY), `slot`, `recipeTitle`, `servings` and `remoteId`.

    Args:
        week: 1 or 2 to list just that week. Omit for the whole plan.
    """
    return _plan_entries(week)


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
async def add_to_shopping_list(items: list[str], quantities: list[str] | None = None) -> list[dict]:
    """Add one or more items to the shopping list. Items already on it are skipped.

    Args:
        items: Item names, e.g. ["flour", "sugar"].
        quantities: Optional amounts in the same order as items, e.g. ["500 g", "1 kg"]. Use ""
            for an item with no amount.
    """
    on_list = {i["name"].strip().lower() for i in store.all_shopping_items()}
    added = []
    for index, name in enumerate(items):
        key = name.strip().lower()
        if key and key not in on_list:
            on_list.add(key)
            given = quantities[index] if quantities and index < len(quantities) else ""
            quantity = given.strip() or None
            added.append(
                store.insert_shopping_item(
                    {"remoteId": store.new_id(), "name": name, "quantity": quantity},
                ),
            )
    return added


@mcp.tool(annotations=WRITE)
async def add_to_meal_plan(
    recipe_title: str,
    weekday: str,
    slot: str,
    week: int | None = None,
    servings: float | None = None,
) -> dict:
    """Add a recipe to the fixed meal plan on a weekday.

    Args:
        recipe_title: Must match a recipe already saved (via save_recipe or already in the app).
        weekday: MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY or SUNDAY.
        slot: One of BREAKFAST, LUNCH, DINNER, OTHER.
        week: 1 or 2. Omit to use the current week (see get_plan_view). 2 needs a two-week plan.
        servings: How many servings to plan. Omit to use the recipe's default.
    """
    view = store.get_plan_view()
    target_week = week if week is not None else view["activeWeek"]
    if target_week not in range(1, view["weeks"] + 1):
        raise ToolError(
            f"The plan has {view['weeks']} week(s), so week {target_week} doesn't exist. "
            "set_plan_view(weeks=2) makes it a two-week plan."
        )
    try:
        slot_date = fixed_week.slot_date(target_week, weekday)
    except ValueError as e:
        raise ToolError(str(e)) from e
    entry = store.insert_plan_entry(
        {
            "remoteId": store.new_id(),
            "recipeTitle": recipe_title,
            "date": slot_date,
            "slot": slot,
            "servings": servings,
        }
    )
    return _describe_plan_entry(entry)


@mcp.tool(annotations=DELETE)
async def delete_recipe(recipe: str) -> dict:
    """Permanently delete a saved recipe. Meals already planned with it stay on the plan under
    its old title; use remove_from_meal_plan / clear_meal_plan to drop those too.

    Args:
        recipe: The recipe's remoteId (from list_recipes) or its exact title.
    """
    found = _find_recipe(recipe)
    store.delete_kitchen_row("recipes", found["remoteId"])
    return {"deleted": found["remoteId"], "title": found["title"]}


@mcp.tool(annotations=DELETE)
async def delete_pantry_item(name: str) -> dict:
    """Remove an item from the pantry entirely. To record that it ran out, use update_pantry
    with status OUT instead.

    Args:
        name: The pantry item's name (case-insensitive, exact).
    """
    found = store.find_pantry_item_by_name(name.strip())
    if found is None:
        raise ToolError(f"No pantry item called {name!r}. get_pantry lists them.")
    store.delete_kitchen_row("pantryItems", found["remoteId"])
    return {"deleted": found["remoteId"], "name": found["name"]}


@mcp.tool(annotations=DELETE)
async def remove_from_shopping_list(items: list[str]) -> dict:
    """Remove one or more items from the shopping list.

    Args:
        items: Item names exactly as get_shopping_list shows them (case-insensitive).
    """
    removed, missing = [], []
    for name in items:
        found = store.find_shopping_item_by_name(name)
        if found is None:
            missing.append(name)
        else:
            store.delete_kitchen_row("shoppingItems", found["remoteId"])
            removed.append(found["name"])
    return {"removed": removed, "notOnList": missing}


@mcp.tool(annotations=DELETE)
async def remove_from_meal_plan(remote_id: str) -> dict:
    """Remove one planned meal. The recipe itself stays saved.

    Args:
        remote_id: The planned entry's remoteId, from get_meal_plan.
    """
    entry = store.get_plan_entry(remote_id)
    if entry is None:
        raise ToolError(f"No planned meal {remote_id!r}. get_meal_plan lists the current ones.")
    store.delete_kitchen_row("mealPlanEntries", remote_id)
    described = _describe_plan_entry(entry)
    return {
        "deleted": remote_id,
        "recipeTitle": described["recipeTitle"],
        "week": described["week"],
        "weekday": described["weekday"],
    }


@mcp.tool(annotations=DELETE)
async def clear_meal_plan(week: int | None = None) -> dict:
    """Remove every planned meal from one week of the fixed plan, or from the whole plan.
    Recipes stay saved.

    Args:
        week: 1 or 2 to clear just that week. Omit to clear the whole plan.
    """
    entries = _plan_entries(week)
    for entry in entries:
        store.delete_kitchen_row("mealPlanEntries", entry["remoteId"])
    return {"removed": len(entries), "week": week if week is not None else "all"}


@mcp.tool(annotations=READ)
async def get_plan_view() -> dict:
    """How the fixed meal plan is laid out: `weeks` (1 or 2) and `activeWeek` — which week is
    the current one, the week the phone opens on and the one "today" and "tonight" refer to.
    The user switches the active week by hand; nothing advances it automatically."""
    view = store.get_plan_view()
    return {"weeks": view["weeks"], "activeWeek": view["activeWeek"]}


@mcp.tool(annotations=WRITE)
async def set_plan_view(weeks: int | None = None, active_week: int | None = None) -> dict:
    """Change the fixed meal plan's layout. Only what you pass changes.

    Args:
        weeks: 1 for a one-week plan, 2 for a two-week plan. Going back to 1 hides week 2's
            meals without deleting them.
        active_week: 1 or 2 — which week is the current one. 2 needs a two-week plan.
    """
    if weeks is None and active_week is None:
        raise ToolError("Pass weeks and/or active_week.")
    if weeks is not None and weeks not in (1, 2):
        raise ToolError("weeks must be 1 or 2.")
    current = store.get_plan_view()
    new_weeks = weeks if weeks is not None else current["weeks"]
    new_active = active_week if active_week is not None else current["activeWeek"]
    if new_active not in range(1, new_weeks + 1):
        raise ToolError(f"active_week must be between 1 and {new_weeks} for this plan.")
    updated = store.upsert_plan_view(
        {
            "weeks": new_weeks,
            "calendar": False,
            "activeWeek": new_active,
            "updatedAt": max(store.now_millis(), current["updatedAt"] + 1),
        }
    )
    return {"weeks": updated["weeks"], "activeWeek": updated["activeWeek"]}


def _describe_plan_entry(entry: dict) -> dict:
    """A stored plan row as Claude sees it: week and weekday instead of the slot date."""
    week, weekday = fixed_week.describe(entry["date"]) or (None, None)
    return {
        "remoteId": entry["remoteId"],
        "recipeTitle": entry["recipeTitle"],
        "week": week,
        "weekday": weekday,
        "slot": entry["slot"],
        "servings": entry.get("servings"),
    }


def _plan_entries(week: int | None) -> list[dict]:
    """The fixed plan, or one week of it. Rows on real calendar dates — left from before the
    plan became fixed — fall outside the slot range and are never returned."""
    if week is None:
        start, end = fixed_week.FIRST, fixed_week.LAST
    else:
        try:
            start, end = fixed_week.week_range(week)
        except ValueError as e:
            raise ToolError(str(e)) from e
    return [_describe_plan_entry(e) for e in store.plan_between(start, end)]


def _find_recipe(ref: str) -> dict:
    ref = ref.strip()
    for r in store.all_recipes():
        if r["remoteId"] == ref or r["title"].lower() == ref.lower():
            return r
    raise ToolError(f"No recipe {ref!r}. list_recipes shows what's saved.")


# Registers the gym and budget tools on the same server; imported last because they import mcp
# from here.
from . import mcp_budget, mcp_gym  # noqa: E402, F401
