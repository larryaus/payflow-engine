package com.payflow.account.service;

import com.payflow.account.domain.Account;
import com.payflow.account.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public Account getAccount(String accountId) {
        return accountRepository.findByAccountId(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));
    }

    @Transactional
    public void freezeAmount(String accountId, Long amount) {
        Account account = getAccount(accountId);
        account.freeze(amount);
        accountRepository.save(account);
    }

    @Transactional
    public void unfreezeAmount(String accountId, Long amount) {
        Account account = getAccount(accountId);
        account.unfreeze(amount);
        accountRepository.save(account);
    }

    /**
     * 执行转账: 付款方扣款(从冻结金额) + 收款方入账
     * 使用 @Version 乐观锁保证并发安全
     */
    @Transactional
    public void transfer(String fromAccountId, String toAccountId, Long amount) {
        Account from = getAccount(fromAccountId);
        Account to = getAccount(toAccountId);

        from.debit(amount);
        to.credit(amount);

        accountRepository.save(from);
        accountRepository.save(to);
    }
}
