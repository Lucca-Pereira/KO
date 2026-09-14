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

    # ---- Server -------------------------------------------------------------------
    host: str = "127.0.0.1"
    """Bind address. Set this to the NAS's Tailscale IP; never 0.0.0.0."""

    port: int = 8080

    api_token: str = ""
    """Shared bearer token. Empty disables authentication entirely."""

    # ---- Ollama -------------------------------------------------------------------
    ollama_url: str = "http://host.docker.internal:11434"

    model_fast: str = "qwen2.5:3b"
    """Translation and ingredient parsing: short, mechanical, high volume."""

    model_chat: str = "qwen2.5:7b-instruct"
    """Conversation and structured recipe patches."""

    model_recipe: str = "qwen2.5:7b-instruct"
    """Authoring a whole recipe."""

    model_nutrition: str = "qwen2.5:7b-instruct"
    """Estimating macros for ingredients the food table does not know."""

    ollama_keep_alive: str = "-1"
    """Passed straight to Ollama. -1 pins the model in memory so the first request of the
    morning does not pay a 30-90s cold load and time the phone out."""

    warmup_seconds: int = 600
    """How often to send a one-token request to keep the model resident. 0 disables."""

    request_timeout: float = 300.0
    """Ceiling for a single Ollama call. A 7B on NAS CPU is slow; this is not a hot path."""

    # ---- Upstreams ----------------------------------------------------------------
    mealdb_url: str = "https://www.themealdb.com/api/json/v1/1/"

    openfoodfacts_url: str = "https://world.openfoodfacts.org"

    user_agent: str = "KO-Kitchen/0.5 (personal use; https://github.com/lucca/KO)"
    """Open Food Facts rate-limits anonymous clients; identify honestly."""

    cache_ttl_seconds: int = 60 * 60 * 24 * 7
    """How long to keep upstream lookups. Recipes and barcodes barely change."""

    cache_path: str = "/data/cache.sqlite3"

    @property
    def auth_enabled(self) -> bool:
        return bool(self.api_token.strip())


@lru_cache
def settings() -> Settings:
    return Settings()
