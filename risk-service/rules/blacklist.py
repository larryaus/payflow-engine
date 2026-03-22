class BlacklistChecker:
    """黑名单检查: 检查账户是否在黑名单中"""

    # 实际生产中从数据库/缓存加载
    BLACKLIST = set()

    def check(self, from_account: str, to_account: str, amount: int) -> bool:
        if from_account in self.BLACKLIST or to_account in self.BLACKLIST:
            return False
        return True
