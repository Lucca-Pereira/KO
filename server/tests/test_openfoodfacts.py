"""Open Food Facts mapping.

Every case here is a real shape the API returns, and each one used to be a way to put a wrong
number in someone's food diary.
"""

from __future__ import annotations

import httpx
import pytest
import respx

from ko_brain import cache
from ko_brain.sources import openfoodfacts


@pytest.fixture(autouse=True)
def _isolated_cache(tmp_path, monkeypatch):
    monkeypatch.setenv("KO_CACHE_PATH", str(tmp_path / "cache.sqlite3"))
    from ko_brain.config import settings

    settings.cache_clear()
    cache._conn = None  # noqa: SLF001 - the module owns one connection per process
    yield
    cache._conn = None  # noqa: SLF001


def _product(**nutriments) -> dict:
    return {
        "status": 1,
        "product": {
            "code": "1234567890123",
            "product_name": "Test yoghurt",
            "brands": "Brandy, Other Brand",
            "serving_size": "125 g",
            "serving_quantity": "125",
            "nutriments": nutriments,
        },
    }


URL = "https://world.openfoodfacts.org/api/v2/product/1234567890123.json"


class TestBarcodeLookup:
    @respx.mock
    async def test_maps_a_normal_product(self):
        respx.get(URL).mock(
            return_value=httpx.Response(
                200,
                json=_product(
                    **{
                        "energy-kcal_100g": 61,
                        "proteins_100g": 3.5,
                        "carbohydrates_100g": 4.7,
                        "fat_100g": 3.3,
                        "fiber_100g": 0,
                    }
                ),
            )
        )
        food = await openfoodfacts.lookup_barcode("1234567890123")
        assert food is not None
        assert food.name == "Test yoghurt"
        assert food.brand == "Brandy"  # first brand only
        assert food.kcal_per_100 == 61
        assert food.serving_grams == 125
        assert food.source == "OFF"

    @respx.mock
    async def test_converts_kilojoules_when_kcal_is_missing(self):
        respx.get(URL).mock(
            return_value=httpx.Response(
                200, json=_product(**{"energy_100g": 1000, "proteins_100g": 3.5})
            )
        )
        food = await openfoodfacts.lookup_barcode("1234567890123")
        assert food is not None
        assert food.kcal_per_100 == pytest.approx(239.0, abs=0.5)

    @respx.mock
    async def test_accepts_numbers_written_as_strings(self):
        respx.get(URL).mock(
            return_value=httpx.Response(
                200,
                json=_product(**{"energy-kcal_100g": "61", "proteins_100g": "3,5", "fat_100g": ""}),
            )
        )
        food = await openfoodfacts.lookup_barcode("1234567890123")
        assert food is not None
        assert food.kcal_per_100 == 61
        assert food.protein_per_100 == 3.5  # European decimal comma
        assert food.fat_per_100 == 0.0  # empty string means absent, not zero-and-known

    @respx.mock
    async def test_a_product_with_no_macros_counts_as_not_found(self):
        # Real and common: the contributor filled in per-serving values only. Returning zeroes
        # would log a zero-calorie yoghurt; returning nothing sends the phone to manual entry.
        respx.get(URL).mock(return_value=httpx.Response(200, json=_product()))
        assert await openfoodfacts.lookup_barcode("1234567890123") is None

    @respx.mock
    async def test_status_zero_is_not_found_despite_http_200(self):
        respx.get(URL).mock(return_value=httpx.Response(200, json={"status": 0}))
        assert await openfoodfacts.lookup_barcode("1234567890123") is None

    @respx.mock
    async def test_a_network_failure_is_not_an_exception(self):
        respx.get(URL).mock(side_effect=httpx.ConnectError("down"))
        assert await openfoodfacts.lookup_barcode("1234567890123") is None

    async def test_rejects_a_non_numeric_barcode_without_a_request(self):
        assert await openfoodfacts.lookup_barcode("not-a-barcode") is None

    @respx.mock
    async def test_the_second_lookup_is_served_from_cache(self):
        route = respx.get(URL).mock(
            return_value=httpx.Response(
                200, json=_product(**{"energy-kcal_100g": 61, "proteins_100g": 3.5})
            )
        )
        first = await openfoodfacts.lookup_barcode("1234567890123")
        second = await openfoodfacts.lookup_barcode("1234567890123")
        assert route.call_count == 1
        assert first == second

    @respx.mock
    async def test_converts_sodium_grams_to_milligrams(self):
        respx.get(URL).mock(
            return_value=httpx.Response(
                200, json=_product(**{"energy-kcal_100g": 61, "sodium_100g": 0.4})
            )
        )
        food = await openfoodfacts.lookup_barcode("1234567890123")
        assert food is not None
        assert food.sodium_mg_per_100 == 400.0
