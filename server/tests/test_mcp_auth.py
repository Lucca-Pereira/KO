import pytest
from asgi_lifespan import LifespanManager
from httpx import ASGITransport, AsyncClient

from ko_sync.config import settings
from ko_sync.main import app


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

    resp = await client.post(
        "/mcp/",
        json={"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}},
        headers={"Accept": "application/json, text/event-stream"},
    )
    assert resp.status_code == 401


async def test_mcp_mount_rejects_wrong_token(client, monkeypatch):
    monkeypatch.setenv("KO_API_TOKEN", "secret")
    settings.cache_clear()

    resp = await client.post(
        "/mcp/",
        json={"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}},
        headers={
            "Accept": "application/json, text/event-stream",
            "Authorization": "Bearer wrong",
        },
    )
    assert resp.status_code == 401


async def test_mcp_mount_accepts_correct_token(client, monkeypatch):
    monkeypatch.setenv("KO_API_TOKEN", "secret")
    settings.cache_clear()

    resp = await client.post(
        "/mcp/",
        json={
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {"name": "test", "version": "0"},
            },
        },
        headers={
            "Accept": "application/json, text/event-stream",
            "Authorization": "Bearer secret",
        },
    )
    assert resp.status_code == 200
