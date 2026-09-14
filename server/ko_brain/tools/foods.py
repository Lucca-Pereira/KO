"""Food lookup: barcodes through Open Food Facts, names through the local table."""

from __future__ import annotations

from ..schemas import FoodDto, FoodSearchResponse, FoodSeedResponse
from ..sources import foodtable, openfoodfacts


async def by_barcode(barcode: str) -> FoodDto | None:
    """Open Food Facts first, then the local table in case it is something like a bare egg box.

    A product found with no usable nutrition comes back as None on purpose, so the phone opens
    manual entry rather than logging a zero-calorie yoghurt.
    """
    found = await openfoodfacts.lookup_barcode(barcode)
    return found


async def search(query: str, limit: int = 25) -> FoodSearchResponse:
    return FoodSearchResponse(results=foodtable.search(query, limit))


async def seed(since: int = 0) -> FoodSeedResponse:
    """The whole local table, so the phone can refresh it without a new APK.

    ``since`` lets the phone skip the download when nothing has changed; the table is small
    enough that partial diffs would be more code than they save.
    """
    updated = foodtable.updated_at()
    if since and since >= updated:
        return FoodSeedResponse(updated_at=updated, foods=[])
    return FoodSeedResponse(updated_at=updated, foods=foodtable.all_foods())
