"""The shared SQLite store behind both front doors.

One table per collection, keyed by `remote_id` — the same id the phone assigns to a row at
creation time (see the app-side sync plan). Every write here is an upsert by that id, which is
what makes a retried push idempotent: applying the same row twice is a no-op the second time.

No ORM at this scale. Pydantic models (`schemas.py`) are the wire shape; this module only ever
sees plain values.
"""

from __future__ import annotations

import json
import sqlite3
import threading
import time
import uuid
from collections.abc import Iterator
from contextlib import contextmanager

from .config import settings

_lock = threading.Lock()
_connection: sqlite3.Connection | None = None


def now_millis() -> int:
    return int(time.time() * 1000)


def new_id() -> str:
    return str(uuid.uuid4())


def _connect() -> sqlite3.Connection:
    global _connection
    if _connection is None:
        conn = sqlite3.connect(settings().db_path, check_same_thread=False)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        _init_schema(conn)
        _connection = conn
    return _connection


def init() -> None:
    """Opens the database and creates any missing tables. Called once at startup."""
    with _lock:
        _connect()


def reset_for_tests() -> None:
    """Drops the cached connection so a new `db_path` (e.g. a tmp file per test) takes effect."""
    global _connection
    with _lock:
        if _connection is not None:
            _connection.close()
        _connection = None


