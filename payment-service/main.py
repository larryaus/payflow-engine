import json
import os
import threading
import time
import uuid
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from enum import Enum
from typing import List, Optional

import httpx
import redis as redis_lib
from confluent_kafka import Consumer, KafkaError, Producer
from fastapi import Depends, FastAPI, Header, HTTPException, Query
from pydantic import BaseModel
from sqlalchemy import BigInteger, Column, DateTime, String, create_engine
from sqlalchemy.orm import Session, declarative_base, sessionmaker

# ── Configuration ──────────────────────────────────────────────────────────────

DATABASE_URL = os.environ.get(
    "DATABASE_URL", "postgresql://payflow:payflow_secret@localhost:5432/payflow"
)
REDIS_HOST = os.environ.get("REDIS_HOST", "localhost")
KAFKA_BROKERS = os.environ.get("KAFKA_BROKERS", "localhost:9092")
ACCOUNT_SERVICE_URL = os.environ.get("ACCOUNT_SERVICE_URL", "http://account-service:8082")
LEDGER_SERVICE_URL = os.environ.get("LEDGER_SERVICE_URL", "http://ledger-service:8083")
RISK_SERVICE_URL = os.environ.get("RISK_SERVICE_URL", "http://risk-service:8084")

# ── Database ───────────────────────────────────────────────────────────────────

engine = create_engine(DATABASE_URL)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
Base = declarative_base()


class PaymentStatus(str, Enum):
    CREATED = "CREATED"
    PENDING = "PENDING"
    PROCESSING = "PROCESSING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    REFUNDING = "REFUNDING"
    REFUNDED = "REFUNDED"

    def can_transit_to(self, target: "PaymentStatus") -> bool:
        transitions = {
            PaymentStatus.CREATED: {PaymentStatus.PENDING, PaymentStatus.FAILED},
            PaymentStatus.PENDING: {PaymentStatus.PROCESSING, PaymentStatus.FAILED},
            PaymentStatus.PROCESSING: {PaymentStatus.COMPLETED, PaymentStatus.FAILED},
            PaymentStatus.COMPLETED: {PaymentStatus.REFUNDING, PaymentStatus.REFUNDED},
            PaymentStatus.REFUNDING: {PaymentStatus.REFUNDED, PaymentStatus.FAILED},
        }
        return target in transitions.get(self, set())


class RefundStatus(str, Enum):
    PROCESSING = "PROCESSING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


class PaymentOrder(Base):
    __tablename__ = "payment_order"

    id = Column(BigInteger, primary_key=True, autoincrement=True)
    payment_id = Column(String(64), unique=True, nullable=False)
    idempotency_key = Column(String(64), unique=True, nullable=False)
    from_account = Column(String(32), nullable=False)
    to_account = Column(String(32), nullable=False)
    amount = Column(BigInteger, nullable=False)
    currency = Column(String(3), default="CNY")
    status = Column(String(16), default="CREATED")
    payment_method = Column(String(32))
    memo = Column(String(256))
    callback_url = Column(String(512))
    created_at = Column(DateTime(timezone=True))
    updated_at = Column(DateTime(timezone=True))
    completed_at = Column(DateTime(timezone=True))


class RefundOrder(Base):
    __tablename__ = "refund_order"

    id = Column(BigInteger, primary_key=True, autoincrement=True)
    refund_id = Column(String(64), unique=True, nullable=False)
    payment_id = Column(String(64), nullable=False)
    idempotency_key = Column(String(64), unique=True, nullable=False)
    amount = Column(BigInteger, nullable=False)
    reason = Column(String(256))
    status = Column(String(16), default="PROCESSING")
    created_at = Column(DateTime(timezone=True))
    completed_at = Column(DateTime(timezone=True))


# ── Pydantic Schemas ───────────────────────────────────────────────────────────

class CreatePaymentRequest(BaseModel):
    from_account: str
    to_account: str
    amount: int
    currency: str = "CNY"
    payment_method: str
    memo: Optional[str] = None
    callback_url: Optional[str] = None


class RefundRequest(BaseModel):
    amount: int
    reason: Optional[str] = None


class PaymentResponse(BaseModel):
    payment_id: str
    from_account: str
    to_account: str
    amount: int
    currency: str
    status: str
    payment_method: Optional[str]
    memo: Optional[str]
    callback_url: Optional[str]
    created_at: Optional[datetime]
    updated_at: Optional[datetime]
    completed_at: Optional[datetime]

    model_config = {"from_attributes": True}


