import pytest
from fastmcp.exceptions import ToolError
from httpx import ASGITransport, AsyncClient

from ko_sync import mcp_gym, store

DAY = "2026-09-29"

WHEY = {
    "name": "Whey protein",
    "kind": "PROTEIN",
    "doseAmount": 1,
    "doseUnit": "scoop",
    "kcalPerDose": 112,
    "proteinPerDose": 24,
    "carbsPerDose": 2.3,
    "fatPerDose": 1.5,
    "dosesPerDay": 1,
    "active": True,
}
CREATINE = {
    **WHEY,
    "name": "Creatine",
    "kind": "CREATINE",
    "kcalPerDose": 0,
    "proteinPerDose": 0,
    "carbsPerDose": 0,
    "fatPerDose": 0,
}


@pytest.fixture
def phone_context():
    store.replace_supplements([WHEY, CREATINE])
    store.replace_targets(
        [
            {
                "effectiveFrom": "2026-09-01",
                "kcal": 2500,
                "proteinG": 160,
                "carbsG": 280,
                "fatG": 80,
                "source": "ADAPTIVE",
            }
        ]
    )


# ---- Food -------------------------------------------------------------------------------------


async def test_log_food_then_get_day_shows_totals_and_remaining(phone_context):
    await mcp_gym.log_food("3 eggs", 234, 19, 1.5, 16, slot="breakfast", date=DAY)
    day = await mcp_gym.get_day(DAY)
    assert [e["label"] for e in day["entries"]] == ["3 eggs"]
    assert day["entries"][0]["slot"] == "BREAKFAST"
    assert day["totals"]["proteinG"] == 19
    assert day["remaining"]["kcal"] == 2500 - 234


async def test_update_and_delete_food_entry(phone_context):
    entry = await mcp_gym.log_food("3 eggs", 234, 19, 1.5, 16, date=DAY)
    await mcp_gym.update_food_entry(entry["remoteId"], label="2 eggs", kcal=156)
    [fixed] = (await mcp_gym.get_day(DAY))["entries"]
    assert (fixed["label"], fixed["kcal"], fixed["proteinG"]) == ("2 eggs", 156, 19)

    await mcp_gym.delete_food_entry(entry["remoteId"])
    assert (await mcp_gym.get_day(DAY))["entries"] == []
    # A tombstone, not a hard delete: the phone still has to hear about it.
    assert store.gym_by_id("nutrition_entries", entry["remoteId"])["deleted"] is True


async def test_bad_input_is_a_tool_error_not_a_crash(phone_context):
    with pytest.raises(ToolError):
        await mcp_gym.log_food("x", 10, 1, 1, 1, slot="brunch")
    with pytest.raises(ToolError):
        await mcp_gym.log_food("x", 10, 1, 1, 1, date="29/09/2026")
    with pytest.raises(ToolError):
        await mcp_gym.delete_food_entry("nope")


# ---- Supplements ------------------------------------------------------------------------------


async def test_log_supplement_with_macros_adds_the_diary_line(phone_context):
    await mcp_gym.log_supplement("whey", date=DAY)  # partial name is enough
    day = await mcp_gym.get_day(DAY)
    assert {s["name"]: s["taken"] for s in day["supplements"]} == {
        "Whey protein": True,
        "Creatine": False,
    }
    [line] = day["entries"]
    assert (line["sourceType"], line["proteinG"]) == ("SUPPLEMENT", 24)


async def test_relogging_a_supplement_replaces_the_dose(phone_context):
    await mcp_gym.log_supplement("Whey protein", date=DAY)
    await mcp_gym.log_supplement("Whey protein", date=DAY, doses=2)
    day = await mcp_gym.get_day(DAY)
    assert [e["proteinG"] for e in day["entries"]] == [48]
    assert len(store.supplement_logs_between(DAY, DAY)) == 1


async def test_zero_calorie_supplement_has_no_diary_line(phone_context):
    result = await mcp_gym.log_supplement("creatine", date=DAY)
    assert result["diaryEntry"] is None
    assert (await mcp_gym.get_day(DAY))["entries"] == []


async def test_unlog_supplement_removes_log_and_line(phone_context):
    await mcp_gym.log_supplement("Whey protein", date=DAY)
    await mcp_gym.unlog_supplement("Whey protein", date=DAY)
    day = await mcp_gym.get_day(DAY)
    assert day["entries"] == []
    assert all(not s["taken"] for s in day["supplements"])


async def test_unknown_supplement_lists_the_known_ones(phone_context):
    with pytest.raises(ToolError, match="Creatine"):
        await mcp_gym.log_supplement("fish oil")


# ---- Body -------------------------------------------------------------------------------------


async def test_body_metrics_merge_within_a_day(phone_context):
    await mcp_gym.log_body_metrics(date=DAY, weight_kg=78.4)
    await mcp_gym.log_body_metrics(date=DAY, waist_cm=82)
    body = (await mcp_gym.get_day(DAY))["body"]
    assert (body["weightKg"], body["waistCm"]) == (78.4, 82)


async def test_history_covers_every_day_in_range(phone_context):
    await mcp_gym.log_food("lunch", 700, 40, 80, 20, date="2026-09-28")
    await mcp_gym.log_body_metrics(date=DAY, weight_kg=78.4)
    days = await mcp_gym.get_gym_history("2026-09-28", DAY)
    assert [d["date"] for d in days] == ["2026-09-28", DAY]
    assert days[0]["totals"]["kcal"] == 700
    assert days[1]["weightKg"] == 78.4


# ---- /v1/sync -----------------------------------------------------------------------------------


async def test_sync_carries_gym_rows_both_ways_and_tombstones():
    from ko_sync.main import app

    store.replace_supplements([WHEY])
    claude_entry = await mcp_gym.log_food("toast", 180, 6, 30, 3, date=DAY)
    await mcp_gym.delete_food_entry(claude_entry["remoteId"])

    push = {
        "lastSyncedAt": 0,
        "push": {
            "nutritionEntries": [
                {"remoteId": "n1", "date": DAY, "label": "oats", "kcal": 300, "updatedAt": 1000}
            ],
            "supplementLogs": [
                {
                    "remoteId": "s1",
                    "date": DAY,
                    "supplementName": "Whey protein",
                    "updatedAt": 1000,
                }
            ],
            "bodyMetrics": [{"date": DAY, "weightKg": 78.0, "updatedAt": 1000}],
        },
        "supplements": [WHEY, CREATINE],
    }
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as c:
        body = (await c.post("/v1/sync", json=push)).json()

    pulled = {e["remoteId"]: e for e in body["nutritionEntries"]}
    assert pulled["n1"]["label"] == "oats"
    assert pulled[claude_entry["remoteId"]]["deleted"] is True  # the phone must hear about it
    assert body["bodyMetrics"][0]["date"] == DAY
    assert {s["name"] for s in store.all_supplements()} == {"Whey protein", "Creatine"}


async def test_sync_without_gym_present_lists_prunes_nothing_there():
    from ko_sync.main import app

    await mcp_gym.log_body_metrics(date=DAY, weight_kg=78.0)
    later = store.now_millis() + 1
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as c:
        # A 0.10 phone: kitchen lists only.
        await c.post("/v1/sync", json={"lastSyncedAt": later, "present": {"recipes": []}})
        assert store.gym_by_id("body_metrics", DAY) is not None
        # A 0.11 phone that deleted the weigh-in.
        await c.post("/v1/sync", json={"lastSyncedAt": later, "present": {"bodyMetrics": []}})
    assert store.gym_by_id("body_metrics", DAY) is None
