"""Splitting an ingredient line like "200 g chicken breast" into an amount and a name.

The phone never needs this — it has its own ``MeasureParser`` in Kotlin and sends quantity, unit
and name as separate fields. Claude, calling the MCP tools, sends whole lines. Without this the
MCP path skips the deterministic food table entirely and asks the model to guess every single
ingredient, which is exactly the behaviour the table-first design exists to avoid.

Kept intentionally close to the Kotlin parser's rules so both sides agree on what "1 1/2 cups"
means.
"""

from __future__ import annotations

import re

VULGAR_FRACTIONS = {
    "¼": 0.25, "½": 0.5, "¾": 0.75,
    "⅓": 1 / 3, "⅔": 2 / 3,
    "⅕": 0.2, "⅖": 0.4, "⅗": 0.6, "⅘": 0.8,
    "⅙": 1 / 6, "⅚": 5 / 6,
    "⅛": 0.125, "⅜": 0.375, "⅝": 0.625, "⅞": 0.875,
}  # fmt: skip

UNIT_ALIASES = {
    "g": "g", "gr": "g", "gram": "g", "grams": "g", "gramme": "g", "grammes": "g",
    "kg": "kg", "kilo": "kg", "kilos": "kg", "kilogram": "kg", "kilograms": "kg",
    "mg": "mg",
    "ml": "ml", "millilitre": "ml", "millilitres": "ml", "milliliter": "ml", "milliliters": "ml",
    "l": "l", "litre": "l", "litres": "l", "liter": "l", "liters": "l",
    "oz": "oz", "ounce": "oz", "ounces": "oz",
    "lb": "lb", "lbs": "lb", "pound": "lb", "pounds": "lb",
    "tsp": "tsp", "teaspoon": "tsp", "teaspoons": "tsp",
    "tbsp": "tbsp", "tbs": "tbsp", "tablespoon": "tbsp", "tablespoons": "tbsp",
    "cup": "cup", "cups": "cup",
    "clove": "clove", "cloves": "clove",
    "slice": "slice", "slices": "slice",
    "scoop": "scoop", "scoops": "scoop",
    "handful": "handful", "pinch": "pinch", "dash": "dash",
    "large": "large", "medium": "medium", "small": "small", "whole": "whole",
    "piece": "piece", "pieces": "piece",
}  # fmt: skip

_FRACTION_CHARS = "".join(VULGAR_FRACTIONS)
_LEADING_AMOUNT = re.compile(
    rf"""^\s*
    (?:
        (?P<whole>\d+)\s*(?P<vulgar>[{_FRACTION_CHARS}])   # 1½
      | (?P<vulgar_only>[{_FRACTION_CHARS}])               # ½
      | (?P<w>\d+)\s+(?P<n>\d+)\s*/\s*(?P<d>\d+)           # 1 1/2
      | (?P<n2>\d+)\s*/\s*(?P<d2>\d+)                      # 3/4
      | (?P<lo>\d+(?:[.,]\d+)?)\s*(?:-|–|to)\s*\d+(?:[.,]\d+)?   # 1-2, takes the low end
      | (?P<plain>\d+(?:[.,]\d+)?)                         # 200, 1.5
    )
    \s*""",
    re.VERBOSE,
)


def parse_line(line: str) -> tuple[float | None, str | None, str]:
    """Returns ``(quantity, unit, name)`` for one ingredient line.

    The name is whatever is left after the amount and unit are taken off the front, so an
    unrecognised unit stays part of the name rather than being silently dropped.
    """
    text = line.strip()
    if not text:
        return None, None, ""

    quantity: float | None = None
    match = _LEADING_AMOUNT.match(text)
    if match:
        quantity = _quantity_from(match)
        text = text[match.end() :].strip()

    unit: str | None = None
    parts = text.split(maxsplit=1)
    if parts:
        candidate = parts[0].lower().strip(".,")
        if candidate in UNIT_ALIASES:
            unit = UNIT_ALIASES[candidate]
            text = parts[1].strip() if len(parts) > 1 else ""
        elif quantity is not None:
            # "200g chicken" — the unit is glued to the number, so it survived into the name.
            glued = re.match(r"^([a-z]+)(.*)$", parts[0].lower())
            if glued and glued.group(1) in UNIT_ALIASES and not glued.group(2):
                unit = UNIT_ALIASES[glued.group(1)]
                text = parts[1].strip() if len(parts) > 1 else ""

    # "of" survives the amount often enough to be worth removing: "2 cloves of garlic".
    if text.lower().startswith("of "):
        text = text[3:].strip()

    return quantity, unit, text


def _quantity_from(match: re.Match[str]) -> float | None:
    g = match.groupdict()
    if g.get("vulgar"):
        return float(g["whole"] or 0) + VULGAR_FRACTIONS[g["vulgar"]]
    if g.get("vulgar_only"):
        return VULGAR_FRACTIONS[g["vulgar_only"]]
    if g.get("w"):
        denominator = float(g["d"])
        return float(g["w"]) + float(g["n"]) / denominator if denominator else None
    if g.get("n2"):
        denominator = float(g["d2"])
        return float(g["n2"]) / denominator if denominator else None
    if g.get("lo"):
        return float(g["lo"].replace(",", "."))
    if g.get("plain"):
        return float(g["plain"].replace(",", "."))
    return None
