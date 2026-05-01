package com.payflow.account.service;

import com.payflow.account.domain.Account;
import com.payflow.account.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    private static final int MAX_RETRIES = 3;

    private final AccountRepository accountRepository;
    private final TransactionTemplate transactionTemplate;

    public AccountService(AccountRepository accountRepository, PlatformTransactionManager txManager) {
        this.accountRepository = accountRepository;
        this.transactionTemplate = new TransactionTemplate(txManager);
    }

    public List<Account> listAccounts() {
        return accountRepository.findAll();
    }

    public Account getAccount(String accountId) {
        return accountRepository.findByAccountId(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));
    }

    public void freezeAmount(String accountId, Long amount) {
        retryOnOptimisticLock(() -> transactionTemplate.executeWithoutResult(status -> {
            Account account = getAccount(accountId);
            account.freeze(amount);
            accountRepository.save(account);
        }));
    }

    public void unfreezeAmount(String accountId, Long amount) {
        retryOnOptimisticLock(() -> transactionTemplate.executeWithoutResult(status -> {
            Account account = getAccount(accountId);
            account.unfreeze(amount);
            accountRepository.save(account);
        }));
    }

    /**
     * 执行转账: 付款方扣款(从冻结金额) + 收款方入账
     * 使用 @Version 乐观锁 + 重试保证并发安全
     */
    public void transfer(String fromAccountId, String toAccountId, Long amount) {
        retryOnOptimisticLock(() -> transactionTemplate.executeWithoutResult(status -> {
            Account from = getAccount(fromAccountId);
            Account to = getAccount(toAccountId);

            from.debit(amount);
            to.credit(amount);

            accountRepository.save(from);
            accountRepository.save(to);
        }));
    }

    private void retryOnOptimisticLock(Runnable operation) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                operation.run();
                return;
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt == MAX_RETRIES) {
                    log.error("Optimistic lock failed after {} attempts", MAX_RETRIES);
                    throw e;
                }
                log.warn("Optimistic lock conflict on attempt {}, retrying...", attempt);
            }
        }
    }
}
