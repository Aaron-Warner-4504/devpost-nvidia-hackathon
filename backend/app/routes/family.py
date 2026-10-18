"""Family pairing endpoints. See app/family.py for roles and what guardians receive."""
import hmac

from fastapi import APIRouter, Depends, HTTPException, Response
from sqlalchemy import delete, func, or_, select, update
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app import family
from app.auth import current_user
from app.config import settings
from app.db import get_session
from app.models import FamilyAcceptFailure, FamilyInvite, TrustedContact, User, _id
from app.schemas import (FamilyAccept, FamilyContactsOut, FamilyInviteIn, FamilyInviteOut, FamilyLinkOut,
                         GuardianOut, ProtectingOut)

router = APIRouter()
INVALID_CODE = "invalid or expired code"
_new_link_id = _id("fam")


@router.post("/v1/family/invites", response_model=FamilyInviteOut)
def create_invite(body: FamilyInviteIn, user: User = Depends(current_user), db: Session = Depends(get_session)):
    """Protected user: create a 6-character, single-use pairing code valid for 10 minutes (requires
    `consent: true`). A new invite invalidates the user's earlier unused ones; max 5 per hour."""
    now = family.utcnow()
    family.lock(db, f"family-invite:{user.id}")
    recent = db.scalar(select(func.count()).select_from(FamilyInvite).where(
        FamilyInvite.user_id == user.id, FamilyInvite.created_at > now - family.RATE_WINDOW)) or 0
    if recent >= family.MAX_INVITES_PER_HOUR:
        db.rollback()
        raise HTTPException(status_code=429, detail="too many invites, try again later")
    db.execute(delete(FamilyInvite).where(FamilyInvite.user_id == user.id,
                                          FamilyInvite.created_at <= now - family.RATE_WINDOW))
    db.execute(update(FamilyInvite).where(FamilyInvite.user_id == user.id, FamilyInvite.used_at.is_(None),
                                          FamilyInvite.expires_at > now).values(expires_at=now))
    expires = now + family.INVITE_TTL
    for _ in range(8):  # a hash collision with a stored code is astronomically rare; just retry
        code = family.new_code()
        sp = db.begin_nested()
        try:
            db.add(FamilyInvite(user_id=user.id, code_hash=family.code_hash(code), expires_at=expires,
                                created_at=now))
            db.flush()
            sp.commit()
            break
        except IntegrityError:
            sp.rollback()
    else:
        raise RuntimeError("could not allocate a pairing code")
    db.commit()
    return FamilyInviteOut(code=code, expires_at=expires)


