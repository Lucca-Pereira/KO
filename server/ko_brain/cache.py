"""A small SQLite TTL cache for upstream lookups.

The NAS is stateless apart from this. Nothing in here is a source of truth — losing the file
costs one round trip to TheMealDB or Open Food Facts, nothing more. That constraint is what
keeps the phone the only place your data actually lives.
"""

from __future__ import annotations

import json
import sqlite3
import threading
import time
from pathlib import Path
from typing import Any

from .config import settings

_lock = threading.Lock()
_conn: sqlite3.Connection | None = None


def _connection() -> sqlite3.Connection:
    global _conn
    if _conn is None:
        path = Path(settings().cache_path)
        path.parent.mkdir(parents=True, exist_ok=True)
        _conn = sqlite3.connect(path, check_same_thread=False)
        _conn.execute(
            "CREATE TABLE IF NOT EXISTS cache ("
            "  key TEXT PRIMARY KEY,"
            "  value TEXT NOT NULL,"
            "  expires_at REAL NOT NULL"
            ")"
        )
        _conn.commit()
    return _conn


def get(key: str) -> Any | None:
    with _lock:
        row = (
            _connection()
            .execute("SELECT value, expires_at FROM cache WHERE key = ?", (key,))
            .fetchone()
        )
    if row is None:
        return None
    value, expires_at = row
    if expires_at < time.time():
        delete(key)
        return None
    try:
        return json.loads(value)
    except json.JSONDecodeError:
        delete(key)
        return None


def put(key: str, value: Any, ttl_seconds: int | None = None) -> None:
    ttl = ttl_seconds if ttl_seconds is not None else settings().cache_ttl_seconds
    with _lock:
        conn = _connection()
        conn.execute(
            "INSERT INTO cache (key, value, expires_at) VALUES (?, ?, ?) "
            "ON CONFLICT(key) DO UPDATE SET "
            "  value = excluded.value, expires_at = excluded.expires_at",
            (key, json.dumps(value), time.time() + ttl),
        )
        conn.commit()


def delete(key: str) -> None:
    with _lock:
        conn = _connection()
        conn.execute("DELETE FROM cache WHERE key = ?", (key,))
        conn.commit()


def size() -> int:
    with _lock:
        try:
            return _connection().execute("SELECT COUNT(*) FROM cache").fetchone()[0]
        except sqlite3.Error:
            return 0


def purge_expired() -> int:
    with _lock:
        conn = _connection()
        cursor = conn.execute("DELETE FROM cache WHERE expires_at < ?", (time.time(),))
        conn.commit()
        return cursor.rowcount
