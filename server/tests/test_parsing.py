"""Ingredient-line parsing.

This exists so the MCP path can reach the deterministic food table. Every case that fails here
silently downgrades a looked-up number into a guessed one.
"""

from __future__ import annotations

import pytest

from ko_brain.parsing import parse_line


@pytest.mark.parametrize(
    ("line", "quantity", "unit", "name"),
    [
        ("200 g chicken breast", 200, "g", "chicken breast"),
        ("200g chicken breast", 200, "g", "chicken breast"),
        ("1 tbsp olive oil", 1, "tbsp", "olive oil"),
        ("2 tablespoons olive oil", 2, "tbsp", "olive oil"),
        ("1 1/2 cups white rice", 1.5, "cup", "white rice"),
        ("1 ½ cups white rice", 1.5, "cup", "white rice"),
        ("½ tsp salt", 0.5, "tsp", "salt"),
        ("3/4 cup milk", 0.75, "cup", "milk"),
        ("1-2 tbsp soy sauce", 1, "tbsp", "soy sauce"),
        ("2 cloves of garlic", 2, "clove", "garlic"),
        ("1 large onion", 1, "large", "onion"),
        ("1,5 l water", 1.5, "l", "water"),
        ("2 eggs", 2, None, "eggs"),
        ("salt", None, None, "salt"),
    ],
)
def test_parses_the_shapes_recipes_actually_use(line, quantity, unit, name):
    q, u, n = parse_line(line)
    assert q == pytest.approx(quantity) if quantity is not None else q is None
    assert u == unit
    assert n == name


def test_an_unknown_unit_stays_part_of_the_name():
    # Better to leave "glugs" in the name than to drop it and pretend the amount was understood.
    quantity, unit, name = parse_line("3 glugs of oil")
    assert quantity == 3
    assert unit is None
    assert "glugs" in name


def test_an_empty_line_yields_nothing():
    assert parse_line("   ") == (None, None, "")


def test_a_range_takes_the_low_end():
    # Under-buying is recoverable; over-counting calories is not what a diary is for.
    assert parse_line("2 to 3 cloves garlic")[0] == 2