class RefundResponse(BaseModel):
    refund_id: str
    payment_id: str
    amount: int
    reason: Optional[str]
    status: str
    created_at: Optional[datetime]
    completed_at: Optional[datetime]

    model_config = {"from_attributes": True}


# ── Redis ──────────────────────────────────────────────────────────────────────

_redis = redis_lib.Redis(host=REDIS_HOST, port=6379, decode_responses=True)
IDEMPOTENCY_TTL = 86400  # 24 hours


def idempotency_try_acquire(key: str) -> bool:
    """Atomically claim the key. Returns True if new, False if duplicate."""
    return bool(_redis.set(f"idempotent:{key}", "PROCESSING", ex=IDEMPOTENCY_TTL, nx=True))


def idempotency_mark_completed(key: str, payment_id: str):
    _redis.set(f"idempotent:{key}", payment_id, ex=IDEMPOTENCY_TTL)


def idempotency_get_payment_id(key: str) -> Optional[str]:
    value = _redis.get(f"idempotent:{key}")
    return value if (value and value != "PROCESSING") else None


def acquire_account_lock(account_id: str, timeout: int = 5) -> Optional[str]:
    """Try to acquire a distributed lock on the account. Returns token or None."""
    lock_key = f"account:lock:{account_id}"
    token = str(uuid.uuid4())
    deadline = time.time() + timeout
    while time.time() < deadline:
        if _redis.set(lock_key, token, ex=30, nx=True):
            return token
        time.sleep(0.1)
    return None


def release_account_lock(account_id: str, token: str):
    lock_key = f"account:lock:{account_id}"
    if _redis.get(lock_key) == token:
        _redis.delete(lock_key)


# ── Kafka ──────────────────────────────────────────────────────────────────────

_producer: Optional[Producer] = None


def _get_producer() -> Producer:
    global _producer
    if _producer is None:
        _producer = Producer({"bootstrap.servers": KAFKA_BROKERS})
    return _producer


def publish(topic: str, key: str, payload: dict):
    _get_producer().produce(topic, key=key, value=json.dumps(payload).encode())
    _get_producer().flush()


# ── HTTP Clients ───────────────────────────────────────────────────────────────

def check_risk(from_account: str, to_account: str, amount: int) -> bool:
    try:
        with httpx.Client(timeout=5) as client:
            resp = client.post(
                f"{RISK_SERVICE_URL}/api/v1/risk/check",
                params={"from_account": from_account, "to_account": to_account, "amount": amount},
            )
            return resp.json() is True
    except Exception:
        return False


def freeze_account(account_id: str, amount: int):
    with httpx.Client(timeout=5) as c:
        c.post(
            f"{ACCOUNT_SERVICE_URL}/api/v1/accounts/freeze",
            params={"account_id": account_id, "amount": amount},
        ).raise_for_status()


def unfreeze_account(account_id: str, amount: int):
    with httpx.Client(timeout=5) as c:
        c.post(
            f"{ACCOUNT_SERVICE_URL}/api/v1/accounts/unfreeze",
            params={"account_id": account_id, "amount": amount},
        ).raise_for_status()


def transfer_accounts(from_account: str, to_account: str, amount: int):
    with httpx.Client(timeout=5) as c:
        c.post(
            f"{ACCOUNT_SERVICE_URL}/api/v1/accounts/transfer",
            params={"from_account": from_account, "to_account": to_account, "amount": amount},
        ).raise_for_status()


def create_ledger_entry(reference_id: str, debit_account: str, credit_account: str, amount: int):
    with httpx.Client(timeout=5) as c:
        c.post(
            f"{LEDGER_SERVICE_URL}/api/v1/ledger/entries",
            params={
                "reference_id": reference_id,
                "debit_account": debit_account,
                "credit_account": credit_account,
                "amount": amount,
            },
        ).raise_for_status()


# ── DB Helpers ─────────────────────────────────────────────────────────────────

def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def _transit_status(db: Session, payment: PaymentOrder, new_status: PaymentStatus):
    current = PaymentStatus(payment.status)
    if not current.can_transit_to(new_status):
        raise ValueError(f"Cannot transition {current} → {new_status}")
    payment.status = new_status.value
    payment.updated_at = datetime.now(timezone.utc)
    if new_status == PaymentStatus.COMPLETED:
        payment.completed_at = payment.updated_at
    db.commit()


# ── Async Payment Processor ────────────────────────────────────────────────────

