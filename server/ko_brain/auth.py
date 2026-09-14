"""Bearer-token auth.

The service is expected to be bound to a Tailscale address, so this is a second lock rather than
the only one. It still matters: anything on the tailnet can reach the port, and "my own devices"
grows over time.

An unset token disables auth entirely, which is a legitimate choice for a single-user box — but
it is logged as a warning at startup rather than passing quietly.
"""

from __future__ import annotations

import hmac
import logging

from fastapi import Header, HTTPException, status

from .config import settings

log = logging.getLogger(__name__)


async def require_token(authorization: str | None = Header(default=None)) -> None:
    cfg = settings()
    if not cfg.auth_enabled:
        return

    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing bearer token.",
            headers={"WWW-Authenticate": "Bearer"},
        )

    supplied = authorization.split(" ", 1)[1].strip()
    # Constant-time: a token is short enough that timing a comparison is not fantasy.
    if not hmac.compare_digest(supplied, cfg.api_token.strip()):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid token.",
            headers={"WWW-Authenticate": "Bearer"},
        )
