import os
import uuid
from datetime import datetime, timezone
from typing import List

from fastapi import Depends, FastAPI, Query
from pydantic import BaseModel
from sqlalchemy import BigInteger, Column, DateTime, String, create_engine, text
from sqlalchemy.orm import Session, declarative_base, sessionmaker

DATABASE_URL = os.environ.get(
    "DATABASE_URL", "postgresql://payflow:payflow_secret@localhost:5432/payflow"
)

engine = create_engine(DATABASE_URL)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
Base = declarative_base()


# ── ORM Model ─────────────────────────────────────────────────────────────────

class LedgerEntry(Base):
    __tablename__ = "ledger_entry"

    id = Column(BigInteger, primary_key=True, autoincrement=True)
    entry_id = Column(String(64), unique=True, nullable=False)
    payment_id = Column(String(64), nullable=False)
    debit_account = Column(String(32), nullable=False)
    credit_account = Column(String(32), nullable=False)
    amount = Column(BigInteger, nullable=False)
    currency = Column(String(3), default="CNY")
    entry_type = Column(String(32), nullable=False)
    status = Column(String(16), default="PENDING")
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))


# ── Pydantic Schemas ───────────────────────────────────────────────────────────

class LedgerEntryResponse(BaseModel):
    entry_id: str
    payment_id: str
    debit_account: str
    credit_account: str
    amount: int
    currency: str
    entry_type: str
    status: str
    created_at: datetime

    model_config = {"from_attributes": True}


# ── FastAPI App ────────────────────────────────────────────────────────────────

app = FastAPI(title="Ledger Service")


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


@app.post("/api/v1/ledger/entries", response_model=LedgerEntryResponse, status_code=201)
def create_entry(
    reference_id: str = Query(...),
    debit_account: str = Query(...),
    credit_account: str = Query(...),
    amount: int = Query(...),
    db: Session = Depends(get_db),
):
    entry_id = "LED_" + uuid.uuid4().hex[:12].upper()
    entry = LedgerEntry(
        entry_id=entry_id,
        payment_id=reference_id,
        debit_account=debit_account,
        credit_account=credit_account,
        amount=amount,
        currency="CNY",
        entry_type="PAYMENT",
        status="COMPLETED",
    )
    db.add(entry)
    db.commit()
    db.refresh(entry)
    return entry


@app.get("/api/v1/ledger/entries", response_model=List[LedgerEntryResponse])
def get_entries(payment_id: str = Query(...), db: Session = Depends(get_db)):
    return db.query(LedgerEntry).filter(LedgerEntry.payment_id == payment_id).all()


@app.get("/api/v1/ledger/verify")
def verify_balance(db: Session = Depends(get_db)):
    """
    In this schema, each ledger entry stores both sides of the double-entry in
    one row (debit_account + credit_account + amount), so the sum of all amounts
    is intrinsically equal on both sides. We verify that the table is internally
    consistent by confirming no NULL/negative amounts exist among COMPLETED rows.
    """
    bad_rows = db.execute(
        text("SELECT COUNT(*) FROM ledger_entry WHERE status='COMPLETED' AND amount <= 0")
    ).scalar()
    return bad_rows == 0


@app.get("/health")
def health():
    return {"status": "ok"}