def _process_payment(payment_id: str):
    """
    Background worker: freeze → ledger → transfer → complete.
    Runs in a dedicated thread so the Kafka consumer is not blocked.
    """
    db = SessionLocal()
    try:
        payment = db.query(PaymentOrder).filter(PaymentOrder.payment_id == payment_id).first()
        if not payment:
            return

        lock_token = acquire_account_lock(payment.from_account)
        if not lock_token:
            _transit_status(db, payment, PaymentStatus.FAILED)
            publish(
                "payment.failed",
                payment.from_account,
                {"payment_id": payment_id, "reason": "ACCOUNT_BUSY", "event": "FAILED"},
            )
            return

        try:
            _transit_status(db, payment, PaymentStatus.PROCESSING)
            freeze_account(payment.from_account, payment.amount)
            create_ledger_entry(
                payment_id, payment.from_account, payment.to_account, payment.amount
            )
            transfer_accounts(payment.from_account, payment.to_account, payment.amount)
            _transit_status(db, payment, PaymentStatus.COMPLETED)
            publish(
                "payment.completed",
                payment.from_account,
                {
                    "payment_id": payment_id,
                    "from_account": payment.from_account,
                    "to_account": payment.to_account,
                    "amount": payment.amount,
                    "callback_url": payment.callback_url,
                    "event": "COMPLETED",
                },
            )
        except Exception as exc:
            print(f"[payment] Processing failed for {payment_id}: {exc}")
            try:
                unfreeze_account(payment.from_account, payment.amount)
            except Exception:
                pass
            try:
                _transit_status(db, payment, PaymentStatus.FAILED)
            except Exception:
                pass
            publish(
                "payment.failed",
                payment.from_account,
                {"payment_id": payment_id, "reason": str(exc), "event": "FAILED"},
            )
        finally:
            release_account_lock(payment.from_account, lock_token)
    finally:
        db.close()


# ── Kafka Consumer ─────────────────────────────────────────────────────────────

_stop_event = threading.Event()


def _kafka_consumer_loop():
    consumer = Consumer(
        {
            "bootstrap.servers": KAFKA_BROKERS,
            "group.id": "payment-processor",
            "auto.offset.reset": "earliest",
        }
    )
    consumer.subscribe(["payment.created"])
    try:
        while not _stop_event.is_set():
            msg = consumer.poll(timeout=1.0)
            if msg is None:
                continue
            if msg.error():
                if msg.error().code() != KafkaError._PARTITION_EOF:
                    print(f"[payment] Kafka error: {msg.error()}")
                continue
            try:
                data = json.loads(msg.value().decode("utf-8"))
                payment_id = data.get("payment_id")
                if payment_id:
                    threading.Thread(
                        target=_process_payment, args=(payment_id,), daemon=True
                    ).start()
            except Exception as exc:
                print(f"[payment] Error handling Kafka message: {exc}")
    finally:
        consumer.close()


# ── FastAPI App ────────────────────────────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI):
    t = threading.Thread(target=_kafka_consumer_loop, daemon=True)
    t.start()
    yield
    _stop_event.set()
    t.join(timeout=5)


app = FastAPI(title="Payment Service", lifespan=lifespan)


@app.post("/api/v1/payments", status_code=202)
def create_payment(
    body: CreatePaymentRequest,
    idempotency_key: str = Header(..., alias="Idempotency-Key"),
):
    if not idempotency_try_acquire(idempotency_key):
        # Duplicate request — poll Redis for the completed paymentId
        for _ in range(5):
            existing_id = idempotency_get_payment_id(idempotency_key)
            if existing_id:
                db = SessionLocal()
                try:
                    payment = (
                        db.query(PaymentOrder)
                        .filter(PaymentOrder.payment_id == existing_id)
                        .first()
                    )
                    if payment:
                        return PaymentResponse.model_validate(payment)
                finally:
                    db.close()
            time.sleep(0.2)
        raise HTTPException(status_code=409, detail="Duplicate request is still being processed")

    db = SessionLocal()
    try:
        payment_id = "PAY_" + uuid.uuid4().hex[:16].upper()
        now = datetime.now(timezone.utc)
        payment = PaymentOrder(
            payment_id=payment_id,
            idempotency_key=idempotency_key,
            from_account=body.from_account,
            to_account=body.to_account,
            amount=body.amount,
            currency=body.currency,
            status=PaymentStatus.CREATED.value,
            payment_method=body.payment_method,
            memo=body.memo,
            callback_url=body.callback_url,
            created_at=now,
            updated_at=now,
        )
        db.add(payment)
        db.commit()
        db.refresh(payment)

        # Persist paymentId so duplicate requests can retrieve it
        idempotency_mark_completed(idempotency_key, payment_id)

        # Synchronous risk check
        if not check_risk(body.from_account, body.to_account, body.amount):
            payment.status = PaymentStatus.FAILED.value
            payment.updated_at = datetime.now(timezone.utc)
            db.commit()
            raise HTTPException(status_code=400, detail="Payment rejected by risk engine")

        # Transition CREATED → PENDING
        payment.status = PaymentStatus.PENDING.value
        payment.updated_at = datetime.now(timezone.utc)
        db.commit()

        # Publish event to trigger async processing
        publish(
            "payment.created",
            body.from_account,
            {
                "payment_id": payment_id,
                "from_account": body.from_account,
                "to_account": body.to_account,
                "amount": body.amount,
                "event": "CREATED",
            },
        )

        return {"payment_id": payment_id, "status": PaymentStatus.PENDING.value, "created_at": now}
    finally:
        db.close()


