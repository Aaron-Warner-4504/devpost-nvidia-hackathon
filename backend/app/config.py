from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # Required: set DATABASE_URL in the environment or .env (see .env.example).
    database_url: str
    llm_model: str = ""
    embed_model: str = ""
    vision_model: str = ""
    token_factory_base_url: str = ""
    token_factory_api_key: str = ""
    tavily_api_key: str = ""
    inspector_url: str = ""
    inspector_secret: str = ""
    agent_mode: str = "hybrid"
    embed_dim: int = 1024
    # Family push (Task 11): path to a Firebase service-account JSON; unset -> notifications are no-ops.
    firebase_credentials_file: str = ""
    # Server-side secret mixed into stored pairing-code hashes. Required (>= 16 chars) when ENVIRONMENT=prod:
    # the app refuses to start without it.
    family_code_pepper: str = ""
    # dev | test | prod (anything other than dev/test is treated as a real deployment)
    environment: str = "dev"
    # Failed pairing-code accepts across ALL users per rolling hour before every accept gets 429
    # (deliberate trade-off: under a distributed guessing attack legit pairing pauses too).
    family_global_accept_failures_per_hour: int = 300
    # POST /v1/devices/register per client IP per rolling hour
    register_per_ip_per_hour: int = 20
    # POST /v1/check per user per rolling hour (JSON text checks / multipart screenshot checks, separate counters)
    check_rate_limit_per_hour: int = 30
    check_image_rate_limit_per_hour: int = 10
    # Use X-Forwarded-For for the client IP. Only enable behind a reverse proxy that appends to it.
    trust_proxy_headers: bool = False
    # Number of trusted proxies appending to X-Forwarded-For: the client is the entry this many positions
    # from the RIGHT (1 = the entry our Caddy appended). Entries further left are client-controlled.
    trusted_proxy_count: int = 1
    # Demo web dashboard (/dashboard and GET /v1/dashboard/timeline). Off by default: both return 404.
    demo_enabled: bool = False


settings = Settings()
