"""The ASGI app: REST for the phone at ``/v1``, MCP for Claude at ``/mcp``.

One process, one store, two front doors.
"""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from .api.rest import router
from .config import settings
from .mcp_server import mcp
from .version import VERSION

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
)
log = logging.getLogger("ko_sync")

# FastMCP builds its own ASGI app with its own lifespan (session manager, transports). That
# lifespan has to run, so it is chained into ours rather than replaced.
mcp_app = mcp.http_app(path="/")


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    cfg = settings()
    log.info("KO sync %s starting on %s:%s", VERSION, cfg.host, cfg.port)
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
app.mount("/mcp", mcp_app)


def run() -> None:
    import uvicorn

    cfg = settings()
    uvicorn.run("ko_sync.main:app", host=cfg.host, port=cfg.port, log_config=None)


if __name__ == "__main__":
    run()
