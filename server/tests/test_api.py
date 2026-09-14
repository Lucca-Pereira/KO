"""The HTTP surface: auth, health degradation, and the SSE stream's framing.

Nothing here calls a model. The stream test in particular replaces the model with a canned
generator, because what needs verifying is that the stream always terminates with a `done` or
an `error` — a client left hanging is the one failure the phone cannot recover from.
"""

from __future__ import annotations

import json

import httpx
import pytest
import respx
from fastapi.testclient import TestClient


@pytest.fixture
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("KO_API_TOKEN", "secret")
    monkeypatch.setenv("KO_CACHE_PATH", str(tmp_path / "cache.sqlite3"))
    monkeypatch.setenv("KO_OLLAMA_URL", "http://ollama.test")
    monkeypatch.setenv("KO_WARMUP_SECONDS", "0")

    from ko_brain import cache
    from ko_brain.config import settings

    settings.cache_clear()
    cache._conn = None  # noqa: SLF001
    from ko_brain.main import app

    with TestClient(app) as c:
        yield c
    cache._conn = None  # noqa: SLF001


AUTH = {"Authorization": "Bearer secret"}
TAGS = "http://ollama.test/api/tags"


class TestAuth:
    def test_health_needs_no_token(self, client):
        assert client.get("/health").status_code == 200

    def test_a_v1_route_without_a_token_is_rejected(self, client):
        assert client.get("/v1/foods/search?q=egg").status_code == 401

    def test_a_wrong_token_is_rejected(self, client):
        r = client.get("/v1/foods/search?q=egg", headers={"Authorization": "Bearer nope"})
        assert r.status_code == 401

    def test_a_malformed_header_is_rejected(self, client):
        r = client.get("/v1/foods/search?q=egg", headers={"Authorization": "secret"})
        assert r.status_code == 401

    def test_the_right_token_is_accepted(self, client):
        assert client.get("/v1/foods/search?q=egg", headers=AUTH).status_code == 200


class TestHealth:
    @respx.mock
    def test_reports_ollama_as_unreachable_rather_than_failing(self, client):
        respx.get(TAGS).mock(side_effect=httpx.ConnectError("down"))
        body = client.get("/health").json()
        assert body["ok"] is False
        assert body["ollama"]["reachable"] is False
        # The phone's offline banner keys off this; a 500 here would just look like a crash.

    @respx.mock
    def test_names_configured_models_that_are_not_installed(self, client, monkeypatch):
        respx.get(TAGS).mock(
            return_value=httpx.Response(200, json={"models": [{"name": "llama3.1:8b"}]})
        )
        body = client.get("/health").json()
        assert body["ok"] is True
        assert "llama3.1:8b" in body["ollama"]["models"]
        # Everything configured defaults to qwen2.5, none of which is installed here.
        assert body["ollama"]["missing"]

    @respx.mock
    def test_matches_an_installed_model_ignoring_its_tag(self, client, monkeypatch):
        monkeypatch.setenv("KO_MODEL_FAST", "llama3.1:70b")
        monkeypatch.setenv("KO_MODEL_CHAT", "llama3.1:70b")
        monkeypatch.setenv("KO_MODEL_RECIPE", "llama3.1:70b")
        monkeypatch.setenv("KO_MODEL_NUTRITION", "llama3.1:70b")
        from ko_brain.config import settings

        settings.cache_clear()
        respx.get(TAGS).mock(
            return_value=httpx.Response(200, json={"models": [{"name": "llama3.1:8b"}]})
        )
        assert client.get("/health").json()["ollama"]["missing"] == []


class TestFoods:
    def test_search_reads_the_local_table(self, client):
        results = client.get("/v1/foods/search?q=whey", headers=AUTH).json()["results"]
        assert results
        assert all("whey" in f["name"] for f in results)
        assert all(f["is_supplement"] for f in results)

    def test_seed_returns_the_whole_table(self, client):
        body = client.get("/v1/foods/seed", headers=AUTH).json()
        assert len(body["foods"]) > 100
        assert body["updated_at"] > 0

    def test_seed_skips_the_download_when_nothing_changed(self, client):
        updated = client.get("/v1/foods/seed", headers=AUTH).json()["updated_at"]
        body = client.get(f"/v1/foods/seed?since={updated}", headers=AUTH).json()
        assert body["foods"] == []

    @respx.mock
    def test_an_unknown_barcode_is_a_404(self, client):
        respx.get("https://world.openfoodfacts.org/api/v2/product/9999999999999.json").mock(
            return_value=httpx.Response(200, json={"status": 0})
        )
        assert client.get("/v1/foods/barcode/9999999999999", headers=AUTH).status_code == 404


