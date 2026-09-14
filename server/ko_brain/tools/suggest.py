"""Dish suggestions from what is in the pantry."""

from __future__ import annotations

import logging

from ..config import settings
from ..ollama import OllamaError, resolve_model
from ..ollama import generate as llm
from ..prompts import load
from ..schemas import IdeaList, SuggestRequest, SuggestResponse

log = logging.getLogger(__name__)


async def suggest(req: SuggestRequest) -> SuggestResponse:
    cfg = settings()
    have = [e.best_name() for e in req.pantry if e.status != "OUT"]
    running_low = [e.best_name() for e in req.pantry if e.status == "LOW"]

    lines = [
        f"Pantry: {', '.join(have) if have else '(empty — suggest popular easy dishes)'}",
    ]
    if running_low:
        lines.append(f"Running low (use sparingly): {', '.join(running_low)}")
    if req.exclude:
        lines.append(f"Do not suggest: {', '.join(req.exclude[:40])}")
    if req.constraints.strip():
        lines.append(f"Constraints: {req.constraints.strip()}")
    lines.append(f"Suggest {req.count} dishes.")

    try:
        result = await llm(
            model=cfg.model_chat,
            system=load("suggest"),
            user="\n".join(lines),
            schema=IdeaList,
            temperature=0.7,
        )
    except OllamaError as exc:
        log.warning("Suggestion failed: %s", exc)
        return SuggestResponse(ideas=[], note=str(exc))

    ideas = []
    for idea in result.suggestions:
        if not idea.title.strip():
            continue
        # Small models sometimes answer the "search term" field with a whole sentence; the
        # dish name is a better search than a bad query.
        query = idea.query.strip()
        if not query or len(query.split()) > 5:
            query = idea.title.strip()
        ideas.append(idea.model_copy(update={"query": query}))

    return SuggestResponse(
        ideas=ideas[: req.count],
        model=await resolve_model(cfg.model_chat),
        note=None if ideas else "The model didn't return any usable ideas.",
    )
