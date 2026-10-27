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

    /**
     * 记账冲销补偿: 创建借贷互换的 REVERSAL 分录，使双边账目归零
     * 账本记录不可删除，冲销是唯一正确的会计补偿方式
     */
    @Transactional
    public LedgerEntry reverseEntry(String paymentId) {
        List<LedgerEntry> entries = repository.findByPaymentId(paymentId);
        LedgerEntry original = entries.stream()
                .filter(e -> "PAYMENT".equals(e.getEntryType()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No PAYMENT ledger entry found for paymentId: " + paymentId));

        LedgerEntry reversal = new LedgerEntry();
        reversal.setEntryId("LED_" + UUID.randomUUID().toString().substring(0, 12));
        reversal.setPaymentId(paymentId);
        reversal.setDebitAccount(original.getCreditAccount());   // 借贷互换
        reversal.setCreditAccount(original.getDebitAccount());   // 借贷互换
        reversal.setAmount(original.getAmount());
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