class TestChatStream:
    def test_the_stream_always_ends_with_done(self, client, monkeypatch):
        from ko_brain.tools import chat as chat_tool

        async def fake_answer(_req):
            for piece in ["Sure, ", "swap the ", "cream."]:
                yield piece

        async def no_patch(_req, _answer):
            return None

        monkeypatch.setattr(chat_tool, "stream_answer", fake_answer)
        monkeypatch.setattr(chat_tool, "propose_patch", no_patch)

        events = _read_stream(client)
        assert [e for e, _ in events if e == "token"]
        assert events[-1][0] == "done"
        assert "".join(d["t"] for e, d in events if e == "token") == "Sure, swap the cream."

    def test_a_model_failure_ends_the_stream_with_an_error(self, client, monkeypatch):
        from ko_brain.ollama import OllamaError
        from ko_brain.tools import chat as chat_tool

        async def failing(_req):
            raise OllamaError("model timeout")
            yield  # pragma: no cover - makes this an async generator

        monkeypatch.setattr(chat_tool, "stream_answer", failing)

        events = _read_stream(client)
        assert events[-1][0] == "error"
        assert "model timeout" in events[-1][1]["message"]

    def test_a_proposal_is_sent_before_done(self, client, monkeypatch):
        from ko_brain.schemas import RecipeDto, RecipePatch
        from ko_brain.tools import chat as chat_tool

        async def answer(_req):
            yield "Done."

        async def patch(_req, _answer):
            return RecipePatch(summary="Swapped the cream", recipe=RecipeDto(title="Pasta"))

        monkeypatch.setattr(chat_tool, "stream_answer", answer)
        monkeypatch.setattr(chat_tool, "propose_patch", patch)

        kinds = [e for e, _ in _read_stream(client)]
        assert kinds.index("proposal") < kinds.index("done")


def _read_stream(client) -> list[tuple[str, dict]]:
    body = {
        "recipe": {
            "title": "Creamy pasta",
            "servings": 2,
            "ingredients": [{"name": "cream", "amount": "150 ml"}],
            "steps": [{"text": "Cook it."}],
        },
        "history": [],
        "message": "make it dairy-free",
    }
    events: list[tuple[str, dict]] = []
    with client.stream("POST", "/v1/recipes/chat", headers=AUTH, json=body) as r:
        assert r.status_code == 200
        pending: str | None = None
        for line in r.iter_lines():
            if line.startswith("event: "):
                pending = line[7:]
            elif line.startswith("data: ") and pending:
                events.append((pending, json.loads(line[6:])))
                pending = None
    return events


class TestChatContext:
    def test_the_recipe_is_rendered_into_the_system_prompt(self):
        from ko_brain.schemas import ChatRequest, RecipeSnapshot
        from ko_brain.tools.chat import _system

        req = ChatRequest(
            recipe=RecipeSnapshot(
                title="Tortilla",
                servings=4,
                ingredients=[{"name": "potato", "amount": "500 g"}],
                steps=[{"text": "Fry the potatoes."}],
            ),
            message="can I use sweet potato?",
            pantry=[{"name": "sweet potato"}],
        )
        system = _system(req)
        assert "Tortilla" in system
        assert "500 g potato" in system
        assert "Fry the potatoes." in system
        # The pantry is what lets it answer "what could I use instead" usefully.
        assert "sweet potato" in system

    def test_history_is_windowed(self):
        from ko_brain.schemas import ChatRequest, RecipeSnapshot
        from ko_brain.tools.chat import HISTORY_TURNS, _history

        req = ChatRequest(
            recipe=RecipeSnapshot(title="X"),
            message="latest",
            history=[
                {"role": "user" if i % 2 == 0 else "assistant", "content": f"msg{i}"}
                for i in range(20)
            ],
        )
        messages = _history(req)
        # Windowed history plus the new message; the recipe itself is resent every turn anyway.
        assert len(messages) == HISTORY_TURNS + 1
        assert messages[-1]["content"] == "latest"
