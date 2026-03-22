import os
from datetime import datetime, timezone
from typing import Optional

from fastapi import Depends, FastAPI, HTTPException, Query
from pydantic import BaseModel
from sqlalchemy import BigInteger, Column, DateTime, String, create_engine
from sqlalchemy.orm import Session, declarative_base, sessionmaker

DATABASE_URL = os.environ.get(
    "DATABASE_URL", "postgresql://payflow:payflow_secret@localhost:5432/payflow"
)

engine = create_engine(DATABASE_URL)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
Base = declarative_base()


# ── ORM Model ─────────────────────────────────────────────────────────────────

class Account(Base):
    __tablename__ = "account"

    id = Column(BigInteger, primary_key=True, autoincrement=True)
    account_id = Column(String(32), unique=True, nullable=False)
    account_name = Column(String(128))
    available_balance = Column(BigInteger, default=0)
    frozen_balance = Column(BigInteger, default=0)
    currency = Column(String(3), default="CNY")
    status = Column(String(16), default="ACTIVE")
    version = Column(BigInteger, default=0)
    created_at = Column(DateTime(timezone=True))
    updated_at = Column(DateTime(timezone=True))


# ── Pydantic Schemas ───────────────────────────────────────────────────────────

class BalanceResponse(BaseModel):
    account_id: str
    available_balance: int
    frozen_balance: int
    currency: str
    updated_at: Optional[datetime]

    model_config = {"from_attributes": True}


# ── FastAPI App ────────────────────────────────────────────────────────────────

app = FastAPI(title="Account Service")


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def _get_account(account_id: str, db: Session) -> Account:
    account = db.query(Account).filter(Account.account_id == account_id).first()
    if not account:
        raise HTTPException(status_code=404, detail=f"Account {account_id} not found")
    return account


@app.get("/api/v1/accounts/{account_id}/balance", response_model=BalanceResponse)
def get_balance(account_id: str, db: Session = Depends(get_db)):
    return _get_account(account_id, db)


@app.post("/api/v1/accounts/freeze", status_code=200)
def freeze(
    account_id: str = Query(...),
    amount: int = Query(...),
    db: Session = Depends(get_db),
):
    account = _get_account(account_id, db)
    if (account.available_balance or 0) < amount:
        raise HTTPException(status_code=400, detail="Insufficient available balance")
    account.available_balance -= amount
    account.frozen_balance = (account.frozen_balance or 0) + amount
    account.version = (account.version or 0) + 1
    account.updated_at = datetime.now(timezone.utc)
    db.commit()
    return {}


@app.post("/api/v1/accounts/unfreeze", status_code=200)
def unfreeze(
    account_id: str = Query(...),
    amount: int = Query(...),
    db: Session = Depends(get_db),
):
    account = _get_account(account_id, db)
    if (account.frozen_balance or 0) < amount:
        raise HTTPException(status_code=400, detail="Insufficient frozen balance")
    account.frozen_balance -= amount
    account.available_balance = (account.available_balance or 0) + amount
    account.version = (account.version or 0) + 1
    account.updated_at = datetime.now(timezone.utc)
    db.commit()
    return {}


@app.post("/api/v1/accounts/transfer", status_code=200)
def transfer(
    from_account: str = Query(...),
    to_account: str = Query(...),
    amount: int = Query(...),
    db: Session = Depends(get_db),
):
    from_acc = _get_account(from_account, db)
    to_acc = _get_account(to_account, db)
    if (from_acc.frozen_balance or 0) < amount:
        raise HTTPException(status_code=400, detail="Insufficient frozen balance")
    from_acc.frozen_balance -= amount
    to_acc.available_balance = (to_acc.available_balance or 0) + amount
    now = datetime.now(timezone.utc)
    from_acc.version = (from_acc.version or 0) + 1
    from_acc.updated_at = now
    to_acc.version = (to_acc.version or 0) + 1
    to_acc.updated_at = now
    db.commit()
    return {}


@app.get("/health")
def health():
    return {"status": "ok"}
