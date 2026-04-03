import logging
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from rules.rule_engine import RuleEngine

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="Risk Service", version="1.0.0")
rule_engine = RuleEngine()


class RiskCheckRequest(BaseModel):
    from_account: str = Field(..., min_length=1, max_length=64)
    to_account: str = Field(..., min_length=1, max_length=64)
    amount: int = Field(..., gt=0)


@app.post("/api/v1/risk/check")
async def check_risk(request: RiskCheckRequest) -> dict:
    """
    风控检查入口
    返回 approved=True 表示通过, approved=False 表示拒绝
    """
    try:
        approved = rule_engine.evaluate(request.from_account, request.to_account, request.amount)
        return {"approved": approved}
    except Exception as e:
        logger.error("Risk check failed for %s -> %s amount=%d: %s",
                     request.from_account, request.to_account, request.amount, str(e))
        raise HTTPException(status_code=500, detail="Risk evaluation failed")


@app.get("/health")
async def health():
    return {"status": "ok"}
