import ipaddress

from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

DEFAULT_TRUSTED_PROXY_CIDRS = "127.0.0.0/8,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # Required: set DATABASE_URL in the environment or .env (see .env.example).
    database_url: str
    # Pool sizing: keep small behind a pooler (Supabase free tier has few connections).
    db_pool_size: int = 5
    db_max_overflow: int = 5
    db_pool_recycle: int = 1800
    # Startup search_path so the pgvector `vector` type resolves where Supabase installs it (schema
    # `extensions`). Nonexistent schemas are harmless. Empty string = leave the server default.
    db_search_path: str = "public,extensions"
    # Set true when DATABASE_URL points at a TRANSACTION pooler (Supabase port 6543): disables psycopg
    # server-side prepared statements, which break across pooled connections.
    db_disable_prepared_statements: bool = False
    llm_model: str = ""
    embed_model: str = ""
    vision_model: str = ""
    token_factory_base_url: str = ""
    token_factory_api_key: str = ""
    tavily_api_key: str = ""
    inspector_url: str = ""
    inspector_secret: str = ""
    agent_mode: str = "hybrid"
    # First-pass classifier gate (app/pipeline/classifier.py): "rules" (default, off: current behaviour,
    # no extra dependency) | "laya" (self-hosted Laya, uv extra `laya`). It can only ADD an agent
    # escalation when scam_prob >= classifier_threshold; it never lowers a tier or suppresses an alert.
    classifier: str = "rules"
    classifier_threshold: float = 0.5
    classifier_timeout_ms: int = 300
    laya_model: str = "multilingual"  # multilingual | english | auto
    embed_dim: int = 1024
    # ---- persistent memory (docs/memory.md) ----
    # Embedding provider for semantic memory: "" = auto (token_factory when TOKEN_FACTORY_API_KEY and
    # EMBED_MODEL are set, else none) | token_factory | local (uv extra `semantic`) | fake (tests) | none.
    embedding_provider: str = ""
    # Semantic linking of events. None = auto: on only when the embedding provider is usable.
    semantic_memory: bool | None = None
    local_embed_model: str = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
    embed_timeout_s: float = 5.0
    # Send the OpenAI-compatible `dimensions=EMBED_DIM` parameter (Matryoshka models such as Qwen3-Embedding).
    # Auto-disabled per process if the server rejects it; vectors are truncated + normalised either way.
    embed_send_dimensions: bool = True
    # cosine similarity (1 - distance) needed to link two events as `similar`; tuned on the eval TUNE split
    semantic_threshold: float = 0.25
    semantic_k: int = 5
    semantic_window_days: int = 14
    # Rules-only scam-story progression signal (no LLM)
    story_progression: bool = True
    # Stage-2 background review: "escalated" (agent only for escalated events, as before) | "all"
    agent_review: str = "escalated"
    agent_review_daily_cap: int = 200  # stage-2 agent runs per user per rolling 24 h
    review_workers: int = 2
    review_queue_size: int = 256
    review_sweep_minutes: int = 5  # sweeper period and stale-`running` age
    review_max_queued_per_user: int = 20  # in-queue + running stage-2 jobs per user (0 = unlimited)
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
    # URL path prefix when served behind a reverse proxy that strips it (e.g. /sentinel). Routes stay
    # unprefixed; this only affects generated URLs (OpenAPI servers, docs). Empty = served at the root.
    root_path: str = ""
    # X-Forwarded-For is honoured only when the socket peer is inside one of these comma-separated CIDRs
    # (default: loopback + private ranges, which covers a proxy on the same host or the docker bridge
    # gateway). A peer outside them is used as the client address and the header is ignored.
    trusted_proxy_cidrs: str = DEFAULT_TRUSTED_PROXY_CIDRS
    # Demo web dashboard (/dashboard and GET /v1/dashboard/timeline). Off by default: both return 404.
    demo_enabled: bool = False
    # Interactive API docs (/docs, /redoc) and /openapi.json. Unset = on, except when ENVIRONMENT=prod (off).
    # DOCS_ENABLED=true/false overrides either way.
    docs_enabled: bool | None = None
    # Dev-only: comma-separated browser origins allowed by CORS (e.g. http://localhost:5173). Empty = no CORS
    # middleware. '*' only in dev/test; in prod every origin must be https. See app/cors.py.
    cors_allow_origins: str = ""

    @field_validator("docs_enabled", mode="before")
    @classmethod
    def _blank_docs_enabled(cls, v):
        return None if isinstance(v, str) and not v.strip() else v

    @field_validator("trusted_proxy_cidrs")
    @classmethod
    def _check_cidrs(cls, v: str) -> str:
        for part in v.split(","):
            part = part.strip()
            if not part:
                continue
            try:
                ipaddress.ip_network(part, strict=False)
            except ValueError:
                raise ValueError(f"TRUSTED_PROXY_CIDRS: {part!r} is not a valid CIDR network") from None
        return v


def docs_are_enabled(s: "Settings") -> bool:
    return s.docs_enabled if s.docs_enabled is not None else s.environment != "prod"


settings = Settings()
