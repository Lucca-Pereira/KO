"""Configuration, all of it from the environment.

Nothing here has a default that reaches outside the machine it runs on, and the one secret
(``KO_API_TOKEN``) has no default at all: an unset token disables auth, which is fine on a
Tailscale-only bind and dangerous anywhere else, so it is logged loudly at startup.
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
    """Shared bearer token. Empty disables authentication entirely."""

    db_path: str = "/data/ko-sync.sqlite3"

    @property
    def auth_enabled(self) -> bool:
        return bool(self.api_token.strip())


@lru_cache
def settings() -> Settings:
    return Settings()
