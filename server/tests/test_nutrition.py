"""Nutrition estimation, which is the one place here where being wrong is worse than being
absent: a food diary full of confident guesses is not a food diary.

No model is involved in any of these — that is the point. Everything the table can answer is
answered deterministically, and the tests pin that.
"""

from __future__ import annotations

import pytest

from ko_brain.schemas import EstimateRequest, NutritionIngredient
from ko_brain.sources import foodtable
from ko_brain.tools import nutrition


def ing(name: str, quantity: float | None = None, unit: str | None = None) -> NutritionIngredient:
    return NutritionIngredient(
        name=name,
        normalized_name=foodtable.normalize(name),
        quantity=quantity,
        unit=unit,
        raw=f"{quantity or ''} {unit or ''} {name}".strip(),
    )


class TestFoodTable:
    def test_finds_an_exact_match(self):
        food = foodtable.find("chicken breast")
        assert food is not None
        assert food.kcal_per_100 == 165

    def test_finds_a_more_specific_name(self):
        # "skinless chicken breast" should still land on "chicken breast".
        assert foodtable.find(foodtable.normalize("skinless chicken breast")) is not None

    def test_returns_nothing_for_an_unknown_food(self):
        assert foodtable.find("dragon fruit sorbet") is None

    def test_normalise_folds_accents_and_plurals(self):
        assert foodtable.normalize("Orégano") == "oregano"
        assert foodtable.normalize("Eggs") == "egg"
        assert foodtable.normalize("Chicken Breasts") == "chicken breast"

    @pytest.mark.parametrize(
        ("quantity", "unit", "expected"),
        [(200, "g", 200), (1, "kg", 1000), (2, "tbsp", 30), (1, "cup", 240), (4, "oz", 113.4)],
    )
    def test_converts_known_units_to_grams(self, quantity, unit, expected):
        assert foodtable.grams_for(quantity, unit, None) == pytest.approx(expected, rel=1e-3)

    def test_a_bare_count_needs_a_serving_weight(self):
        egg = foodtable.find("egg")
        assert foodtable.grams_for(2, None, egg) == 100  # 2 x 50 g
        # Without one, refuse: guessing that "2" means 200 g invents calories.
        assert foodtable.grams_for(2, None, None) is None

    def test_search_prefers_a_prefix_match(self):
        names = [f.name for f in foodtable.search("chicken")]
        assert names[0].startswith("chicken")


class TestEstimate:
    async def test_table_only_recipe_needs_no_model(self):
        result = await nutrition.estimate(
            EstimateRequest(
                title="Chicken and rice",
                servings=2,
                ingredients=[ing("chicken breast", 300, "g"), ing("white rice", 200, "g")],
            )
        )
        assert result.coverage == 1.0
        assert all(e.method == "TABLE" for e in result.per_ingredient)
        # 300 g @ 165 + 200 g @ 130 = 495 + 260
        assert result.total.kcal == pytest.approx(755, rel=1e-3)
        assert result.per_serving.kcal == pytest.approx(377.5, rel=1e-3)

    async def test_per_serving_divides_the_total(self):
        result = await nutrition.estimate(
            EstimateRequest(servings=4, ingredients=[ing("white rice", 400, "g")])
        )
        assert result.per_serving.kcal == pytest.approx(result.total.kcal / 4)

    async def test_reports_what_it_could_not_weigh(self, monkeypatch):
        # No model available, so the unmatched ingredient stays unmatched rather than guessed.
        monkeypatch.setattr(nutrition, "_from_model", _no_model)
        result = await nutrition.estimate(
            EstimateRequest(
                servings=1,
                ingredients=[ing("white rice", 100, "g"), ing("dragon fruit sorbet", 1, "cup")],
            )
        )
        assert result.coverage == 0.5
        methods = {e.name: e.method for e in result.per_ingredient}
        assert methods["white rice"] == "TABLE"
        assert methods["dragon fruit sorbet"] == "UNKNOWN"
        assert "1 of 2" in result.note

    async def test_a_known_food_with_no_measurable_amount_is_not_invented(self, monkeypatch):
        monkeypatch.setattr(nutrition, "_from_model", _no_model)
        # The table knows olive oil; "some olive oil" still has no weight.
        result = await nutrition.estimate(
            EstimateRequest(servings=1, ingredients=[ing("olive oil")])
        )
        assert result.total.kcal == 0
        assert result.per_ingredient[0].method == "UNKNOWN"

    async def test_results_follow_the_recipe_order(self, monkeypatch):
        monkeypatch.setattr(nutrition, "_from_model", _no_model)
        result = await nutrition.estimate(
            EstimateRequest(
                servings=1,
                ingredients=[
                    ing("dragon fruit sorbet", 1, "cup"),  # unmatched, resolved second
                    ing("white rice", 100, "g"),  # matched, resolved first
                ],
            )
        )
        assert [e.name for e in result.per_ingredient] == ["dragon fruit sorbet", "white rice"]

    async def test_an_empty_recipe_is_not_an_error(self):
        result = await nutrition.estimate(EstimateRequest(servings=2))
        assert result.coverage == 0.0
        assert result.total.kcal == 0


async def _no_model(unmatched):  # noqa: ARG001 - stands in for an unreachable Ollama
    return {}
