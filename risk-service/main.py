from fastapi import FastAPI, Query
from rules.rule_engine import RuleEngine

app = FastAPI(title="Risk Service", version="1.0.0")
rule_engine = RuleEngine()


@app.post("/api/v1/risk/check")
async def check_risk(
    from_account: str = Query(...),
    to_account: str = Query(...),
    amount: int = Query(...)
) -> bool:
    """
    风控检查入口
    返回 True 表示通过, False 表示拒绝
    """
    return rule_engine.evaluate(from_account, to_account, amount)


@app.get("/health")
async def health():
    return {"status": "ok"}
