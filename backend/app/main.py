from fastapi import Depends, FastAPI

from app.auth import current_user
from app.routes import alerts, events, me
from app.schemas import FamilyContact, Feedback

app = FastAPI(title="Sentinel API", version="0.1.0")
app.include_router(me.router)
app.include_router(events.router)
app.include_router(alerts.router)


@app.get("/health")
def health():
    return {"status": "ok"}


# Stubs until Task 11 (feedback overrides, family pairing): fixed payloads, same shapes as the contract.
@app.post("/v1/alerts/{id}/feedback")
def alert_feedback(id: str, body: Feedback, _: str = Depends(current_user)):
    return {"ok": True}


@app.post("/v1/family/contacts")
def add_family_contact(body: FamilyContact, _: str = Depends(current_user)):
    return {"ok": True, "id": "fam_123"}
