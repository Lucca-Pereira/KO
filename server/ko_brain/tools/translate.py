"""Translating grocery names into English, so a Spanish pantry can search an English recipe
database.

Batched, because a long list pushes a small model past its useful attention span and it starts
dropping entries — and because a failure then costs one batch rather than the whole pantry.
"""

from __future__ import annotations

import logging

from ..config import settings
from ..ollama import OllamaError, resolve_model
from ..ollama import generate as llm
from ..prompts import load
from ..schemas import TranslateRequest, TranslateResponse, TranslationMap

log = logging.getLogger(__name__)

BATCH_SIZE = 20


async def translate(req: TranslateRequest) -> TranslateResponse:
    cfg = settings()
    names = [n.strip() for n in req.names if n.strip()]
    if not names:
        return TranslateResponse()

    system = load("translate")
    out: dict[str, str] = {}

    # Sequential, not gathered: Ollama serves one generation at a time anyway, so firing them
    # concurrently would only queue them behind each other with extra bookkeeping.
    for start in range(0, len(names), BATCH_SIZE):
        batch = names[start : start + BATCH_SIZE]
        try:
            result = await llm(
                model=cfg.model_fast,
                system=system,
                user="Translate these into English:\n" + "\n".join(batch),
                schema=TranslationMap,
                temperature=0.0,
            )
        except OllamaError as exc:
            log.warning("Translation batch failed: %s", exc)
            continue

        for original, english in result.translations.items():
            clean = english.strip().lower()
            if clean:
                out[original.strip()] = clean

    return TranslateResponse(translations=out, model=await resolve_model(cfg.model_fast))
