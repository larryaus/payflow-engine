package com.payflow.account.service;

import com.payflow.account.domain.Account;
import com.payflow.account.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public List<Account> listAccounts() {
        return accountRepository.findAll();
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

    /**
     * 逆向转账补偿: 从收款方可用余额扣回，退还给付款方可用余额
     * 仅用于 Saga 补偿，不经过冻结流程
     */
    @Transactional
    public void reverseTransfer(String fromAccountId, String toAccountId, Long amount) {
        Account from = getAccount(fromAccountId);
        Account to = getAccount(toAccountId);

        to.deductAvailable(amount);
        from.credit(amount);

        accountRepository.save(from);
        accountRepository.save(to);
    }
}
