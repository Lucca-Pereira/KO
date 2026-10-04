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
    for entry in req.push.nutritionEntries:
        store.upsert_gym("nutrition_entries", entry.model_dump())
    for log in req.push.supplementLogs:
        store.upsert_gym("supplement_logs", log.model_dump())
    for metric in req.push.bodyMetrics:
        store.upsert_gym("body_metrics", metric.model_dump())

    if req.push.planView is not None:
        store.upsert_plan_view(req.push.planView.model_dump())

    if req.supplements is not None:
        store.replace_supplements([s.model_dump() for s in req.supplements])
    if req.targets is not None:
        store.replace_targets([t.model_dump() for t in req.targets])

    present = req.present
    if present is not None:
        since = req.lastSyncedAt
        store.prune_absent("recipes", present.recipes, since)
        store.prune_absent("pantry_items", present.pantryItems, since)
        store.prune_absent("shopping_items", present.shoppingItems, since)
        store.prune_absent("meal_plan_entries", present.mealPlanEntries, since)
        if present.nutritionEntries is not None:
            store.prune_absent("nutrition_entries", present.nutritionEntries, since)
        if present.supplementLogs is not None:
            store.prune_absent("supplement_logs", present.supplementLogs, since)
        if present.bodyMetrics is not None:
            store.prune_absent("body_metrics", present.bodyMetrics, since)

    server_time = store.now_millis()
    view = store.get_plan_view()
    return SyncResponse(
        serverTime=server_time,
        recipes=store.recipes_changed_since(req.lastSyncedAt),
        pantryItems=store.pantry_changed_since(req.lastSyncedAt),
        shoppingItems=store.shopping_changed_since(req.lastSyncedAt),
        mealPlanEntries=store.plan_changed_since(req.lastSyncedAt),
        nutritionEntries=store.gym_changed_since("nutrition_entries", req.lastSyncedAt),
        supplementLogs=store.gym_changed_since("supplement_logs", req.lastSyncedAt),
        bodyMetrics=store.gym_changed_since("body_metrics", req.lastSyncedAt),
        planView=view if view["updatedAt"] > req.lastSyncedAt else None,
        deleted=store.deleted_since(req.lastSyncedAt),
    )


router.include_router(secured)
