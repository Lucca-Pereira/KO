"""Talking to the model about one specific recipe.

Two passes per turn, deliberately:

1. **Answer**, streamed, so the phone shows words appearing rather than a spinner for 40 seconds.
2. **Patch**, structured, once the answer is done — only if the user actually asked for a change.

They are separate because they want different things from the model. A good answer is prose; a
good patch is JSON that validates. Asking a 7B for both at once reliably gets you a worse
version of each.

A patch is a whole replacement recipe rather than a list of surgical operations. Small models
rewrite a short recipe reliably; they do not reliably emit `{"op": "replaceIngredient", "id":
42}` against ids they cannot see.
"""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator

from ..config import settings
from ..ollama import OllamaError, stream_chat
from ..ollama import generate as llm
from ..prompts import load
from ..schemas import ChatRequest, RecipePatch, RecipeSnapshot

log = logging.getLogger(__name__)

HISTORY_TURNS = 6
"""How much conversation to resend. Beyond this a 7B starts losing the recipe itself, which
matters more than remembering what was said eight messages ago."""


def render_recipe(recipe: RecipeSnapshot) -> str:
    lines = [f"# {recipe.title}", f"Serves {recipe.servings}", "", "## Ingredients"]
    for ing in recipe.ingredients:
        prefix = f"{ing.amount} " if ing.amount else ""
        suffix = " (optional)" if ing.optional else ""
        lines.append(f"- {prefix}{ing.name}{suffix}")
    if recipe.steps:
        lines += ["", "## Method"]
        lines += [f"{n}. {s.text}" for n, s in enumerate(recipe.steps, start=1)]
    if recipe.notes:
        lines += ["", f"Notes: {recipe.notes}"]
    return "\n".join(lines)


def _system(req: ChatRequest) -> str:
    parts = [load("chat"), "", "The recipe:", render_recipe(req.recipe)]
    have = [e.best_name() for e in req.pantry if e.status != "OUT"]
    if have:
        # Knowing the pantry is what lets it answer "what can I use instead?" usefully.
        parts += ["", f"In their pantry right now: {', '.join(have)}"]
    return "\n".join(parts)


def _history(req: ChatRequest) -> list[dict[str, str]]:
    recent = req.history[-HISTORY_TURNS:]
    return [
        *({"role": m.role, "content": m.content} for m in recent),
        {"role": "user", "content": req.message},
    ]


async def stream_answer(req: ChatRequest) -> AsyncIterator[str]:
    async for piece in stream_chat(
        model=settings().model_chat,
        system=_system(req),
        messages=_history(req),
        temperature=0.6,
    ):
        yield piece


async def propose_patch(req: ChatRequest, answer: str) -> RecipePatch | None:
    """Returns an edit to offer the user, or None if they only asked a question.

    The model decides which it was, because "make it dairy-free" and "what could I use instead
    of cream?" are the same topic and different intents, and a keyword check gets that wrong
    both ways.
    """
    user = "\n".join(
        [
            "The recipe as it stands:",
            render_recipe(req.recipe),
            "",
            f"They said: {req.message}",
            "",
            f"You replied: {answer}",
            "",
            "If that asked for a concrete change, return the full edited recipe and a one-line "
            "summary. If it was only a question, return the recipe exactly as it is with an "
            "empty summary.",
        ]
    )

    try:
        patch = await llm(
            model=settings().model_chat,
            system=load("patch"),
            user=user,
            schema=RecipePatch,
            temperature=0.2,
        )
    except OllamaError as exc:
        log.warning("Patch generation failed: %s", exc)
        return None

    if not patch.summary.strip():
        return None
    if _unchanged(req.recipe, patch):
        # It claimed a change and produced none; offering that as an edit would be noise.
        return None
    return patch


def _unchanged(before: RecipeSnapshot, patch: RecipePatch) -> bool:
    after = patch.recipe

    def ingredients(items) -> list[tuple[str, str]]:
        return [(i.name.strip().lower(), i.amount.strip().lower()) for i in items]

    def steps(items) -> list[str]:
        return [s.text.strip().lower() for s in items]

    return (
        before.title.strip().lower() == after.title.strip().lower()
        and before.servings == after.servings
        and ingredients(before.ingredients) == ingredients(after.ingredients)
        and steps(before.steps) == steps(after.steps)
    )
