import json
import os
import threading
from contextlib import asynccontextmanager

import httpx
from confluent_kafka import Consumer, KafkaError
from fastapi import FastAPI
from pydantic import BaseModel

KAFKA_BROKERS = os.environ.get("KAFKA_BROKERS", "localhost:9092")
TOPICS = ["payment.completed", "payment.failed", "notification.send"]

_stop_event = threading.Event()


# ── Webhook Sender ─────────────────────────────────────────────────────────────

def send_webhook(callback_url: str, payload: dict, retries: int = 3):
    for attempt in range(retries):
        try:
            with httpx.Client(timeout=10) as client:
                resp = client.post(callback_url, json=payload)
                if resp.status_code < 500:
                    return
        except Exception as exc:
            if attempt == retries - 1:
                print(f"[notification] Webhook to {callback_url} failed after {retries} attempts: {exc}")


# ── Kafka Consumer Loop ────────────────────────────────────────────────────────

def kafka_consumer_loop():
    consumer = Consumer(
        {
            "bootstrap.servers": KAFKA_BROKERS,
            "group.id": "notification-group",
            "auto.offset.reset": "earliest",
        }
    )
    consumer.subscribe(TOPICS)
    try:
        while not _stop_event.is_set():
            msg = consumer.poll(timeout=1.0)
            if msg is None:
                continue
            if msg.error():
                if msg.error().code() != KafkaError._PARTITION_EOF:
                    print(f"[notification] Kafka error: {msg.error()}")
                continue
            try:
                data = json.loads(msg.value().decode("utf-8"))
                callback_url = data.get("callback_url")
                if callback_url:
                    t = threading.Thread(
                        target=send_webhook, args=(callback_url, data), daemon=True
                    )
                    t.start()
            except Exception as exc:
                print(f"[notification] Error processing message: {exc}")
    finally:
        consumer.close()


# ── FastAPI App ────────────────────────────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI):
    consumer_thread = threading.Thread(target=kafka_consumer_loop, daemon=True)
    consumer_thread.start()
    yield
    _stop_event.set()
    consumer_thread.join(timeout=5)


app = FastAPI(title="Notification Service", lifespan=lifespan)


class WebhookRequest(BaseModel):
    callback_url: str
    payload: dict = {}


@app.post("/api/v1/notify/webhook", status_code=202)
def trigger_webhook(req: WebhookRequest):
    """Manual webhook trigger for testing."""
    t = threading.Thread(
        target=send_webhook, args=(req.callback_url, req.payload), daemon=True
    )
    t.start()
    return {"queued": True}


@app.get("/health")
def health():
    return {"status": "ok"}
