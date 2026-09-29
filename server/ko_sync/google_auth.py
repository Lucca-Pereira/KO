"""Google sign-in for `/mcp`, limited to an allowlist of accounts.

claude.ai's custom connectors (which is also what the phone and Desktop apps use) call the server
from Anthropic's cloud and can only authenticate with OAuth — they can't send the phone's static
bearer token. FastMCP's `GoogleProvider` does the OAuth dance; on its own it would accept *any*
Google account, so the token check is wrapped with an email allowlist. FastMCP runs that check on
every MCP request (it re-validates the upstream Google token each time), so a non-allowlisted
account gets a 401 on its first tool call, not just at sign-in.
"""

from __future__ import annotations

import logging

from fastmcp.server.auth import TokenVerifier
from fastmcp.server.auth.auth import AccessToken
from fastmcp.server.auth.providers.google import GoogleProvider

from .config import Settings

log = logging.getLogger(__name__)


class AllowlistVerifier(TokenVerifier):
    def __init__(self, inner: TokenVerifier, allowed: frozenset[str]) -> None:
        super().__init__(required_scopes=inner.required_scopes)
        self.inner = inner
        self.allowed = allowed

    async def verify_token(self, token: str) -> AccessToken | None:
        access = await self.inner.verify_token(token)
        if access is None:
            return None
        email = str(access.claims.get("email") or "").lower()
        # Google's tokeninfo returns "true" as a string; the userinfo fallback returns a bool.
        verified = str(access.claims.get("email_verified")).lower() == "true"
        if not verified or email not in self.allowed:
            log.warning("Rejected Google account %r: not in KO_ALLOWED_EMAILS", email or "?")
            return None
        return access


def google_provider(cfg: Settings) -> GoogleProvider:
    provider = GoogleProvider(
        client_id=cfg.google_client_id.strip(),
        client_secret=cfg.google_client_secret.strip(),
        base_url=cfg.public_url.strip().rstrip("/"),
        required_scopes=["openid", "email"],
    )
    provider._token_validator = AllowlistVerifier(provider._token_validator, cfg.allowed_email_set)
    return provider
