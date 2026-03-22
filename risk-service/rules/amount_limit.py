class AmountLimitChecker:
    """限额规则: 单笔交易金额上限"""

    SINGLE_TX_LIMIT = 500_000_00  # 50万元 = 50000000分

    def check(self, from_account: str, to_account: str, amount: int) -> bool:
        if amount <= 0:
            return False
        if amount > self.SINGLE_TX_LIMIT:
            return False
        return True
