import logging
from rules.blacklist import BlacklistChecker
from rules.amount_limit import AmountLimitChecker

logger = logging.getLogger(__name__)


class RuleEngine:
    """风控规则引擎 — 责任链模式"""

    def __init__(self):
        self.checkers = [
            BlacklistChecker(),
            AmountLimitChecker(),
        ]

    def evaluate(self, from_account: str, to_account: str, amount: int) -> bool:
        for checker in self.checkers:
            try:
                if not checker.check(from_account, to_account, amount):
                    logger.info("Risk check rejected by %s: %s -> %s amount=%d",
                                checker.__class__.__name__, from_account, to_account, amount)
                    return False
            except Exception as e:
                logger.error("Rule %s raised exception, denying by default: %s",
                             checker.__class__.__name__, str(e))
                return False
        return True
