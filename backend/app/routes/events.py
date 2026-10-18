from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.auth import current_user
from app.db import get_session
from app.llm import get_llm
from app.models import User
from app.pipeline.orchestrator import PipelineDeps, process_event
from app.schemas import EventIn, EventResult

router = APIRouter()


def get_pipeline_deps() -> PipelineDeps:
    """Side-effecting pipeline dependencies (RDAP, HTTP clients, alert hooks); overridden in tests."""
    return PipelineDeps()


@router.post("/v1/events", response_model=EventResult)
def post_event(event: EventIn, user: User = Depends(current_user), db: Session = Depends(get_session),
               llm=Depends(get_llm), deps: PipelineDeps = Depends(get_pipeline_deps)):
    """Ingest a device event. `alert` is set only when it should be shown (caution and up, after
    dedupe / daily cap); `duplicate: true` (alert null) when the device_event_id was already seen."""
    return process_event(db, user, event, force_agent=False, llm=llm, deps=deps)


@router.post("/v1/check", response_model=EventResult)
def check(event: EventIn, user: User = Depends(current_user), db: Session = Depends(get_session),
          llm=Depends(get_llm), deps: PipelineDeps = Depends(get_pipeline_deps)):
    """User-initiated "is this a scam?": always runs the agent and always returns an alert."""
    return process_event(db, user, event, force_agent=True, llm=llm, deps=deps)
