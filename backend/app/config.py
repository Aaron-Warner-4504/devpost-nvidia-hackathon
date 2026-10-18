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
    # Use X-Forwarded-For for the client IP. Only enable behind a proxy that overwrites the header.
    trust_proxy_headers: bool = False


settings = Settings()
