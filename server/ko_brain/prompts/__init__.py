"""Prompts live on disk as Markdown, not as string constants.

The whole reason the AI moved off the phone is that changing a prompt should not mean building
and sideloading an APK. Keeping them as files means a change is an edit and a restart.

They are re-read per request when ``KO_RELOAD_PROMPTS`` is set, so tuning is edit-and-retry
rather than edit-and-restart; in normal running they are cached.
"""

from __future__ import annotations

import os
from functools import lru_cache
from pathlib import Path

_DIR = Path(__file__).parent


@lru_cache
def _cached(name: str) -> str:
    return (_DIR / f"{name}.md").read_text(encoding="utf-8").strip()


def load(name: str) -> str:
    """Returns the prompt body for ``name`` (without the .md)."""
    if os.getenv("KO_RELOAD_PROMPTS"):
        return (_DIR / f"{name}.md").read_text(encoding="utf-8").strip()
    return _cached(name)
