"""The gym side of the MCP surface: log food, supplements and weigh-ins by talking to Claude.

The phone stays the brain — targets, adaptive TDEE and the charts are computed there from what
gets logged. These tools only record, read back, and correct. Unlike the kitchen tools, a diary
line or supplement tick *can* be removed from here (a `deleted` tombstone the phone applies on its
next pull), because "actually it was two eggs" has to be fixable without opening the app.
"""

from __future__ import annotations

from datetime import date as Date
from datetime import timedelta

from fastmcp.exceptions import ToolError

from . import store
from .config import today
from .mcp_server import DELETE, READ, WRITE, mcp

SLOTS = ("BREAKFAST", "LUNCH", "DINNER", "SNACK", "SUPPLEMENT")


def _date(value: str | None) -> str:
    if value is None:
        return today()
    try:
        return Date.fromisoformat(value).isoformat()
    except ValueError as e:
        raise ToolError(f"date must be an ISO date like 2026-09-29, got {value!r}") from e


def _slot(value: str) -> str:
    slot = value.strip().upper()
    if slot not in SLOTS:
        raise ToolError(f"slot must be one of {', '.join(SLOTS)}, got {value!r}")
    return slot


def _supplement(name: str) -> dict:
    """By exact name, else by a unique partial match ("whey" -> "Whey protein")."""
    known = store.all_supplements()
    wanted = name.strip().lower()
    exact = [s for s in known if s["name"].lower() == wanted]
    partial = [s for s in known if wanted in s["name"].lower()]
    match = exact or (partial if len(partial) == 1 else [])
    if not match:
        names = ", ".join(s["name"] for s in known) or "none yet — add them in the app"
        raise ToolError(f"No single supplement matches {name!r}. Known: {names}")
    return match[0]


def _totals(entries: list[dict]) -> dict:
    return {
        k: round(sum(e[k] or 0 for e in entries), 1) for k in ("kcal", "proteinG", "carbsG", "fatG")
    }


def _live_supplement_entries(day: str, supplement_name: str) -> list[dict]:
    return [
        e
        for e in store.entries_between(day, day)
        if e["sourceType"] == "SUPPLEMENT"
        and (e["supplementName"] or "").lower() == supplement_name.lower()
    ]


def _tombstone(table: str, row: dict) -> dict:
    return store.upsert_gym(table, {**row, "deleted": True, "updatedAt": store.newer_than(row)})


# ---- Read -----------------------------------------------------------------------------------


@mcp.tool(annotations=READ)
async def get_day(date: str | None = None) -> dict:
    """Everything about one day: diary lines, totals, the target in force and what's left,
    supplements taken, and that day's weigh-in/measurements.

    Args:
        date: ISO date. Omit for today.
    """
    day = _date(date)
    entries = store.entries_between(day, day)
    totals = _totals(entries)
    target = store.target_on(day)
    taken = {s["supplementName"].lower() for s in store.supplement_logs_between(day, day)}
    body = store.body_metrics_between(day, day)
    return {
        "date": day,
        "entries": entries,
        "totals": totals,
        "target": target,
        "remaining": (
            {k: round(target[k] - totals[k], 1) for k in ("kcal", "proteinG", "carbsG", "fatG")}
            if target
            else None
        ),
        "supplements": [
            {"name": s["name"], "taken": s["name"].lower() in taken}
            for s in store.all_supplements()
            if s["active"]
        ],
        "body": body[0] if body else None,
    }


@mcp.tool(annotations=READ)
async def get_gym_history(start_date: str, end_date: str | None = None) -> list[dict]:
    """Per-day totals, target, supplements taken and weight over a range (inclusive), for
    "how was my week?" questions.

    Args:
        start_date: ISO date.
        end_date: ISO date. Omit for today.
    """
    start, end = _date(start_date), _date(end_date)
    if start > end:
        raise ToolError("start_date is after end_date")
    if Date.fromisoformat(end) - Date.fromisoformat(start) > timedelta(days=92):
        raise ToolError("Ask for at most about three months at a time.")

    entries = store.entries_between(start, end)
    logs = store.supplement_logs_between(start, end)
    body = {b["date"]: b for b in store.body_metrics_between(start, end)}
    days = []
    d = Date.fromisoformat(start)
    while d.isoformat() <= end:
        key = d.isoformat()
        day_entries = [e for e in entries if e["date"] == key]
        days.append(
            {
                "date": key,
                "totals": _totals(day_entries),
                "entryCount": len(day_entries),
                "target": store.target_on(key),
                "supplementsTaken": sorted({x["supplementName"] for x in logs if x["date"] == key}),
                "weightKg": (body.get(key) or {}).get("weightKg"),
            }
        )
        d += timedelta(days=1)
    return days


