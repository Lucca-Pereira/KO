"""Open Food Facts barcode lookup.

The API is real-world messy in four specific ways, all handled here rather than on the phone:

* ``status: 0`` means not found. It arrives as HTTP 200.
* ``energy-kcal_100g`` is often missing while ``energy_100g`` (kilojoules) is present.
* ``nutrition_data_per`` can be ``"serving"``, in which case the ``_100g`` keys may not exist
  at all — the product is real but has no usable nutrition.
* Numbers arrive as numbers or as strings, inconsistently, field by field.

A product with no macros is reported as *not found* rather than as zeroes, so the phone opens
manual entry instead of quietly logging a zero-calorie yoghurt.
"""

from __future__ import annotations

import logging
from typing import Any

import httpx

from .. import cache
from ..config import settings
from ..schemas import FoodDto

log = logging.getLogger(__name__)

_FIELDS = ",".join(
    [
        "code",
        "product_name",
        "product_name_en",
        "generic_name",
        "brands",
        "quantity",
        "serving_size",
        "serving_quantity",
        "nutriments",
        "nutrition_data_per",
        "image_front_small_url",
        "categories_tags",
    ]
)

KJ_PER_KCAL = 4.184


def _num(value: Any) -> float | None:
    """OFF mixes numbers and numeric strings freely; empty strings mean absent."""
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, int | float):
        return float(value)
    text = str(value).strip().replace(",", ".")
    if not text:
        return None
    try:
        return float(text)
    except ValueError:
        return None


def _client() -> httpx.AsyncClient:
    return httpx.AsyncClient(
        base_url=settings().openfoodfacts_url,
        timeout=httpx.Timeout(12.0, connect=5.0),
        headers={"User-Agent": settings().user_agent},
    )


async def lookup_barcode(barcode: str) -> FoodDto | None:
    code = barcode.strip()
    if not code.isdigit():
        return None

    cache_key = f"off:{code}"
    cached = cache.get(cache_key)
    if cached is not None:
        return FoodDto.model_validate(cached) if cached else None

    try:
        async with _client() as http:
            resp = await http.get(f"/api/v2/product/{code}.json", params={"fields": _FIELDS})
            resp.raise_for_status()
            payload = resp.json()
    except (httpx.HTTPError, ValueError) as exc:
        log.warning("Open Food Facts lookup for %s failed: %s", code, exc)
        return None

    if payload.get("status") == 0 or "product" not in payload:
        # Cache the miss briefly: a barcode OFF does not know today it will not know in an hour,
        # but it might next month, so this is a short TTL rather than the default week.
        cache.put(cache_key, None, ttl_seconds=60 * 60)
        return None

    food = _to_food(payload["product"], code)
    cache.put(cache_key, food.model_dump() if food else None)
    return food


def _to_food(product: dict[str, Any], code: str) -> FoodDto | None:
    nutriments: dict[str, Any] = product.get("nutriments") or {}

    kcal = _num(nutriments.get("energy-kcal_100g"))
    if kcal is None:
        kj = _num(nutriments.get("energy_100g")) or _num(nutriments.get("energy-kj_100g"))
        if kj is not None:
            kcal = round(kj / KJ_PER_KCAL, 1)

    protein = _num(nutriments.get("proteins_100g"))
    carbs = _num(nutriments.get("carbohydrates_100g"))
    fat = _num(nutriments.get("fat_100g"))

    # Found the product, but it carries no usable nutrition — usually because the contributor
    # entered per-serving values only. Treat as a miss so the phone prefills manual entry.
    if kcal is None and protein is None and carbs is None and fat is None:
        return None

    name = (
        product.get("product_name")
        or product.get("product_name_en")
        or product.get("generic_name")
        or ""
    ).strip()
    if not name:
        name = f"Product {code}"

    brands = (product.get("brands") or "").split(",")
    brand = brands[0].strip() if brands and brands[0].strip() else None

    return FoodDto(
        barcode=code,
        name=name,
        brand=brand,
        source="OFF",
        serving_label=(product.get("serving_size") or "").strip() or None,
        serving_grams=_num(product.get("serving_quantity")),
        kcal_per_100=kcal or 0.0,
        protein_per_100=protein or 0.0,
        carbs_per_100=carbs or 0.0,
        fat_per_100=fat or 0.0,
        fiber_per_100=_num(nutriments.get("fiber_100g")),
        sugar_per_100=_num(nutriments.get("sugars_100g")),
        sat_fat_per_100=_num(nutriments.get("saturated-fat_100g")),
        sodium_mg_per_100=(lambda g: round(g * 1000, 1) if g is not None else None)(
            _num(nutriments.get("sodium_100g"))
        ),
        image_url=(product.get("image_front_small_url") or "").strip() or None,
    )
