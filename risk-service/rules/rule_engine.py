from rules.blacklist import BlacklistChecker
from rules.amount_limit import AmountLimitChecker


class RuleEngine:
    """风控规则引擎 — 责任链模式"""

    def __init__(self):
        self.checkers = [
            BlacklistChecker(),
            AmountLimitChecker(),
        ]

    def evaluate(self, from_account: str, to_account: str, amount: int) -> bool:
        for checker in self.checkers:
            if not checker.check(from_account, to_account, amount):
                return False
        return True