@mcp.tool(annotations=READ)
async def get_supplements() -> list[dict]:
    """The supplements set up in the app, with dose and macros per dose. Use these names for
    log_supplement."""
    return store.all_supplements()


# ---- Write ----------------------------------------------------------------------------------


@mcp.tool(annotations=WRITE)
async def log_food(
    label: str,
    kcal: float,
    protein_g: float,
    carbs_g: float,
    fat_g: float,
    slot: str = "SNACK",
    date: str | None = None,
    grams: float | None = None,
    servings: float | None = None,
    fiber_g: float | None = None,
    note: str | None = None,
) -> dict:
    """Add a line to the food diary. The macros are totals for what was eaten, not per 100 g.

    Args:
        label: What it was, e.g. "3 eggs, scrambled" — shown in the diary.
        kcal: Total calories.
        protein_g: Total protein in grams.
        carbs_g: Total carbs in grams.
        fat_g: Total fat in grams.
        slot: BREAKFAST, LUNCH, DINNER or SNACK.
        date: ISO date. Omit for today.
        grams: Total weight eaten, if known.
        servings: Number of servings, if that's how it was described.
        fiber_g: Total fibre in grams, if known.
        note: e.g. "estimated".
    """
    if min(kcal, protein_g, carbs_g, fat_g) < 0:
        raise ToolError("Calories and macros can't be negative.")
    return store.upsert_gym(
        "nutrition_entries",
        {
            "remoteId": store.new_id(),
            "date": _date(date),
            "slot": _slot(slot),
            "sourceType": "QUICK",
            "label": label.strip(),
            "grams": grams,
            "servings": servings,
            "kcal": kcal,
            "proteinG": protein_g,
            "carbsG": carbs_g,
            "fatG": fat_g,
            "fiberG": fiber_g,
            "note": note,
            "updatedAt": store.now_millis(),
        },
    )


@mcp.tool(annotations=WRITE)
async def update_food_entry(
    remote_id: str,
    label: str | None = None,
    kcal: float | None = None,
    protein_g: float | None = None,
    carbs_g: float | None = None,
    fat_g: float | None = None,
    slot: str | None = None,
    date: str | None = None,
    grams: float | None = None,
    servings: float | None = None,
    note: str | None = None,
) -> dict:
    """Correct a diary line. Only the fields you pass change.

    Args:
        remote_id: The entry's remoteId, from get_day.
        label: New description.
        kcal: New total calories.
        protein_g: New total protein.
        carbs_g: New total carbs.
        fat_g: New total fat.
        slot: BREAKFAST, LUNCH, DINNER or SNACK.
        date: Move it to another ISO date.
        grams: New total weight.
        servings: New number of servings.
        note: New note.
    """
    entry = store.gym_by_id("nutrition_entries", remote_id)
    if entry is None or entry["deleted"]:
        raise ToolError(f"No diary entry {remote_id!r}. get_day lists the current ones.")
    changes = {
        "label": label,
        "kcal": kcal,
        "proteinG": protein_g,
        "carbsG": carbs_g,
        "fatG": fat_g,
        "slot": _slot(slot) if slot is not None else None,
        "date": _date(date) if date is not None else None,
        "grams": grams,
        "servings": servings,
        "note": note,
    }
    updated = {**entry, **{k: v for k, v in changes.items() if v is not None}}
    return store.upsert_gym("nutrition_entries", {**updated, "updatedAt": store.newer_than(entry)})


@mcp.tool(annotations=DELETE)
async def delete_food_entry(remote_id: str) -> dict:
    """Remove a diary line (it disappears from the phone on its next sync).

    Args:
        remote_id: The entry's remoteId, from get_day.
    """
    entry = store.gym_by_id("nutrition_entries", remote_id)
    if entry is None or entry["deleted"]:
        raise ToolError(f"No diary entry {remote_id!r}. get_day lists the current ones.")
    _tombstone("nutrition_entries", entry)
    return {"deleted": remote_id, "label": entry["label"], "date": entry["date"]}


