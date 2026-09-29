"""Configuration, all of it from the environment.

Nothing here has a default that reaches outside the machine it runs on, and the one secret
(``KO_API_TOKEN``) has no default at all: an unset token disables auth, which is fine on a
Tailscale-only bind and dangerous anywhere else, so it is logged loudly at startup — and once
``KO_PUBLIC_URL`` says the service is reachable from the internet, it refuses to start at all.
"""

from __future__ import annotations

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="KO_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    host: str = "127.0.0.1"
    """Bind address. Set this to the NAS's Tailscale IP; never 0.0.0.0."""

    port: int = 8090

    api_token: str = ""
    """Shared bearer token for the phone's `/v1`. Empty disables authentication entirely."""

    db_path: str = "/data/ko-sync.sqlite3"

    public_url: str = ""
    """The public HTTPS address (Tailscale Funnel), e.g. ``https://nas.tail1234.ts.net``.
    Empty means tailnet-only. Setting it makes the bearer token mandatory."""

    google_client_id: str = ""
    google_client_secret: str = ""

    allowed_emails: str = ""
    """Comma-separated Google accounts allowed through `/mcp` when Google sign-in is on."""

    @property
    def auth_enabled(self) -> bool:
        return bool(self.api_token.strip())

    @property
    def allowed_email_set(self) -> frozenset[str]:
        return frozenset(e.strip().lower() for e in self.allowed_emails.split(",") if e.strip())

    @property
    def google_enabled(self) -> bool:
        return bool(self.google_client_id.strip())

    def problems(self) -> list[str]:
        """Combinations that would leave the service open to the internet. Checked at startup."""
        out = []
        if self.public_url.strip() and not self.auth_enabled:
            out.append("KO_PUBLIC_URL is set but KO_API_TOKEN is empty — /v1 would be public.")
        if self.google_enabled:
            if not self.public_url.strip():
                out.append("Google sign-in needs KO_PUBLIC_URL (Google redirects back to it).")
            if not self.google_client_secret.strip():
                out.append("KO_GOOGLE_CLIENT_ID is set but KO_GOOGLE_CLIENT_SECRET is empty.")
            if not self.allowed_email_set:
                # Without this, *any* Google account could sign in and edit the kitchen.
                out.append("Google sign-in needs KO_ALLOWED_EMAILS, or anyone with Google gets in.")
        return out


@lru_cache
def settings() -> Settings:
    return Settings()
