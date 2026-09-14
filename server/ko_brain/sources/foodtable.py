"""The local food table: common whole foods, per 100 g.

This is the deterministic half of nutrition estimation. A 7B model's macro guesses swing by 20%
or more between runs, which makes a calorie tracker decorative; matching against a real table
first and only asking the model about the remainder is what makes the numbers worth logging.

The same JSON ships inside the APK, so the phone can match offline and the two sides agree. The
seed data is derived from USDA FNDDS / SR Legacy, which is public domain — Open Food Facts' own
data is ODbL and would need attribution.
"""

from __future__ import annotations

import json
import unicodedata
from functools import lru_cache
from pathlib import Path

from ..schemas import FoodDto

_DATA = Path(__file__).resolve().parents[2] / "data" / "foods_seed.json"

# Units the table knows how to weigh. Volumes assume water-like density unless the food
# overrides it, which is wrong for oil and honey and close enough for everything else.
_GRAMS_PER_UNIT: dict[str, float] = {
    "g": 1.0,
    "kg": 1000.0,
    "mg": 0.001,
    "ml": 1.0,
    "l": 1000.0,
    "oz": 28.35,
    "lb": 453.6,
    "tsp": 5.0,
    "tbsp": 15.0,
    "cup": 240.0,
    "pint": 473.0,
}


def normalize(name: str) -> str:
    """Mirrors the app's IngredientMatcher closely enough to key on the same string.

    The app sends its own ``normalized_name`` with every request precisely so this does not have
    to be a perfect reimplementation — this is the fallback for when it does not.
    """
    folded = unicodedata.normalize("NFD", name.lower())
    stripped = "".join(c for c in folded if unicodedata.category(c) != "Mn")
    cleaned = "".join(c if c.isalpha() or c.isspace() else " " for c in stripped)
    words = cleaned.split()
    singular = [
        w[:-1] if len(w) > 3 and w.endswith("s") and not w.endswith("ss") else w for w in words
    ]
    return " ".join(singular)


@lru_cache
def _table() -> list[FoodDto]:
    if not _DATA.exists():
        return []
    raw = json.loads(_DATA.read_text(encoding="utf-8"))
    return [FoodDto.model_validate(entry) for entry in raw.get("foods", [])]


@lru_cache
def _index() -> dict[str, FoodDto]:
    out: dict[str, FoodDto] = {}
    for food in _table():
        out.setdefault(normalize(food.name), food)
    return out


def all_foods() -> list[FoodDto]:
    return list(_table())


def updated_at() -> int:
    if not _DATA.exists():
        return 0
    return int(_DATA.stat().st_mtime)


def find(normalized_name: str) -> FoodDto | None:
    """Exact match, then a token-subset match ("chicken breast" for "skinless chicken breast")."""
    key = normalized_name.strip()
    if not key:
        return None

    index = _index()
    if key in index:
        return index[key]

    wanted = set(key.split())
    if not wanted:
        return None

    best: FoodDto | None = None
    best_extra = 99
    for candidate_key, food in index.items():
        tokens = set(candidate_key.split())
        if tokens and tokens <= wanted:
            extra = len(wanted - tokens)
            if extra < best_extra:
                best, best_extra = food, extra
    return best


def search(query: str, limit: int = 25) -> list[FoodDto]:
    needle = normalize(query)
    if not needle:
        return _table()[:limit]
    scored = [
        (0 if normalize(f.name).startswith(needle) else 1, f.name.lower(), f)
        for f in _table()
        if needle in normalize(f.name)
    ]
    scored.sort(key=lambda t: (t[0], t[1]))
    return [f for _, _, f in scored[:limit]]


def grams_for(quantity: float | None, unit: str | None, food: FoodDto | None) -> float | None:
    """Converts an amount to grams, or None when there is nothing to go on.

    A count with no unit ("2 eggs") only works when the food declares a serving weight; guessing
    that an unknown "2" means 200 g would put invented calories in a food diary.
    """
    if quantity is None:
        return None
    key = (unit or "").strip().lower()
    if key in _GRAMS_PER_UNIT:
        return quantity * _GRAMS_PER_UNIT[key]
    countable = not key or key in {"whole", "piece", "large", "medium", "small", "clove", "slice"}
    if countable and food is not None and food.serving_grams:
        return quantity * food.serving_grams
    return None


def macros_for_grams(food: FoodDto, grams: float) -> dict[str, float]:
    factor = grams / 100.0
    return {
        "kcal": round(food.kcal_per_100 * factor, 1),
        "protein_g": round(food.protein_per_100 * factor, 1),
        "carbs_g": round(food.carbs_per_100 * factor, 1),
        "fat_g": round(food.fat_per_100 * factor, 1),
        "fiber_g": round(food.fiber_per_100 * factor, 1)
        if food.fiber_per_100 is not None
        else None,
    }
