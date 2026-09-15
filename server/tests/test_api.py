import pytest
from httpx import ASGITransport, AsyncClient

from ko_sync.main import app


@pytest.fixture
async def client():
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c


async def test_health_is_unauthenticated(client):
    resp = await client.get("/health")
    assert resp.status_code == 200
    assert resp.json()["ok"] is True


async def test_sync_push_then_pull(client):
    push = {
        "lastSyncedAt": 0,
        "push": {
            "recipes": [
                {
                    "remoteId": "r1",
                    "title": "Chicken Teriyaki",
                    "servings": 2,
                    "ingredients": [],
                    "steps": [],
                    "updatedAt": 1000,
                }
            ],
            "pantryItems": [{"remoteId": "p1", "name": "Onion", "updatedAt": 1000}],
            "shoppingItems": [{"remoteId": "s1", "name": "flour"}],
            "mealPlanEntries": [
                {
                    "remoteId": "m1",
                    "recipeTitle": "Chicken Teriyaki",
                    "date": "2026-09-20",
                    "slot": "DINNER",
                }
            ],
        },
    }
    resp = await client.post("/v1/sync", json=push)
    assert resp.status_code == 200
    body = resp.json()
    assert [r["title"] for r in body["recipes"]] == ["Chicken Teriyaki"]
    assert [p["name"] for p in body["pantryItems"]] == ["Onion"]
    assert [s["name"] for s in body["shoppingItems"]] == ["flour"]
    assert len(body["mealPlanEntries"]) == 1

    # A second sync with the returned serverTime as lastSyncedAt sees nothing new.
    resp2 = await client.post("/v1/sync", json={"lastSyncedAt": body["serverTime"], "push": {}})
    body2 = resp2.json()
    assert body2["recipes"] == []
    assert body2["pantryItems"] == []


async def test_retried_push_does_not_duplicate(client):
    row = {"remoteId": "r1", "name": "Onion", "updatedAt": 1000}
    push = {"lastSyncedAt": 0, "push": {"pantryItems": [row]}}
    await client.post("/v1/sync", json=push)
    resp = await client.post("/v1/sync", json=push)
    assert len(resp.json()["pantryItems"]) == 1
