import fastmcp
import pytest
from asgi_lifespan import LifespanManager
from fastmcp.server.auth import TokenVerifier
from fastmcp.server.auth.auth import AccessToken
from httpx import ASGITransport, AsyncClient

from ko_sync.config import settings
from ko_sync.google_auth import AllowlistVerifier
from ko_sync.main import app, create_app
from ko_sync.mcp_server import mcp

INITIALIZE = {
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
        "protocolVersion": "2024-11-05",
        "capabilities": {},
        "clientInfo": {"name": "test", "version": "0"},
    },
}
MCP_HEADERS = {"Accept": "application/json, text/event-stream"}


@pytest.fixture
async def client():
    # The MCP mount needs its StreamableHTTPSessionManager task group started via the app's
    # lifespan — ASGITransport alone never runs it, unlike a real uvicorn process.
    async with LifespanManager(app):
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as c:
            yield c


async def test_mcp_mount_rejects_missing_token(client, monkeypatch):
    monkeypatch.setenv("KO_API_TOKEN", "secret")
    settings.cache_clear()

    resp = await client.post("/mcp", json=INITIALIZE, headers=MCP_HEADERS)
    assert resp.status_code == 401


async def test_mcp_mount_rejects_wrong_token(client, monkeypatch):
    monkeypatch.setenv("KO_API_TOKEN", "secret")
    settings.cache_clear()

    resp = await client.post(
        "/mcp", json=INITIALIZE, headers={**MCP_HEADERS, "Authorization": "Bearer wrong"}
    )
    assert resp.status_code == 401


async def test_mcp_mount_accepts_correct_token(client, monkeypatch):
    monkeypatch.setenv("KO_API_TOKEN", "secret")
    settings.cache_clear()

    resp = await client.post(
        "/mcp", json=INITIALIZE, headers={**MCP_HEADERS, "Authorization": "Bearer secret"}
    )
    assert resp.status_code == 200


# ---- Startup refusals ---------------------------------------------------------------------------


def _env(monkeypatch, **values):
    for key, value in values.items():
        monkeypatch.setenv(f"KO_{key.upper()}", value)
    settings.cache_clear()


def test_refuses_public_url_without_token(monkeypatch):
    _env(monkeypatch, public_url="https://nas.example.ts.net", api_token="")
    with pytest.raises(RuntimeError, match="KO_API_TOKEN"):
        create_app()


def test_refuses_google_without_allowlist(monkeypatch):
    _env(
        monkeypatch,
        public_url="https://nas.example.ts.net",
        api_token="secret",
        google_client_id="id.apps.googleusercontent.com",
        google_client_secret="shh",
    )
    with pytest.raises(RuntimeError, match="KO_ALLOWED_EMAILS"):
        create_app()


# ---- Google sign-in mode ------------------------------------------------------------------------


@pytest.fixture
async def google_client(monkeypatch, tmp_path):
    monkeypatch.setattr(fastmcp.settings, "home", tmp_path)  # OAuth state store, off the real disk
    _env(
        monkeypatch,
        public_url="https://nas.example.ts.net",
        api_token="secret",
        google_client_id="id.apps.googleusercontent.com",
        google_client_secret="shh",
        allowed_emails="Me@Example.com",
    )
    google_app = create_app()
    try:
        async with LifespanManager(google_app):
            transport = ASGITransport(app=google_app)
            async with AsyncClient(transport=transport, base_url="https://nas.example.ts.net") as c:
                yield c
    finally:
        mcp.auth = None  # create_app() set it on the shared server object


async def test_google_mode_challenges_with_oauth_metadata(google_client):
    resp = await google_client.post("/mcp", json=INITIALIZE, headers=MCP_HEADERS)
    assert resp.status_code == 401
    assert "resource_metadata" in resp.headers.get("www-authenticate", "")


async def test_google_mode_ignores_the_phone_token_on_mcp(google_client):
    resp = await google_client.post(
        "/mcp", json=INITIALIZE, headers={**MCP_HEADERS, "Authorization": "Bearer secret"}
    )
    assert resp.status_code == 401


async def test_google_mode_serves_discovery_at_the_root(google_client):
    resource = await google_client.get("/.well-known/oauth-protected-resource/mcp")
    assert resource.status_code == 200
    assert resource.json()["resource"].rstrip("/") == "https://nas.example.ts.net/mcp"

    server = await google_client.get("/.well-known/oauth-authorization-server")
    assert server.status_code == 200
    assert server.json()["authorization_endpoint"].startswith("https://nas.example.ts.net/")


async def test_google_mode_keeps_the_phone_endpoint_on_its_token(google_client):
    assert (await google_client.post("/v1/sync", json={})).status_code == 401
    ok = await google_client.post("/v1/sync", json={}, headers={"Authorization": "Bearer secret"})
    assert ok.status_code == 200


# ---- The allowlist itself -----------------------------------------------------------------------


class _FakeGoogle(TokenVerifier):
    def __init__(self, claims):
        super().__init__()
        self.claims = claims

    async def verify_token(self, token):
        if self.claims is None:
            return None
        return AccessToken(token=token, client_id="x", scopes=[], claims=self.claims)


async def test_allowlist_accepts_listed_verified_email():
    verifier = AllowlistVerifier(
        _FakeGoogle({"email": "ME@example.com", "email_verified": "true"}),
        frozenset({"me@example.com"}),
    )
    assert await verifier.verify_token("t") is not None


@pytest.mark.parametrize(
    "claims",
    [
        {"email": "someone@else.com", "email_verified": "true"},
        {"email": "me@example.com", "email_verified": "false"},
        {"email": None, "email_verified": True},
        None,
    ],
)
async def test_allowlist_rejects_everyone_else(claims):
    verifier = AllowlistVerifier(_FakeGoogle(claims), frozenset({"me@example.com"}))
    assert await verifier.verify_token("t") is None
