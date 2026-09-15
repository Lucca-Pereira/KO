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
