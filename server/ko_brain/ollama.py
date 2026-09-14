"""Talking to Ollama, reliably.

Three things this file exists to get right, all of which the phone used to get wrong:

1. **Structured output is a schema, not a flag.** ``format: "json"`` only guarantees the reply
   parses as JSON — not that it has your shape. Passing a real JSON Schema and validating with
   Pydantic is what removes the "try three shapes and hope" parsing the app used to carry.
2. **One request at a time.** A 7B on NAS CPU serves one generation; letting a macro estimate
   queue invisibly behind a chat stream just blows the phone's timeout instead.
3. **Cold starts.** After idle Ollama evicts the model and the next call pays 30-90s. A long
   keep-alive plus a periodic one-token ping keeps the first request of the morning usable.
"""

from __future__ import annotations

import asyncio
import json
import logging
from collections.abc import AsyncIterator
from typing import Any

import httpx
from pydantic import BaseModel, ValidationError

from .config import settings

log = logging.getLogger(__name__)


class OllamaError(RuntimeError):
    """Anything that stopped us getting an answer, with a message fit to show a user."""


class OllamaBusy(OllamaError):
    """Another generation is already running."""


class _Gate:
    """A semaphore that can also report how many callers are waiting."""

    def __init__(self) -> None:
        self._sem = asyncio.Semaphore(1)
        self.waiting = 0

    @property
    def busy(self) -> bool:
        return self._sem.locked()

    async def __aenter__(self) -> None:
        self.waiting += 1
        await self._sem.acquire()
        self.waiting -= 1

    async def __aexit__(self, *_: object) -> None:
        self._sem.release()


gate = _Gate()


def _keep_alive() -> int | str:
    """Ollama parses keep_alive as a Go duration ("10m") *or* a number of seconds.

    A bare "-1" is neither: as a string it fails with `missing unit in duration`, and -1 is the
    value that means "keep the model loaded forever", which is exactly what stops the first
    request each morning from paying a cold load. So numeric settings are sent as numbers and
    anything else is passed through as the duration string it presumably is.
    """
    raw = settings().ollama_keep_alive.strip()
    try:
        return int(raw)
    except ValueError:
        return raw


def _client() -> httpx.AsyncClient:
    return httpx.AsyncClient(
        base_url=settings().ollama_url.rstrip("/"),
        timeout=httpx.Timeout(settings().request_timeout, connect=10.0),
    )


async def list_models() -> list[str]:
    try:
        async with _client() as http:
            resp = await http.get("/api/tags")
            resp.raise_for_status()
            return [m["name"] for m in resp.json().get("models", [])]
    except httpx.HTTPError as exc:
        raise OllamaError(f"Could not reach Ollama at {settings().ollama_url}: {exc}") from exc


async def resolve_model(requested: str) -> str:
    """Returns ``requested`` if installed, else the closest thing that is.

    Matching ignores the tag, so a configured ``qwen2.5:7b-instruct`` still resolves when only
    ``qwen2.5:7b`` is pulled. Falls back to the first installed model rather than failing: a
    stale name in the config should degrade, not break the app.
    """
    try:
        installed = await list_models()
    except OllamaError:
        return requested
    if not installed:
        return requested
    if requested in installed:
        return requested
    base = requested.split(":")[0]
    for name in installed:
        if name.split(":")[0] == base:
            return name
    log.warning("Model %s not installed; falling back to %s", requested, installed[0])
    return installed[0]


