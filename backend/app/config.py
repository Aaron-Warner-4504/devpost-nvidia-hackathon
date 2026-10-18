from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    database_url: str = "postgresql+psycopg://sentinel:sentinel@localhost:5440/sentinel"
    llm_model: str = ""
    embed_model: str = ""
    vision_model: str = ""
    token_factory_base_url: str = ""
    token_factory_api_key: str = ""
    tavily_api_key: str = ""
    agent_mode: str = "hybrid"
    embed_dim: int = 1024


settings = Settings()
