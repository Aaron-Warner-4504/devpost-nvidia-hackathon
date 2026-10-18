"""Client for the sandboxed link inspector (Task 8b). Stub: no inspector configured -> not fetched."""

from __future__ import annotations

from pydantic import BaseModel, Field

from app.config import Settings


class LinkReport(BaseModel):
    final_url: str
    redirect_chain: list[str] = Field(default_factory=list)
    status: int | None = None
    title: str | None = None
    has_password_field: bool = False
    has_otp_or_upi_or_card_field: bool = False
    triggers_download: bool = False
    apk_download: bool = False
    brand_mismatch: str | None = None
    threat_feed_hits: list[str] = Field(default_factory=list)
    text_excerpt: str = ""
    fetched_ok: bool = False


def inspect_link(url: str, settings: Settings | None = None) -> LinkReport:
    """Ask the inspector service to look at `url`. The backend never fetches the URL itself.
    TODO(Task 8b): call POST {inspector_url}/inspect with the shared secret."""
    return LinkReport(final_url=url, fetched_ok=False)