@app.get("/api/v1/payments")
def list_payments(page: int = Query(1, ge=1), size: int = Query(20, ge=1, le=100)):
    db = SessionLocal()
    try:
        offset = (page - 1) * size
        total = db.query(PaymentOrder).count()
        payments = (
            db.query(PaymentOrder)
            .order_by(PaymentOrder.created_at.desc())
            .offset(offset)
            .limit(size)
            .all()
        )
        return {
            "data": [PaymentResponse.model_validate(p) for p in payments],
            "total": total,
        }
    finally:
        db.close()


@app.get("/api/v1/payments/{payment_id}", response_model=PaymentResponse)
def get_payment(payment_id: str):
    db = SessionLocal()
    try:
        payment = db.query(PaymentOrder).filter(PaymentOrder.payment_id == payment_id).first()
        if not payment:
            raise HTTPException(status_code=404, detail="Payment not found")
        return PaymentResponse.model_validate(payment)
    finally:
        db.close()


@app.post("/api/v1/payments/{payment_id}/refund", status_code=201, response_model=RefundResponse)
def create_refund(
    payment_id: str,
    body: RefundRequest,
    idempotency_key: str = Header(..., alias="Idempotency-Key"),
):
    db = SessionLocal()
    try:
        # Idempotency check
        existing = (
            db.query(RefundOrder).filter(RefundOrder.idempotency_key == idempotency_key).first()
        )
        if existing:
            return RefundResponse.model_validate(existing)

        payment = db.query(PaymentOrder).filter(PaymentOrder.payment_id == payment_id).first()
        if not payment:
            raise HTTPException(status_code=404, detail="Payment not found")
        if payment.status != PaymentStatus.COMPLETED.value:
            raise HTTPException(status_code=400, detail="Only COMPLETED payments can be refunded")
        if body.amount > payment.amount:
            raise HTTPException(status_code=400, detail="Refund amount exceeds original payment")

        refund_id = "REF_" + uuid.uuid4().hex[:16].upper()
        now = datetime.now(timezone.utc)
        refund = RefundOrder(
            refund_id=refund_id,
            payment_id=payment_id,
            idempotency_key=idempotency_key,
            amount=body.amount,
            reason=body.reason,
            status=RefundStatus.PROCESSING.value,
            created_at=now,
        )
        db.add(refund)
        db.commit()

        try:
            # Reverse transfer: to_account → from_account
            transfer_accounts(payment.to_account, payment.from_account, body.amount)
            create_ledger_entry(
                f"REFUND_{refund_id}",
                payment.to_account,
                payment.from_account,
                body.amount,
            )
            refund.status = RefundStatus.COMPLETED.value
            refund.completed_at = datetime.now(timezone.utc)
            # Full refund → mark payment as REFUNDED
            if body.amount == payment.amount:
                payment.status = PaymentStatus.REFUNDED.value
                payment.updated_at = datetime.now(timezone.utc)
            db.commit()
        except Exception as exc:
            refund.status = RefundStatus.FAILED.value
            db.commit()
            raise HTTPException(status_code=500, detail=f"Refund execution failed: {exc}")

        db.refresh(refund)
        return RefundResponse.model_validate(refund)
    finally:
        db.close()


@app.get("/health")
def health():
    return {"status": "ok"}
