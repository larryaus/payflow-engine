package com.payflow.ledger.service;

import com.payflow.ledger.domain.LedgerEntry;
import com.payflow.ledger.repository.LedgerEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 复式记账服务
 * 核心原则: 每笔交易的借方总额 == 贷方总额
 */
@Service
public class LedgerService {

    private final LedgerEntryRepository repository;

    public LedgerService(LedgerEntryRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public LedgerEntry createEntry(String referenceId, String debitAccount,
                                    String creditAccount, Long amount) {
        LedgerEntry entry = new LedgerEntry();
        entry.setEntryId("LED_" + UUID.randomUUID().toString().substring(0, 12));
        entry.setPaymentId(referenceId);
        entry.setDebitAccount(debitAccount);
        entry.setCreditAccount(creditAccount);
        entry.setAmount(amount);
        entry.setEntryType("PAYMENT");
        entry.setStatus("COMPLETED");
        return repository.save(entry);
    }

    @Transactional
    public LedgerEntry reverseEntry(String originalReferenceId, String debitAccount,
                                     String creditAccount, Long amount) {
        LedgerEntry reversal = new LedgerEntry();
        reversal.setEntryId("LED_REV_" + UUID.randomUUID().toString().substring(0, 12));
        reversal.setPaymentId("REVERSAL_" + originalReferenceId);
        reversal.setDebitAccount(creditAccount);
        reversal.setCreditAccount(debitAccount);
        reversal.setAmount(amount);
        reversal.setEntryType("REVERSAL");
        reversal.setStatus("COMPLETED");
        return repository.save(reversal);
    }

    public List<LedgerEntry> getEntriesByPaymentId(String paymentId) {
        return repository.findByPaymentId(paymentId);
    }

    /**
     * 验证记账平衡: 所有借方金额之和 == 所有贷方金额之和
     */
    public boolean verifyBalance() {
        Long totalDebit = repository.sumAllDebits();
        Long totalCredit = repository.sumAllCredits();
        return totalDebit != null && totalDebit.equals(totalCredit);
    }
}
