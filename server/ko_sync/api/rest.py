"""The REST surface the phone talks to: one endpoint, push-then-pull, in a single round trip."""

from __future__ import annotations

from fastapi import APIRouter, Depends

from .. import store
from ..auth import require_token
from ..schemas import Health, SyncRequest, SyncResponse
from ..version import VERSION

router = APIRouter()
secured = APIRouter(prefix="/v1", dependencies=[Depends(require_token)])


@router.get("/health", response_model=Health)
async def health() -> Health:
    """Unauthenticated on purpose: the container healthcheck and the phone's "is the NAS up?"
    banner both poll this, and it leaks nothing but a version string."""
    return Health(ok=True, version=VERSION)


@secured.post("/sync", response_model=SyncResponse)
async def sync(req: SyncRequest) -> SyncResponse:
    for recipe in req.push.recipes:
        store.upsert_recipe(recipe.model_dump())
    for item in req.push.pantryItems:
        store.upsert_pantry_item(item.model_dump())
    for item in req.push.shoppingItems:
        store.insert_shopping_item(item.model_dump())
    for entry in req.push.mealPlanEntries:
        store.insert_plan_entry(entry.model_dump())

    server_time = store.now_millis()
    return SyncResponse(
        serverTime=server_time,
        recipes=store.recipes_changed_since(req.lastSyncedAt),
        pantryItems=store.pantry_changed_since(req.lastSyncedAt),
        shoppingItems=store.shopping_changed_since(req.lastSyncedAt),
        mealPlanEntries=store.plan_changed_since(req.lastSyncedAt),
    )


router.include_router(secured)
