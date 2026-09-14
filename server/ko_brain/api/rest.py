"""The REST surface the phone talks to.

Thin on purpose: every route is a few lines over a function in ``tools/``, which is the same
function the MCP endpoint exposes. One implementation, two front doors.
"""

from __future__ import annotations

import json
import logging
from collections.abc import AsyncIterator

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.responses import StreamingResponse

from .. import cache
from ..auth import require_token
from ..config import settings
from ..ollama import OllamaError, gate, list_models
from ..schemas import (
    ChatRequest,
    EstimateRequest,
    EstimateResponse,
    FoodDto,
    FoodSearchResponse,
    FoodSeedResponse,
    GenerateRecipeRequest,
    Health,
    ImportRecipeRequest,
    MealSearchResponse,
    OllamaHealth,
    RecipeDto,
    SuggestRequest,
    SuggestResponse,
    TranslateRequest,
    TranslateResponse,
)
from ..tools import chat as chat_tool
from ..tools import foods as foods_tool
from ..tools import nutrition as nutrition_tool
from ..tools import recipes as recipes_tool
from ..tools import suggest as suggest_tool
from ..tools import translate as translate_tool
from ..version import VERSION

log = logging.getLogger(__name__)

router = APIRouter()
secured = APIRouter(prefix="/v1", dependencies=[Depends(require_token)])


@router.get("/health", response_model=Health)
async def health() -> Health:
    """Unauthenticated on purpose: it is what the container healthcheck and the phone's
    "is the brain up?" banner both poll, and it leaks nothing but model names."""
    cfg = settings()
    configured = {
        "fast": cfg.model_fast,
        "chat": cfg.model_chat,
        "recipe": cfg.model_recipe,
        "nutrition": cfg.model_nutrition,
    }
    try:
        installed = await list_models()
        bases = {m.split(":")[0] for m in installed}
        missing = sorted({m for m in configured.values() if m.split(":")[0] not in bases})
        ollama = OllamaHealth(
            reachable=True, models=installed, configured=configured, missing=missing
        )
    except OllamaError as exc:
        log.debug("Health check could not reach Ollama: %s", exc)
        ollama = OllamaHealth(reachable=False, configured=configured)

    return Health(
        ok=ollama.reachable,
        version=VERSION,
        ollama=ollama,
        busy=gate.busy,
        queue_depth=gate.waiting,
        cache_entries=cache.size(),
    )


@secured.post("/suggest", response_model=SuggestResponse)
async def suggest(req: SuggestRequest) -> SuggestResponse:
    return await suggest_tool.suggest(req)


@secured.post("/translate", response_model=TranslateResponse)
async def translate(req: TranslateRequest) -> TranslateResponse:
    return await translate_tool.translate(req)


@secured.post("/recipes/generate", response_model=RecipeDto)
async def generate_recipe(req: GenerateRecipeRequest) -> RecipeDto:
    try:
        return await recipes_tool.generate_recipe(req)
    except OllamaError as exc:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, str(exc)) from exc


@secured.post("/recipes/import", response_model=RecipeDto)
async def import_recipe(req: ImportRecipeRequest) -> RecipeDto:
    recipe = await recipes_tool.import_recipe(req)
    if recipe is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Nothing to import from that reference.")
    return recipe


@secured.get("/recipes/search", response_model=MealSearchResponse)
async def search_recipes(
    q: str = Query(min_length=1), limit: int = Query(default=20, ge=1, le=50)
) -> MealSearchResponse:
    return await recipes_tool.search_meals(q, limit)


@secured.post("/recipes/for-ideas", response_model=MealSearchResponse)
async def recipes_for_ideas(queries: list[str]) -> MealSearchResponse:
    return await recipes_tool.meals_for_ideas(queries)


@secured.post("/nutrition/estimate", response_model=EstimateResponse)
async def estimate(req: EstimateRequest) -> EstimateResponse:
    return await nutrition_tool.estimate(req)


@secured.get("/foods/barcode/{barcode}", response_model=FoodDto)
async def barcode(barcode: str) -> FoodDto:
    food = await foods_tool.by_barcode(barcode)
    if food is None:
        # Also the answer when the product exists but carries no nutrition, so the phone opens
        # manual entry instead of logging a zero-calorie yoghurt.
        raise HTTPException(status.HTTP_404_NOT_FOUND, "No nutrition data for that barcode.")
    return food


@secured.get("/foods/search", response_model=FoodSearchResponse)
async def search_foods(
    q: str = Query(default=""), limit: int = Query(default=25, ge=1, le=100)
) -> FoodSearchResponse:
    return await foods_tool.search(q, limit)


@secured.get("/foods/seed", response_model=FoodSeedResponse)
async def food_seed(since: int = Query(default=0, ge=0)) -> FoodSeedResponse:
    return await foods_tool.seed(since)


def _sse(event: str, data: dict) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


@secured.post("/recipes/chat")
async def recipe_chat(req: ChatRequest) -> StreamingResponse:
    """Server-sent events: `token`* then optionally `proposal`, then `done` or `error`.

    Streaming matters here more than anywhere else — a 7B on NAS CPU takes tens of seconds for a
    paragraph, and watching it arrive is the difference between "thinking" and "broken".
    """

    async def stream() -> AsyncIterator[str]:
        answer_parts: list[str] = []
        try:
            async for piece in chat_tool.stream_answer(req):
                answer_parts.append(piece)
                yield _sse("token", {"t": piece})

            answer = "".join(answer_parts).strip()
            if answer:
                patch = await chat_tool.propose_patch(req, answer)
                if patch is not None:
                    yield _sse(
                        "proposal",
                        {"summary": patch.summary, "recipe": patch.recipe.model_dump()},
                    )
            yield _sse("done", {"chars": len(answer)})
        except OllamaError as exc:
            yield _sse("error", {"message": str(exc)})
        except Exception as exc:  # noqa: BLE001 - the stream must always terminate cleanly
            log.exception("Chat stream failed")
            yield _sse("error", {"message": f"Something went wrong: {exc}"})

    return StreamingResponse(
        stream(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


router.include_router(secured)