@mcp.tool(annotations=WRITE)
async def log_supplement(name: str, date: str | None = None, doses: float = 1.0) -> dict:
    """Tick off a supplement for the day, e.g. "took my creatine". If it carries calories (a
    protein shake), its diary line is added too — same as tapping it in the app. Logging it
    again the same day replaces the dose rather than adding a second one.

    Args:
        name: The supplement's name from get_supplements (a unique part of it is enough).
        date: ISO date. Omit for today.
        doses: How many doses, e.g. 2 for two scoops.
    """
    if doses <= 0:
        raise ToolError("doses must be more than 0")
    supplement = _supplement(name)
    day = _date(date)
    now = store.now_millis()

    existing = [
        s
        for s in store.supplement_logs_between(day, day)
        if s["supplementName"].lower() == supplement["name"].lower()
    ]
    log = store.upsert_gym(
        "supplement_logs",
        {
            "remoteId": existing[0]["remoteId"] if existing else store.new_id(),
            "date": day,
            "supplementName": supplement["name"],
            "doses": doses,
            "updatedAt": store.newer_than(existing[0] if existing else None),
        },
    )

    # Replace, not add: the phone's own rule for re-ticking a supplement.
    for old in _live_supplement_entries(day, supplement["name"]):
        _tombstone("nutrition_entries", old)
    entry = None
    per = supplement
    if any(per[k] > 0 for k in ("kcalPerDose", "proteinPerDose", "carbsPerDose", "fatPerDose")):
        entry = store.upsert_gym(
            "nutrition_entries",
            {
                "remoteId": store.new_id(),
                "date": day,
                "slot": "SUPPLEMENT",
                "sourceType": "SUPPLEMENT",
                "supplementName": supplement["name"],
                "label": supplement["name"],
                "servings": doses,
                "kcal": per["kcalPerDose"] * doses,
                "proteinG": per["proteinPerDose"] * doses,
                "carbsG": per["carbsPerDose"] * doses,
                "fatG": per["fatPerDose"] * doses,
                "updatedAt": now,
            },
        )
    return {"log": log, "diaryEntry": entry}


@mcp.tool(annotations=DELETE)
async def unlog_supplement(name: str, date: str | None = None) -> dict:
    """Untick a supplement for the day, removing its diary line too.

    Args:
        name: The supplement's name from get_supplements.
        date: ISO date. Omit for today.
    """
    supplement = _supplement(name)
    day = _date(date)
    logs = [
        s
        for s in store.supplement_logs_between(day, day)
        if s["supplementName"].lower() == supplement["name"].lower()
    ]
    if not logs:
        raise ToolError(f"{supplement['name']} isn't logged on {day}.")
    for log in logs:
        _tombstone("supplement_logs", log)
    for entry in _live_supplement_entries(day, supplement["name"]):
        _tombstone("nutrition_entries", entry)
    return {"unlogged": supplement["name"], "date": day}


@mcp.tool(annotations=WRITE)
async def log_body_metrics(
    date: str | None = None,
    weight_kg: float | None = None,
    body_fat_pct: float | None = None,
    waist_cm: float | None = None,
    chest_cm: float | None = None,
    hip_cm: float | None = None,
    arm_cm: float | None = None,
    thigh_cm: float | None = None,
    neck_cm: float | None = None,
    note: str | None = None,
) -> dict:
    """Record a weigh-in and/or measurements. One record per day: fields you pass are set,
    anything else already recorded that day is kept. The phone's calorie target adapts from
    the weight trend.

    Args:
        date: ISO date. Omit for today.
        weight_kg: Body weight in kg.
        body_fat_pct: Body fat percentage.
        waist_cm: Waist in cm.
        chest_cm: Chest in cm.
        hip_cm: Hips in cm.
        arm_cm: Arm in cm.
        thigh_cm: Thigh in cm.
        neck_cm: Neck in cm.
        note: Freeform note.
    """
    values = {
        "weightKg": weight_kg,
        "bodyFatPct": body_fat_pct,
        "waistCm": waist_cm,
        "chestCm": chest_cm,
        "hipCm": hip_cm,
        "armCm": arm_cm,
        "thighCm": thigh_cm,
        "neckCm": neck_cm,
        "note": note,
    }
    given = {k: v for k, v in values.items() if v is not None}
    if not given:
        raise ToolError("Pass at least one measurement.")
    if any(isinstance(v, float | int) and v <= 0 for v in given.values()):
        raise ToolError("Measurements must be positive.")
    day = _date(date)
    existing = store.gym_by_id("body_metrics", day)
    return store.upsert_gym(
        "body_metrics",
        {**(existing or {}), **given, "date": day, "updatedAt": store.newer_than(existing)},
    )
