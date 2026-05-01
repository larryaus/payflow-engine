import logging
import uuid

from fastapi import FastAPI, Query, Request, Response
from rules.rule_engine import RuleEngine

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [traceId=%(trace_id)s] %(levelname)s %(name)s - %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
logger = logging.getLogger("risk-service")

app = FastAPI(title="Risk Service", version="1.0.0")
rule_engine = RuleEngine()

TRACE_HEADER = "X-Trace-Id"


@app.middleware("http")
async def trace_middleware(request: Request, call_next):
    trace_id = request.headers.get(TRACE_HEADER, uuid.uuid4().hex)
    request.state.trace_id = trace_id
    response: Response = await call_next(request)
    response.headers[TRACE_HEADER] = trace_id
    return response


@app.post("/api/v1/risk/check")
async def check_risk(
    request: Request,
    from_account: str = Query(...),
    to_account: str = Query(...),
    amount: int = Query(...)
) -> bool:
    """
    风控检查入口
    返回 True 表示通过, False 表示拒绝
    """
    trace_id = getattr(request.state, "trace_id", "-")
    logger.info(
        "Risk check: from=%s to=%s amount=%d",
        from_account, to_account, amount,
        extra={"trace_id": trace_id},
    )
    result = rule_engine.evaluate(from_account, to_account, amount)
    logger.info(
        "Risk result: %s for from=%s amount=%d",
        "PASS" if result else "REJECT", from_account, amount,
        extra={"trace_id": trace_id},
    )
    return result


@app.get("/health")
async def health():
    return {"status": "ok"}