def _accept(body: FamilyAccept, user: User, db: Session) -> FamilyLinkOut:
    now = family.utcnow()
    family.lock(db, f"family-accept:{user.id}")
    db.execute(delete(FamilyAcceptFailure).where(FamilyAcceptFailure.user_id == user.id,
                                                 FamilyAcceptFailure.created_at <= now - family.RATE_WINDOW))
    # NOTE: a successful accept deliberately does NOT clear this guardian's failure rows: an attacker could
    # otherwise reset their counter by redeeming invites they create themselves between guesses.
    fails = db.scalar(select(func.count()).select_from(FamilyAcceptFailure).where(
        FamilyAcceptFailure.user_id == user.id)) or 0
    # Global cap (all users): bounds guessing spread over many accounts. Deliberate DoS-vs-brute-force
    # trade-off: while the cap is hit, legitimate pairing pauses too (codes are not consumed; retry later).
    # Registration is rate-limited per IP, which limits how cheaply an attacker can trip it.
    global_fails = db.scalar(select(func.count()).select_from(FamilyAcceptFailure).where(
        FamilyAcceptFailure.created_at > now - family.RATE_WINDOW)) or 0
    if fails >= family.MAX_ACCEPT_FAILURES or global_fails >= settings.family_global_accept_failures_per_hour:
        db.commit()
        raise HTTPException(status_code=429, detail="too many attempts, try again later")
    code = family.normalize_code(body.code)
    h = family.code_hash(code or "\x00invalid")  # hash anyway: similar work for malformed codes
    row = None
    if code:
        # atomic claim: of concurrent accepts of one code exactly one UPDATE matches used_at IS NULL
        row = db.execute(
            update(FamilyInvite)
            .where(FamilyInvite.code_hash == h, FamilyInvite.used_at.is_(None), FamilyInvite.expires_at > now,
                   FamilyInvite.user_id != user.id)  # nobody guards themselves; the code is not burned
            .values(used_at=now)
            .returning(FamilyInvite.user_id, FamilyInvite.code_hash, FamilyInvite.created_at)
            .execution_options(synchronize_session=False)).first()
    if row is None or not hmac.compare_digest(row.code_hash, h):
        db.rollback()
        family.lock(db, f"family-accept:{user.id}")
        db.add(FamilyAcceptFailure(user_id=user.id, created_at=now))
        db.commit()
        raise HTTPException(status_code=404, detail=INVALID_CODE)
    # guardian cap: checked after the claim (only now is the protected user known) under a per-protected-
    # user lock; on refusal the claim is rolled back so the invite stays valid (and it is not a failure)
    family.lock(db, f"family-links:{row.user_id}")
    others = db.scalar(select(func.count()).select_from(TrustedContact).where(
        TrustedContact.protected_user_id == row.user_id, TrustedContact.guardian_user_id != user.id)) or 0
    if others >= family.MAX_GUARDIANS:
        db.rollback()
        raise HTTPException(status_code=409, detail="guardian limit reached")
    link_id = db.scalar(
        insert(TrustedContact)
        .values(id=_new_link_id(), protected_user_id=row.user_id, guardian_user_id=user.id, name=body.name,
                fcm_token=body.fcm_token, consent_at=row.created_at, created_at=now)
        .on_conflict_do_update(constraint="uq_trusted_contacts_pair",
                               set_={"name": body.name, "fcm_token": body.fcm_token, "fcm_invalid_at": None,
                                     "consent_at": row.created_at})
        .returning(TrustedContact.id))
    db.commit()
    return FamilyLinkOut(id=link_id)


_ACCEPT_DOC = """Guardian (authenticated as THEIR OWN app user): redeem a pairing code. Wrong, expired, used
or own codes all give the same `404 {"detail": "invalid or expired code"}`; after 10 failures in an hour
by the caller, or too many failures server-wide, 429. 409 when the protected user already has 5
guardians (the code stays valid). Returns only the link id."""


@router.post("/v1/family/accept", response_model=FamilyLinkOut, description=_ACCEPT_DOC)
def accept_invite(body: FamilyAccept, user: User = Depends(current_user), db: Session = Depends(get_session)):
    return _accept(body, user, db)


@router.post("/v1/family/contacts", response_model=FamilyLinkOut, description="Alias of POST /v1/family/accept.")
def add_family_contact(body: FamilyAccept, user: User = Depends(current_user), db: Session = Depends(get_session)):
    return _accept(body, user, db)


@router.get("/v1/family/contacts", response_model=FamilyContactsOut)
def list_contacts(user: User = Depends(current_user), db: Session = Depends(get_session)):
    """My links in both roles. Push tokens are never returned."""
    g = db.execute(select(TrustedContact.id, TrustedContact.name, TrustedContact.created_at)
                   .where(TrustedContact.protected_user_id == user.id)
                   .order_by(TrustedContact.created_at, TrustedContact.id)).all()
    p = db.execute(select(TrustedContact.id, TrustedContact.created_at)
                   .where(TrustedContact.guardian_user_id == user.id)
                   .order_by(TrustedContact.created_at, TrustedContact.id)).all()
    return FamilyContactsOut(guardians=[GuardianOut(id=i, name=n, linked_at=t) for i, n, t in g],
                             protecting=[ProtectingOut(id=i, linked_at=t) for i, t in p])


@router.delete("/v1/family/contacts/{id}", status_code=204)
def unlink(id: str, user: User = Depends(current_user), db: Session = Depends(get_session)):
    """Either side removes the link (hard delete): notifications stop immediately. 404 otherwise."""
    res = db.execute(delete(TrustedContact).where(
        TrustedContact.id == id,
        or_(TrustedContact.protected_user_id == user.id, TrustedContact.guardian_user_id == user.id)))
    db.commit()
    if not res.rowcount:
        raise HTTPException(status_code=404, detail="not found")
    return Response(status_code=204)
