from ko_sync import store


def test_recipe_round_trip():
    wire = {
        "remoteId": store.new_id(),
        "title": "Chicken Teriyaki",
        "servings": 2,
        "ingredients": [{"name": "chicken thigh", "amount": "400 g", "optional": False}],
        "steps": [{"text": "Sear and glaze.", "minutes": 15}],
        "updatedAt": 1000,
    }
    saved = store.upsert_recipe(wire)
    assert saved["title"] == "Chicken Teriyaki"
    assert store.find_recipe_by_title("chicken teriyaki")["remoteId"] == wire["remoteId"]


def test_recipe_last_write_wins():
    remote_id = store.new_id()
    store.upsert_recipe({"remoteId": remote_id, "title": "Old", "updatedAt": 1000})
    # An older write loses against what's already stored.
    result = store.upsert_recipe({"remoteId": remote_id, "title": "Stale", "updatedAt": 500})
    assert result["title"] == "Old"

    # A newer write wins.
    result = store.upsert_recipe({"remoteId": remote_id, "title": "New", "updatedAt": 2000})
    assert result["title"] == "New"


def test_recipes_changed_since():
    store.upsert_recipe({"remoteId": store.new_id(), "title": "A", "updatedAt": 1000})
    store.upsert_recipe({"remoteId": store.new_id(), "title": "B", "updatedAt": 2000})
    changed = store.recipes_changed_since(1000)
    assert [r["title"] for r in changed] == ["B"]


def test_pantry_upsert_idempotent_on_retry():
    remote_id = store.new_id()
    wire = {"remoteId": remote_id, "name": "Onion", "status": "IN_STOCK", "updatedAt": 1000}
    store.upsert_pantry_item(wire)
    store.upsert_pantry_item(wire)  # simulated retry after a dropped response
    assert len(store.pantry_changed_since(0)) == 1


def test_pantry_find_by_name_case_insensitive():
    store.upsert_pantry_item({"remoteId": store.new_id(), "name": "Onion", "updatedAt": 1000})
    assert store.find_pantry_item_by_name("onion") is not None
    assert store.find_pantry_item_by_name("ONION") is not None
    assert store.find_pantry_item_by_name("garlic") is None


def test_shopping_items_are_add_only():
    remote_id = store.new_id()
    store.insert_shopping_item({"remoteId": remote_id, "name": "flour"})
    store.insert_shopping_item({"remoteId": remote_id, "name": "flour"})  # retried push
    assert len(store.shopping_changed_since(0)) == 1


def test_plan_entries_changed_since():
    store.insert_plan_entry(
        {
            "remoteId": store.new_id(),
            "recipeTitle": "Chicken Teriyaki",
            "date": "2026-09-20",
            "slot": "DINNER",
        }
    )
    assert len(store.plan_changed_since(0)) == 1