async def generate[T: BaseModel](
    *,
    model: str,
    system: str,
    user: str,
    schema: type[T],
    temperature: float = 0.4,
    retries: int = 1,
) -> T:
    """One structured generation, validated into ``schema``.

    On a validation failure the model is asked again with its own broken output and the
    validation error appended — which fixes far more cases than a blind retry, because small
    models usually miss one required field rather than produce nonsense.
    """
    resolved = await resolve_model(model)
    json_schema = schema.model_json_schema()
    messages = [
        {"role": "system", "content": system},
        {"role": "user", "content": user},
    ]

    last_error: Exception | None = None
    for attempt in range(retries + 1):
        content = await _chat(resolved, messages, json_schema, temperature)
        try:
            return schema.model_validate_json(content)
        except ValidationError as exc:
            last_error = exc
            log.warning(
                "Validation failed (attempt %d) for %s: %s", attempt + 1, schema.__name__, exc
            )
            if attempt == retries:
                break
            messages = [
                *messages,
                {"role": "assistant", "content": content},
                {
                    "role": "user",
                    "content": (
                        "That reply did not match the required schema. Fix it and reply with "
                        f"corrected JSON only.\n\nErrors:\n{exc}"
                    ),
                },
            ]

    raise OllamaError(f"The model could not produce valid {schema.__name__}: {last_error}")


async def _chat(
    model: str,
    messages: list[dict[str, str]],
    json_schema: dict[str, Any] | None,
    temperature: float,
) -> str:
    payload: dict[str, Any] = {
        "model": model,
        "messages": messages,
        "stream": False,
        "keep_alive": _keep_alive(),
        "options": {"temperature": temperature},
    }
    if json_schema is not None:
        payload["format"] = json_schema

    async with gate:
        try:
            async with _client() as http:
                resp = await http.post("/api/chat", json=payload)
                resp.raise_for_status()
                return resp.json().get("message", {}).get("content", "")
        except httpx.HTTPStatusError as exc:
            raise OllamaError(_http_message(exc)) from exc
        except httpx.HTTPError as exc:
            raise OllamaError(f"Could not reach Ollama: {exc}") from exc


def _http_message(exc: httpx.HTTPStatusError) -> str:
    """Ollama puts the actual complaint in the body ({"error": "..."}); a bare status code
    sends you hunting for something it already told you."""
    detail = ""
    try:
        detail = exc.response.json().get("error", "")
    except (ValueError, AttributeError):
        detail = (exc.response.text or "").strip()[:200]
    status = exc.response.status_code
    return f"Ollama returned HTTP {status}" + (f": {detail}" if detail else "")


async def stream_chat(
    *,
    model: str,
    system: str,
    messages: list[dict[str, str]],
    temperature: float = 0.6,
) -> AsyncIterator[str]:
    """Yields answer text as it is produced. No schema — this is the prose half of a chat turn."""
    resolved = await resolve_model(model)
    payload = {
        "model": resolved,
        "messages": [{"role": "system", "content": system}, *messages],
        "stream": True,
        "keep_alive": _keep_alive(),
        "options": {"temperature": temperature},
    }

    async with gate:
        try:
            async with _client() as http, http.stream("POST", "/api/chat", json=payload) as resp:
                resp.raise_for_status()
                async for line in resp.aiter_lines():
                    if not line.strip():
                        continue
                    try:
                        chunk = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    piece = chunk.get("message", {}).get("content", "")
                    if piece:
                        yield piece
                    if chunk.get("done"):
                        return
        except httpx.HTTPStatusError as exc:
            raise OllamaError(_http_message(exc)) from exc
        except httpx.HTTPError as exc:
            raise OllamaError(f"Could not reach Ollama: {exc}") from exc


async def warm_up() -> None:
    """One cheap token to keep the chat model resident."""
    cfg = settings()
    if gate.busy:
        return  # Real work is in flight; it is keeping the model warm by itself.
    try:
        model = await resolve_model(cfg.model_chat)
        async with gate, _client() as http:
            await http.post(
                "/api/chat",
                json={
                    "model": model,
                    "messages": [{"role": "user", "content": "hi"}],
                    "stream": False,
                    "keep_alive": _keep_alive(),
                    "options": {"num_predict": 1},
                },
            )
        log.debug("Warmed %s", model)
    except (httpx.HTTPError, OllamaError) as exc:
        log.debug("Warm-up skipped: %s", exc)


async def warm_up_loop() -> None:
    interval = settings().warmup_seconds
    if interval <= 0:
        return
    while True:
        await warm_up()
        await asyncio.sleep(interval)
