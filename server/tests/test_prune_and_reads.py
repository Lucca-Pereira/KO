from httpx import ASGITransport, AsyncClient

from ko_sync import mcp_server, store


def _pantry(remote_id, name, updated_at):
    store.upsert_pantry_item({"remoteId": remote_id, "name": name, "updatedAt": updated_at})


# ---- prune_absent ------------------------------------------------------------------------------


def test_prune_drops_rows_the_phone_already_saw_and_no_longer_has():
    _pantry("p1", "Onion", 1000)
    _pantry("p2", "Garlic", 1000)
    removed = store.prune_absent("pantry_items", ["p1"], seen_before=2000)
    assert removed == 1
    assert [p["name"] for p in store.all_pantry_items()] == ["Onion"]


def test_prune_keeps_rows_newer_than_the_phones_last_sync():
    # Claude added this after the phone's last sync — the phone hasn't pulled it yet.
    _pantry("claude", "Leeks", 3000)
    assert store.prune_absent("pantry_items", [], seen_before=2000) == 0
    assert [p["name"] for p in store.all_pantry_items()] == ["Leeks"]


def test_prune_does_nothing_on_a_first_sync():
    _pantry("p1", "Onion", 1000)
    assert store.prune_absent("pantry_items", [], seen_before=0) == 0


# ---- /v1/sync with `present` --------------------------------------------------------------------


async def test_sync_prunes_only_when_present_is_sent():
    from ko_sync.main import app

    store.insert_shopping_item({"remoteId": "s1", "name": "flour"})
    store.insert_shopping_item({"remoteId": "s2", "name": "sugar"})
    later = store.now_millis() + 1

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as c:
        # An old app version: no `present`, nothing pruned.
        assert (await c.post("/v1/sync", json={"lastSyncedAt": later})).status_code == 200
        assert len(store.all_shopping_items()) == 2

        body = {"lastSyncedAt": later, "present": {"shoppingItems": ["s2"]}}
        assert (await c.post("/v1/sync", json=body)).status_code == 200
    assert [i["name"] for i in store.all_shopping_items()] == ["sugar"]


# ---- MCP read tools ----------------------------------------------------------------------------


def _recipe(remote_id, title):
    store.upsert_recipe(
        {
            "remoteId": remote_id,
            "title": title,
            "ingredients": [{"name": "chicken thigh", "amount": "400 g"}],
            "steps": [{"text": "Cook."}],
            "updatedAt": 1000,
        }
    )


async def test_search_recipes_matches_part_of_the_title():
    _recipe("r1", "Chicken Teriyaki")
    _recipe("r2", "Beef Stew")
    found = await mcp_server.search_recipes("teri")
    assert [r["title"] for r in found] == ["Chicken Teriyaki"]
    assert found[0]["steps"] == [{"text": "Cook."}]


async def test_list_recipes_is_brief():
    _recipe("r1", "Chicken Teriyaki")
    [brief] = await mcp_server.list_recipes()
    assert brief["ingredients"] == ["chicken thigh"]
    assert "steps" not in brief


async def test_get_meal_plan_filters_by_inclusive_date_range():
    for rid, date in [("m1", "2026-09-27"), ("m2", "2026-09-28"), ("m3", "2026-10-05")]:
        store.insert_plan_entry(
            {"remoteId": rid, "recipeTitle": "X", "date": date, "slot": "DINNER"}
        )
    week = await mcp_server.get_meal_plan("2026-09-28", "2026-10-04")
    assert [e["remoteId"] for e in week] == ["m2"]
    assert len(await mcp_server.get_meal_plan()) == 3


async def test_add_to_shopping_list_skips_what_is_already_on_it():
    store.insert_shopping_item({"remoteId": "s1", "name": "Flour"})
    added = await mcp_server.add_to_shopping_list(["flour", "eggs", "Eggs", " "])
    assert [i["name"] for i in added] == ["eggs"]
    assert [i["name"] for i in await mcp_server.get_shopping_list()] == ["Flour", "eggs"]
