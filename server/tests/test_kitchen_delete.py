import pytest
from fastmcp.exceptions import ToolError
from httpx import ASGITransport, AsyncClient

from ko_sync import mcp_server, store
from ko_sync.main import app


@pytest.fixture
async def client(monkeypatch):
    from ko_sync.config import settings

    monkeypatch.setenv("KO_API_TOKEN", "t")
    settings.cache_clear()
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as c:
        yield c


def _sync(client, since=0, **body):
    return client.post(
        "/v1/sync",
        json={"lastSyncedAt": since, **body},
        headers={"Authorization": "Bearer t"},
    )


async def _recipe(title="Pasta"):
    return await mcp_server.save_recipe(title, [{"name": "pasta"}], [{"text": "Boil."}])


async def test_delete_recipe_by_title_removes_it_and_tombstones_it():
    saved = await _recipe()
    out = await mcp_server.delete_recipe("pasta")
    assert out["deleted"] == saved["remoteId"]
    assert store.all_recipes() == []
    assert store.deleted_since(0)["recipes"] == [saved["remoteId"]]


async def test_delete_unknown_recipe_errors():
    with pytest.raises(ToolError):
        await mcp_server.delete_recipe("nope")


async def test_shopping_and_pantry_deletes():
    await mcp_server.add_to_shopping_list(["Flour", "Sugar"])
    out = await mcp_server.remove_from_shopping_list(["flour", "milk"])
    assert out == {"removed": ["Flour"], "notOnList": ["milk"]}
    assert [i["name"] for i in store.all_shopping_items()] == ["Sugar"]

    await mcp_server.update_pantry("Rice")
    await mcp_server.delete_pantry_item("rice")
    assert store.all_pantry_items() == []
    with pytest.raises(ToolError):
        await mcp_server.delete_pantry_item("rice")


async def test_plan_remove_one_and_clear_range():
    await _recipe()
    a = await mcp_server.add_to_meal_plan("Pasta", "2026-10-05", "DINNER")
    await mcp_server.add_to_meal_plan("Pasta", "2026-10-06", "DINNER")
    await mcp_server.add_to_meal_plan("Pasta", "2026-10-20", "DINNER")
    await mcp_server.remove_from_meal_plan(a["remoteId"])
    out = await mcp_server.clear_meal_plan("2026-10-05", "2026-10-11")
    assert out["removed"] == 1
    assert [e["date"] for e in store.plan_between(None, None)] == ["2026-10-20"]
    with pytest.raises(ToolError):
        await mcp_server.remove_from_meal_plan(a["remoteId"])


async def test_sync_delivers_tombstones_once(client):
    saved = await _recipe()
    since = store.now_millis()
    await mcp_server.delete_recipe(saved["remoteId"])
    body = (await _sync(client, since - 1)).json()
    assert body["deleted"]["recipes"] == [saved["remoteId"]]
    later = (await _sync(client, body["serverTime"] + 1)).json()
    assert later["deleted"]["recipes"] == []


async def test_plan_view_round_trips_through_claude_and_phone(client):
    assert await mcp_server.get_plan_view() == {"weeks": 1, "calendar": True}
    await mcp_server.set_plan_view(weeks=2)
    await mcp_server.set_plan_view(calendar=False)
    assert await mcp_server.get_plan_view() == {"weeks": 2, "calendar": False}
    with pytest.raises(ToolError):
        await mcp_server.set_plan_view(weeks=3)
    with pytest.raises(ToolError):
        await mcp_server.set_plan_view()

    pulled = (await _sync(client, 0)).json()
    assert pulled["planView"]["weeks"] == 2 and pulled["planView"]["calendar"] is False

    # The phone's newer choice wins; an older one doesn't.
    newer = store.now_millis() + 10_000
    await _sync(client, 0, push={"planView": {"weeks": 1, "calendar": True, "updatedAt": newer}})
    assert await mcp_server.get_plan_view() == {"weeks": 1, "calendar": True}
    await _sync(client, 0, push={"planView": {"weeks": 2, "calendar": True, "updatedAt": 5}})
    assert await mcp_server.get_plan_view() == {"weeks": 1, "calendar": True}