def _init_schema(conn: sqlite3.Connection) -> None:
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS recipes (
            remote_id TEXT PRIMARY KEY,
            title TEXT NOT NULL,
            servings INTEGER NOT NULL DEFAULT 2,
            prep_minutes INTEGER,
            cook_minutes INTEGER,
            notes TEXT,
            tags TEXT NOT NULL DEFAULT '[]',
            kcal_per_serving REAL,
            protein_g REAL,
            carbs_g REAL,
            fat_g REAL,
            macro_note TEXT,
            ingredients TEXT NOT NULL DEFAULT '[]',
            steps TEXT NOT NULL DEFAULT '[]',
            updated_at INTEGER NOT NULL
        );

        CREATE TABLE IF NOT EXISTS pantry_items (
            remote_id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'IN_STOCK',
            category TEXT,
            quantity TEXT,
            note TEXT,
            updated_at INTEGER NOT NULL
        );

        CREATE TABLE IF NOT EXISTS shopping_items (
            remote_id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            created_at INTEGER NOT NULL
        );

        CREATE TABLE IF NOT EXISTS meal_plan_entries (
            remote_id TEXT PRIMARY KEY,
            recipe_title TEXT NOT NULL,
            date TEXT NOT NULL,
            slot TEXT NOT NULL,
            servings REAL,
            created_at INTEGER NOT NULL
        );

        -- Kitchen rows Claude deleted. The phone applies these on its next pull (the reverse of
        -- `prune_absent`, which handles deletions made on the phone).
        CREATE TABLE IF NOT EXISTS tombstones (
            collection TEXT NOT NULL,
            remote_id TEXT NOT NULL,
            deleted_at INTEGER NOT NULL,
            PRIMARY KEY (collection, remote_id)
        );

        -- How the phone's meal-plan screen is laid out; a single row (id = 1), last-write-wins.
        CREATE TABLE IF NOT EXISTS plan_view (
            id INTEGER PRIMARY KEY CHECK (id = 1),
            weeks INTEGER NOT NULL DEFAULT 1,
            calendar INTEGER NOT NULL DEFAULT 1,
            updated_at INTEGER NOT NULL
        );

        -- Gym. Diary lines and supplement ticks can be edited and deleted from Claude, so they
        -- carry updated_at (last-write-wins) and a `deleted` tombstone the phone applies on pull.
        CREATE TABLE IF NOT EXISTS nutrition_entries (
            remote_id TEXT PRIMARY KEY,
            date TEXT NOT NULL,
            slot TEXT NOT NULL DEFAULT 'SNACK',
            source_type TEXT NOT NULL DEFAULT 'QUICK',
            supplement_name TEXT,
            label TEXT NOT NULL,
            grams REAL,
            servings REAL,
            kcal REAL NOT NULL DEFAULT 0,
            protein_g REAL NOT NULL DEFAULT 0,
            carbs_g REAL NOT NULL DEFAULT 0,
            fat_g REAL NOT NULL DEFAULT 0,
            fiber_g REAL,
            note TEXT,
            deleted INTEGER NOT NULL DEFAULT 0,
            updated_at INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS nutrition_entries_date ON nutrition_entries (date);

        CREATE TABLE IF NOT EXISTS supplement_logs (
            remote_id TEXT PRIMARY KEY,
            date TEXT NOT NULL,
            supplement_name TEXT NOT NULL,
            doses REAL NOT NULL DEFAULT 1,
            deleted INTEGER NOT NULL DEFAULT 0,
            updated_at INTEGER NOT NULL
        );

        -- One row per date, like the phone; remote_id *is* the date.
        CREATE TABLE IF NOT EXISTS body_metrics (
            remote_id TEXT PRIMARY KEY,
            weight_kg REAL,
            body_fat_pct REAL,
            waist_cm REAL,
            chest_cm REAL,
            hip_cm REAL,
            arm_cm REAL,
            thigh_cm REAL,
            neck_cm REAL,
            note TEXT,
            updated_at INTEGER NOT NULL
        );

        -- Phone-owned snapshots, replaced wholesale on every sync. Claude reads, never writes.
        CREATE TABLE IF NOT EXISTS supplements (
            name TEXT PRIMARY KEY COLLATE NOCASE,
            kind TEXT NOT NULL,
            dose_amount REAL NOT NULL,
            dose_unit TEXT NOT NULL,
            kcal_per_dose REAL NOT NULL,
            protein_per_dose REAL NOT NULL,
            carbs_per_dose REAL NOT NULL,
            fat_per_dose REAL NOT NULL,
            doses_per_day INTEGER NOT NULL,
            active INTEGER NOT NULL
        );

        CREATE TABLE IF NOT EXISTS nutrition_targets (
            effective_from TEXT PRIMARY KEY,
            kcal REAL NOT NULL,
            protein_g REAL NOT NULL,
            carbs_g REAL NOT NULL,
            fat_g REAL NOT NULL,
            source TEXT NOT NULL
        );
        """
    )
    conn.commit()


@contextmanager
def _cursor() -> Iterator[sqlite3.Cursor]:
    with _lock:
        conn = _connect()
        cur = conn.cursor()
        try:
            yield cur
            conn.commit()
        except Exception:
            conn.rollback()
            raise


def _row_to_recipe(row: sqlite3.Row) -> dict:
    return {
        "remoteId": row["remote_id"],
        "title": row["title"],
        "servings": row["servings"],
        "prepMinutes": row["prep_minutes"],
        "cookMinutes": row["cook_minutes"],
        "notes": row["notes"],
        "tags": json.loads(row["tags"]),
        "kcalPerServing": row["kcal_per_serving"],
        "proteinG": row["protein_g"],
        "carbsG": row["carbs_g"],
        "fatG": row["fat_g"],
        "macroNote": row["macro_note"],
        "ingredients": json.loads(row["ingredients"]),
        "steps": json.loads(row["steps"]),
        "updatedAt": row["updated_at"],
    }


def _row_to_pantry(row: sqlite3.Row) -> dict:
    return {
        "remoteId": row["remote_id"],
        "name": row["name"],
        "status": row["status"],
        "category": row["category"],
        "quantity": row["quantity"],
        "note": row["note"],
        "updatedAt": row["updated_at"],
    }


def _row_to_shopping(row: sqlite3.Row) -> dict:
    return {"remoteId": row["remote_id"], "name": row["name"]}


def _row_to_plan(row: sqlite3.Row) -> dict:
    return {
        "remoteId": row["remote_id"],
        "recipeTitle": row["recipe_title"],
        "date": row["date"],
        "slot": row["slot"],
        "servings": row["servings"],
    }


# ---- Recipes --------------------------------------------------------------------------------


def upsert_recipe(wire: dict) -> dict:
    """Last-write-wins by `updatedAt`. Returns the row as stored (the winner)."""
    with _cursor() as cur:
        cur.execute("SELECT updated_at FROM recipes WHERE remote_id = ?", (wire["remoteId"],))
        existing = cur.fetchone()
        if existing is not None and existing["updated_at"] >= wire["updatedAt"]:
            cur.execute("SELECT * FROM recipes WHERE remote_id = ?", (wire["remoteId"],))
            return _row_to_recipe(cur.fetchone())

        cur.execute(
            """
            INSERT INTO recipes (remote_id, title, servings, prep_minutes, cook_minutes, notes,
                tags, kcal_per_serving, protein_g, carbs_g, fat_g, macro_note, ingredients, steps,
                updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(remote_id) DO UPDATE SET
                title=excluded.title, servings=excluded.servings,
                prep_minutes=excluded.prep_minutes, cook_minutes=excluded.cook_minutes,
                notes=excluded.notes, tags=excluded.tags,
                kcal_per_serving=excluded.kcal_per_serving, protein_g=excluded.protein_g,
                carbs_g=excluded.carbs_g, fat_g=excluded.fat_g, macro_note=excluded.macro_note,
                ingredients=excluded.ingredients, steps=excluded.steps,
                updated_at=excluded.updated_at
            """,
            (
                wire["remoteId"],
                wire["title"],
                wire.get("servings", 2),
                wire.get("prepMinutes"),
                wire.get("cookMinutes"),
                wire.get("notes"),
                json.dumps(wire.get("tags", [])),
                wire.get("kcalPerServing"),
                wire.get("proteinG"),
                wire.get("carbsG"),
                wire.get("fatG"),
                wire.get("macroNote"),
                json.dumps(wire.get("ingredients", [])),
                json.dumps(wire.get("steps", [])),
                wire["updatedAt"],
            ),
        )
    return wire


def recipes_changed_since(since_millis: int) -> list[dict]:
    with _cursor() as cur:
        cur.execute("SELECT * FROM recipes WHERE updated_at > ?", (since_millis,))
        return [_row_to_recipe(r) for r in cur.fetchall()]


def find_recipe_by_title(title: str) -> dict | None:
    with _cursor() as cur:
        cur.execute("SELECT * FROM recipes WHERE title = ? COLLATE NOCASE", (title,))
        row = cur.fetchone()
        return _row_to_recipe(row) if row else None


def search_recipes_by_title(query: str) -> list[dict]:
    """Substring match, case-insensitive. `instr` rather than LIKE so a `%` or `_` in the query
    is matched literally instead of acting as a wildcard."""
    with _cursor() as cur:
        cur.execute(
            "SELECT * FROM recipes WHERE instr(lower(title), lower(?)) > 0 "
            "ORDER BY title COLLATE NOCASE",
            (query.strip(),),
        )
        return [_row_to_recipe(r) for r in cur.fetchall()]


def all_recipes() -> list[dict]:
    with _cursor() as cur:
        cur.execute("SELECT * FROM recipes ORDER BY title COLLATE NOCASE")
        return [_row_to_recipe(r) for r in cur.fetchall()]


# ---- Pantry -----------------------------------------------------------------------------------


def upsert_pantry_item(wire: dict) -> dict:
    with _cursor() as cur:
        cur.execute("SELECT updated_at FROM pantry_items WHERE remote_id = ?", (wire["remoteId"],))
        existing = cur.fetchone()
        if existing is not None and existing["updated_at"] >= wire["updatedAt"]:
            cur.execute("SELECT * FROM pantry_items WHERE remote_id = ?", (wire["remoteId"],))
            return _row_to_pantry(cur.fetchone())

        cur.execute(
            """
            INSERT INTO pantry_items (remote_id, name, status, category, quantity, note, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(remote_id) DO UPDATE SET
                name=excluded.name, status=excluded.status, category=excluded.category,
                quantity=excluded.quantity, note=excluded.note, updated_at=excluded.updated_at
            """,
            (
                wire["remoteId"],
                wire["name"],
                wire.get("status", "IN_STOCK"),
                wire.get("category"),
                wire.get("quantity"),
                wire.get("note"),
                wire["updatedAt"],
            ),
        )
    return wire


def find_pantry_item_by_name(name: str) -> dict | None:
    with _cursor() as cur:
        cur.execute("SELECT * FROM pantry_items WHERE name = ? COLLATE NOCASE", (name,))
        row = cur.fetchone()
        return _row_to_pantry(row) if row else None


def all_pantry_items() -> list[dict]:
    with _cursor() as cur:
        cur.execute("SELECT * FROM pantry_items ORDER BY name COLLATE NOCASE")
        return [_row_to_pantry(r) for r in cur.fetchall()]


def pantry_changed_since(since_millis: int) -> list[dict]:
    with _cursor() as cur:
        cur.execute("SELECT * FROM pantry_items WHERE updated_at > ?", (since_millis,))
        return [_row_to_pantry(r) for r in cur.fetchall()]


# ---- Shopping (add-only) -----------------------------------------------------------------------


def insert_shopping_item(wire: dict) -> dict:
    with _cursor() as cur:
        cur.execute(
            "INSERT OR IGNORE INTO shopping_items (remote_id, name, created_at) VALUES (?, ?, ?)",
            (wire["remoteId"], wire["name"], now_millis()),
        )
    return wire


def shopping_changed_since(since_millis: int) -> list[dict]:
    with _cursor() as cur:
        cur.execute("SELECT * FROM shopping_items WHERE created_at > ?", (since_millis,))
        return [_row_to_shopping(r) for r in cur.fetchall()]


def all_shopping_items() -> list[dict]:
    with _cursor() as cur:
        cur.execute("SELECT * FROM shopping_items ORDER BY created_at")
        return [_row_to_shopping(r) for r in cur.fetchall()]


# ---- Meal plan (add-only) ----------------------------------------------------------------------


def insert_plan_entry(wire: dict) -> dict:
    with _cursor() as cur:
        cur.execute(
            """
            INSERT OR IGNORE INTO meal_plan_entries
                (remote_id, recipe_title, date, slot, servings, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            (
                wire["remoteId"],
                wire["recipeTitle"],
                wire["date"],
                wire["slot"],
                wire.get("servings"),
                now_millis(),
            ),
        )
    return wire


def plan_changed_since(since_millis: int) -> list[dict]:
    with _cursor() as cur:
        cur.execute("SELECT * FROM meal_plan_entries WHERE created_at > ?", (since_millis,))
        return [_row_to_plan(r) for r in cur.fetchall()]


def plan_between(start: str | None, end: str | None) -> list[dict]:
    """ISO dates compare correctly as strings, so plain text comparison is the date range."""
    with _cursor() as cur:
        cur.execute(
            """
            SELECT * FROM meal_plan_entries
            WHERE (? IS NULL OR date >= ?) AND (? IS NULL OR date <= ?)
            ORDER BY date, slot
            """,
            (start, start, end, end),
        )
        return [_row_to_plan(r) for r in cur.fetchall()]


# ---- Deletions made from Claude ----------------------------------------------------------------

# Collection name on the wire -> table. Only kitchen collections; gym rows carry their own
# `deleted` flag instead.
_KITCHEN_TABLES = {
    "recipes": "recipes",
    "pantryItems": "pantry_items",
    "shoppingItems": "shopping_items",
    "mealPlanEntries": "meal_plan_entries",
}


def delete_kitchen_row(collection: str, remote_id: str) -> bool:
    """Removes a row and records a tombstone so the phone drops it on its next sync."""
    table = _KITCHEN_TABLES[collection]
    with _cursor() as cur:
        cur.execute(f"DELETE FROM {table} WHERE remote_id = ?", (remote_id,))  # noqa: S608
        removed = cur.rowcount > 0
        if removed:
            cur.execute(
                "INSERT OR REPLACE INTO tombstones (collection, remote_id, deleted_at) "
                "VALUES (?, ?, ?)",
                (collection, remote_id, now_millis()),
            )
        return removed


def deleted_since(since_millis: int) -> dict[str, list[str]]:
    out: dict[str, list[str]] = {name: [] for name in _KITCHEN_TABLES}
    with _cursor() as cur:
        cur.execute(
            "SELECT collection, remote_id FROM tombstones WHERE deleted_at > ?", (since_millis,)
        )
        for r in cur.fetchall():
            out[r["collection"]].append(r["remote_id"])
    return out


def get_plan_entry(remote_id: str) -> dict | None:
    with _cursor() as cur:
        cur.execute("SELECT * FROM meal_plan_entries WHERE remote_id = ?", (remote_id,))
        row = cur.fetchone()
        return _row_to_plan(row) if row else None


def find_shopping_item_by_name(name: str) -> dict | None:
    with _cursor() as cur:
        cur.execute("SELECT * FROM shopping_items WHERE name = ? COLLATE NOCASE", (name.strip(),))
        row = cur.fetchone()
        return _row_to_shopping(row) if row else None


# ---- Meal-plan layout --------------------------------------------------------------------------


def get_plan_view() -> dict:
    with _cursor() as cur:
        cur.execute("SELECT * FROM plan_view WHERE id = 1")
        row = cur.fetchone()
    if row is None:
        return {"weeks": 1, "calendar": True, "updatedAt": 0}
    return {
        "weeks": row["weeks"],
        "calendar": bool(row["calendar"]),
        "updatedAt": row["updated_at"],
    }


def upsert_plan_view(wire: dict) -> dict:
    """Last-write-wins by `updatedAt`. Returns the stored winner."""
    current = get_plan_view()
    if current["updatedAt"] >= wire["updatedAt"]:
        return current
    with _cursor() as cur:
        cur.execute(
            "INSERT INTO plan_view (id, weeks, calendar, updated_at) VALUES (1, ?, ?, ?) "
            "ON CONFLICT(id) DO UPDATE SET weeks=excluded.weeks, calendar=excluded.calendar, "
            "updated_at=excluded.updated_at",
            (wire["weeks"], int(wire["calendar"]), wire["updatedAt"]),
        )
    return get_plan_view()


# ---- Phone-side deletions ----------------------------------------------------------------------

# table -> the column that says when the server first had the row's current version.
_PRUNABLE = {
    "recipes": "updated_at",
    "pantry_items": "updated_at",
    "shopping_items": "created_at",
    "meal_plan_entries": "created_at",
    "nutrition_entries": "updated_at",
    "supplement_logs": "updated_at",
    "body_metrics": "updated_at",
}


def prune_absent(table: str, present_ids: list[str], seen_before: int) -> int:
    """Drop rows the phone no longer has, so Claude stops seeing things deleted on the phone
    (or ticked off the shopping list).

    Only rows at or before `seen_before` (the phone's `lastSyncedAt`) are candidates: those are
    rows the phone has already pulled, so their absence from its list means it deleted them. A
    row written after that — e.g. something Claude just added — hasn't reached the phone yet, and
    its absence means nothing. `seen_before == 0` (a phone's very first sync, or a reinstall)
    prunes nothing at all.
    """
    if seen_before <= 0:
        return 0
    column = _PRUNABLE[table]
    with _cursor() as cur:
        cur.execute("CREATE TEMP TABLE IF NOT EXISTS _present (remote_id TEXT PRIMARY KEY)")
        cur.execute("DELETE FROM _present")
        cur.executemany(
            "INSERT OR IGNORE INTO _present (remote_id) VALUES (?)", [(i,) for i in present_ids]
        )
        cur.execute(
            f"DELETE FROM {table} WHERE {column} <= ? "  # noqa: S608 - table/column from _PRUNABLE
            "AND remote_id NOT IN (SELECT remote_id FROM _present)",
            (seen_before,),
        )
        removed = cur.rowcount
        cur.execute("DELETE FROM _present")
        return removed


# ---- Gym ----------------------------------------------------------------------------------------

# wire field (camelCase, as on the phone) -> column, per table. `remoteId`/`updatedAt` are implied.
_ENTRY_FIELDS = {
    "date": "date",
    "slot": "slot",
    "sourceType": "source_type",
    "supplementName": "supplement_name",
    "label": "label",
    "grams": "grams",
    "servings": "servings",
    "kcal": "kcal",
    "proteinG": "protein_g",
    "carbsG": "carbs_g",
    "fatG": "fat_g",
    "fiberG": "fiber_g",
    "note": "note",
    "deleted": "deleted",
}
_SUPPLEMENT_LOG_FIELDS = {
    "date": "date",
    "supplementName": "supplement_name",
    "doses": "doses",
    "deleted": "deleted",
}
_BODY_FIELDS = {
    "weightKg": "weight_kg",
    "bodyFatPct": "body_fat_pct",
    "waistCm": "waist_cm",
    "chestCm": "chest_cm",
    "hipCm": "hip_cm",
    "armCm": "arm_cm",
    "thighCm": "thigh_cm",
    "neckCm": "neck_cm",
    "note": "note",
}
_GYM_TABLES = {
    "nutrition_entries": ("remoteId", _ENTRY_FIELDS),
    "supplement_logs": ("remoteId", _SUPPLEMENT_LOG_FIELDS),
    "body_metrics": ("date", _BODY_FIELDS),  # a body metric's id is its date
}


def _gym_row(table: str, row: sqlite3.Row) -> dict:
    key_field, fields = _GYM_TABLES[table]
    out = {key_field: row["remote_id"], "updatedAt": row["updated_at"]}
    for wire, column in fields.items():
        value = row[column]
        out[wire] = bool(value) if wire == "deleted" else value
    return out


def upsert_gym(table: str, wire: dict) -> dict:
    """Last-write-wins by `updatedAt`, like recipes. Returns the row as stored (the winner)."""
    key_field, fields = _GYM_TABLES[table]
    columns = list(fields.values())
    with _cursor() as cur:
        cur.execute(f"SELECT * FROM {table} WHERE remote_id = ?", (wire[key_field],))  # noqa: S608
        existing = cur.fetchone()
        if existing is not None and existing["updated_at"] >= wire["updatedAt"]:
            return _gym_row(table, existing)
        values = [int(wire.get(w) or 0) if w == "deleted" else wire.get(w) for w in fields]
        cur.execute(
            f"INSERT INTO {table} (remote_id, {', '.join(columns)}, updated_at) "  # noqa: S608
            f"VALUES (?, {', '.join('?' for _ in columns)}, ?) "
            f"ON CONFLICT(remote_id) DO UPDATE SET "
            + ", ".join(f"{c}=excluded.{c}" for c in [*columns, "updated_at"]),
            (wire[key_field], *values, wire["updatedAt"]),
        )
        cur.execute(f"SELECT * FROM {table} WHERE remote_id = ?", (wire[key_field],))  # noqa: S608
        return _gym_row(table, cur.fetchone())


def newer_than(row: dict | None) -> int:
    """A timestamp guaranteed to win last-write-wins against `row`. An edit made in the same
    millisecond as the write it corrects would otherwise lose the tie and silently vanish."""
    now = now_millis()
    return max(now, row["updatedAt"] + 1) if row else now


def gym_changed_since(table: str, since_millis: int) -> list[dict]:
    with _cursor() as cur:
        cur.execute(f"SELECT * FROM {table} WHERE updated_at > ?", (since_millis,))  # noqa: S608
        return [_gym_row(table, r) for r in cur.fetchall()]


def gym_by_id(table: str, key: str) -> dict | None:
    with _cursor() as cur:
        cur.execute(f"SELECT * FROM {table} WHERE remote_id = ?", (key,))  # noqa: S608
        row = cur.fetchone()
        return _gym_row(table, row) if row else None


def entries_between(start: str, end: str) -> list[dict]:
    """Live (non-deleted) diary lines, oldest first."""
    with _cursor() as cur:
        cur.execute(
            "SELECT * FROM nutrition_entries WHERE deleted = 0 AND date BETWEEN ? AND ? "
            "ORDER BY date, updated_at",
            (start, end),
        )
        return [_gym_row("nutrition_entries", r) for r in cur.fetchall()]


def supplement_logs_between(start: str, end: str) -> list[dict]:
    with _cursor() as cur:
        cur.execute(
            "SELECT * FROM supplement_logs WHERE deleted = 0 AND date BETWEEN ? AND ? "
            "ORDER BY date",
            (start, end),
        )
        return [_gym_row("supplement_logs", r) for r in cur.fetchall()]


def body_metrics_between(start: str, end: str) -> list[dict]:
    with _cursor() as cur:
        cur.execute(
            "SELECT * FROM body_metrics WHERE remote_id BETWEEN ? AND ? ORDER BY remote_id",
            (start, end),
        )
        return [_gym_row("body_metrics", r) for r in cur.fetchall()]


# ---- Gym snapshots (phone-owned) ---------------------------------------------------------------


def replace_supplements(items: list[dict]) -> None:
    with _cursor() as cur:
        cur.execute("DELETE FROM supplements")
        cur.executemany(
            """
            INSERT OR REPLACE INTO supplements (name, kind, dose_amount, dose_unit, kcal_per_dose,
                protein_per_dose, carbs_per_dose, fat_per_dose, doses_per_day, active)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            [
                (
                    s["name"],
                    s["kind"],
                    s["doseAmount"],
                    s["doseUnit"],
                    s["kcalPerDose"],
                    s["proteinPerDose"],
                    s["carbsPerDose"],
                    s["fatPerDose"],
                    s["dosesPerDay"],
                    int(s["active"]),
                )
                for s in items
            ],
        )


def all_supplements() -> list[dict]:
    with _cursor() as cur:
        cur.execute("SELECT * FROM supplements ORDER BY name COLLATE NOCASE")
        return [
            {
                "name": r["name"],
                "kind": r["kind"],
                "doseAmount": r["dose_amount"],
                "doseUnit": r["dose_unit"],
                "kcalPerDose": r["kcal_per_dose"],
                "proteinPerDose": r["protein_per_dose"],
                "carbsPerDose": r["carbs_per_dose"],
                "fatPerDose": r["fat_per_dose"],
                "dosesPerDay": r["doses_per_day"],
                "active": bool(r["active"]),
            }
            for r in cur.fetchall()
        ]


def replace_targets(items: list[dict]) -> None:
    with _cursor() as cur:
        cur.execute("DELETE FROM nutrition_targets")
        cur.executemany(
            "INSERT OR REPLACE INTO nutrition_targets "
            "(effective_from, kcal, protein_g, carbs_g, fat_g, source) VALUES (?, ?, ?, ?, ?, ?)",
            [
                (t["effectiveFrom"], t["kcal"], t["proteinG"], t["carbsG"], t["fatG"], t["source"])
                for t in items
            ],
        )


def target_on(date: str) -> dict | None:
    """The target in force on a date: the latest one that started on or before it."""
    with _cursor() as cur:
        cur.execute(
            "SELECT * FROM nutrition_targets WHERE effective_from <= ? "
            "ORDER BY effective_from DESC LIMIT 1",
            (date,),
        )
        r = cur.fetchone()
        if r is None:
            return None
        return {
            "effectiveFrom": r["effective_from"],
            "kcal": r["kcal"],
            "proteinG": r["protein_g"],
            "carbsG": r["carbs_g"],
            "fatG": r["fat_g"],
            "source": r["source"],
        }
