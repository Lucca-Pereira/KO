import sqlite3

import pytest
from fastmcp.exceptions import ToolError
from httpx import ASGITransport, AsyncClient

from ko_sync import mcp_budget, store
from ko_sync.config import settings

MONTH = "2026-09"


def test_store_round_trip_and_last_write_wins():
    wire = {
        "remoteId": "e1",
        "amountCents": 1250,
        "category": "food",
        "note": "lunch",
        "spentOn": "2026-09-05",
        "deleted": False,
        "updatedAt": 100,
    }
    store.upsert_gym("expenses", wire)
    assert store.gym_by_id("expenses", "e1") == wire
    store.upsert_gym("expenses", {**wire, "amountCents": 1, "updatedAt": 50})  # stale: ignored
    assert store.gym_by_id("expenses", "e1")["amountCents"] == 1250
    store.upsert_gym("budget_limits", {"category": "food", "monthlyCents": 100, "updatedAt": 10})
    store.upsert_gym("budget_limits", {"category": "food", "monthlyCents": 300, "updatedAt": 20})
    store.upsert_gym("budget_limits", {"category": "food", "monthlyCents": 999, "updatedAt": 15})
    assert store.live_limits() == [
        {"category": "food", "monthlyCents": 300, "deleted": False, "updatedAt": 20}
    ]


async def test_mcp_tools_store_cents_and_validate():
    e = await mcp_budget.add_expense(12.345, "Food ", note="x", spent_on="2026-09-05")
    assert e["amount"] == 12.35 and e["category"] == "food"
    assert store.gym_by_id("expenses", e["remoteId"])["amountCents"] == 1235
    await mcp_budget.update_expense(e["remoteId"], amount=10, category="Eating Out")
    [got] = await mcp_budget.list_expenses(MONTH, "eating out")
    assert got["amount"] == 10.0
    assert await mcp_budget.list_expenses("2026-08") == []
    await mcp_budget.delete_expense(e["remoteId"])
    assert await mcp_budget.list_expenses(MONTH) == []
    assert store.gym_by_id("expenses", e["remoteId"])["deleted"] is True
    for bad in (
        mcp_budget.add_expense(-1, "x"),
        mcp_budget.add_expense(1, " "),
        mcp_budget.add_expense(1, "x", spent_on="5/9/2026"),
        mcp_budget.list_expenses("sept"),
        mcp_budget.delete_expense("nope"),
    ):
        with pytest.raises(ToolError):
            await bad


async def test_summary_math():
    await mcp_budget.add_expense(10.10, "food", spent_on="2026-09-01")
    await mcp_budget.add_expense(20.20, "Food", spent_on="2026-09-30")
    await mcp_budget.add_expense(5, "fun", spent_on="2026-09-10")
    await mcp_budget.add_expense(99, "food", spent_on="2026-10-01")  # other month
    await mcp_budget.set_budget_limit("food", 50)
    await mcp_budget.set_budget_limit("rent", 100)
    await mcp_budget.set_budget_limit("gone", 5)
    await mcp_budget.set_budget_limit("gone", 0)  # removes
    s = await mcp_budget.get_budget_summary(MONTH)
    assert s["categories"] == [
        {"category": "food", "spent": 30.3, "limit": 50.0, "remaining": 19.7},
        {"category": "fun", "spent": 5.0, "limit": None, "remaining": None},
        {"category": "rent", "spent": 0.0, "limit": 100.0, "remaining": 100.0},
    ]
    assert s["total"] == {"spent": 35.3, "limit": 150.0, "remaining": 114.7}


async def test_sync_round_trip_with_tombstone_and_prune():
    from ko_sync.main import app

    claude = await mcp_budget.add_expense(3, "food", spent_on="2026-09-02")
    await mcp_budget.delete_expense(claude["remoteId"])
    push = {
        "lastSyncedAt": 0,
        "push": {
            "expenses": [
                {
                    "remoteId": "p1",
                    "amountCents": 450,
                    "category": "food",
                    "spentOn": "2026-09-03",
                    "updatedAt": 5,
                }
            ],
            "budgetLimits": [{"category": "Food", "monthlyCents": 20000, "updatedAt": 5}],
        },
    }
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as c:
        body = (await c.post("/v1/sync", json=push)).json()
        pulled = {e["remoteId"]: e for e in body["expenses"]}
        assert pulled[claude["remoteId"]]["deleted"] is True
        assert pulled["p1"]["amountCents"] == 450 and pulled["p1"]["note"] is None
        assert body["budgetLimits"][0]["category"] == "food"
        assert body["budgetLimits"][0]["monthlyCents"] == 20000
        # Omitted present lists prune nothing; present lists that omit a row delete it.
        later = store.now_millis() + 1
        await c.post("/v1/sync", json={"lastSyncedAt": later, "present": {"recipes": []}})
        assert store.gym_by_id("expenses", "p1") is not None
        await c.post(
            "/v1/sync",
            json={"lastSyncedAt": later, "present": {"expenses": [], "budgetLimits": []}},
        )
        assert store.gym_by_id("expenses", "p1") is None
        assert store.live_limits() == []


def test_old_database_without_budget_tables_upgrades_in_place():
    path = settings().db_path
    old = sqlite3.connect(path)
    old.executescript(
        """
        CREATE TABLE plan_view (id INTEGER PRIMARY KEY, weeks INTEGER NOT NULL DEFAULT 1,
            calendar INTEGER NOT NULL DEFAULT 1, updated_at INTEGER NOT NULL);
        CREATE TABLE shopping_items (remote_id TEXT PRIMARY KEY, name TEXT NOT NULL,
            created_at INTEGER NOT NULL);
        CREATE TABLE pantry_items (remote_id TEXT PRIMARY KEY, name TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'IN_STOCK', category TEXT, quantity TEXT, note TEXT,
            updated_at INTEGER NOT NULL);
        INSERT INTO pantry_items (remote_id, name, updated_at) VALUES ('p', 'rice', 1);
        """
    )
    old.commit()
    old.close()
    store.init()
    assert [p["name"] for p in store.all_pantry_items()] == ["rice"]  # data survives
    store.upsert_gym(
        "expenses",
        {
            "remoteId": "e",
            "amountCents": 1,
            "category": "x",
            "spentOn": "2026-09-01",
            "updatedAt": 1,
        },
    )
    assert len(store.expenses_between("2026-09-01", "2026-09-30")) == 1
