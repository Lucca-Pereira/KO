"""The ASGI app: REST for the phone at ``/v1``, MCP for Claude at ``/mcp``.

One process, one store, two front doors, each with its own lock:

- ``/v1`` always takes the shared bearer token (the phone sends it; fine over HTTPS).
- ``/mcp`` takes Google sign-in when ``KO_GOOGLE_CLIENT_ID`` is set — that's what claude.ai
  custom connectors need, and it covers the Claude phone/desktop apps too. Without it, ``/mcp``
  falls back to the same bearer token, for a tailnet-only Claude Desktop setup via ``mcp-remote``.

The MCP app is mounted at the root (its endpoint is ``/mcp``) rather than under ``/mcp``, so the
OAuth routes it adds — ``/.well-known/*``, ``/authorize``, ``/token``, ``/register``,
``/auth/callback`` — live at the root where OAuth clients look for them.
"""

from __future__ import annotations

import hmac
import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send

from .api.rest import router
from .config import settings
from .mcp_server import mcp
from .version import VERSION

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
)
log = logging.getLogger("ko_sync")


class RequireTokenAsgi:
    """The same bearer check `auth.require_token` does for `/v1`, applied to the MCP app when
    Google sign-in is off.

    FastMCP's app isn't a FastAPI router, so it can't take a `Depends` — and the tools write data,
    so leaving the mount unauthenticated would make the Tailscale bind the *only* lock.
    """

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        cfg = settings()
        if cfg.auth_enabled:
            headers = dict(scope.get("headers") or [])
            supplied = headers.get(b"authorization", b"").decode("utf-8", "ignore")
            token = supplied[7:].strip() if supplied.lower().startswith("bearer ") else ""
            if not hmac.compare_digest(token, cfg.api_token.strip()):
                response = JSONResponse(
                    {"detail": "Missing or invalid bearer token."}, status_code=401
                )
                await response(scope, receive, send)
                return

        await self.app(scope, receive, send)


def create_app() -> FastAPI:
    cfg = settings()
    problems = cfg.problems()
    if problems:
        raise RuntimeError("Refusing to start: " + " ".join(problems))

    if cfg.google_enabled:
        from .google_auth import google_provider

        mcp.auth = google_provider(cfg)
    else:
        mcp.auth = None

    # FastMCP builds its own ASGI app with its own lifespan (session manager, transports). That
    # lifespan has to run, so it is chained into ours rather than replaced.
    mcp_app = mcp.http_app(path="/mcp")
    mounted: ASGIApp = mcp_app if cfg.google_enabled else RequireTokenAsgi(mcp_app)

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        log.info("KO sync %s starting on %s:%s", VERSION, cfg.host, cfg.port)
        log.info(
            "MCP auth: %s",
            f"Google sign-in for {sorted(cfg.allowed_email_set)}"
            if cfg.google_enabled
            else "bearer token",
        )
        if not cfg.auth_enabled:
            log.warning(
                "KO_API_TOKEN is not set — every request is accepted. Fine on a Tailscale-only "
                "bind, not fine on anything reachable from the LAN."
            )
        if cfg.host in {"0.0.0.0", "::"}:  # noqa: S104 - the point is to warn about it
            log.warning(
                "Bound to %s, which exposes this on every interface. Bind the Tailscale address "
                "instead.",
                cfg.host,
            )

        async with mcp_app.lifespan(app):
            yield

    app = FastAPI(
        title="KO sync",
        version=VERSION,
        summary="The shared store behind KO Kitchen: REST for the phone, MCP for Claude.",
        lifespan=lifespan,
    )
    app.include_router(router)
    # Registered last: FastAPI's own routes (/health, /v1/*) match first, everything else falls
    # through to the MCP app.
    app.mount("/", mounted)
    return app


app = create_app()


def run() -> None:
    import uvicorn

    cfg = settings()
    uvicorn.run("ko_sync.main:app", host=cfg.host, port=cfg.port, log_config=None)


if __name__ == "__main__":
    run()
