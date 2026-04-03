package com.payflow.account.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "account")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, unique = true)
    private String accountId;

    @Column(name = "account_name", nullable = false)
    private String accountName;

    @Column(name = "available_balance", nullable = false)
    private Long availableBalance = 0L;

    @Column(name = "frozen_balance", nullable = false)
    private Long frozenBalance = 0L;

    @Column(nullable = false)
    private String currency = "CNY";

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Version
    private Long version = 0L;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getAccountName() { return accountName; }
    public void setAccountName(String accountName) { this.accountName = accountName; }
    public Long getAvailableBalance() { return availableBalance; }
    public Long getFrozenBalance() { return frozenBalance; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** 冻结金额: 从可用余额转移到冻结余额 */
    public void freeze(Long amount) {
        if (availableBalance < amount) {
            throw new IllegalStateException("Insufficient available balance for freeze");
        }
        this.availableBalance -= amount;
        this.frozenBalance += amount;
        this.updatedAt = Instant.now();
    }

    /** 解冻金额: 从冻结余额转回可用余额 */
    public void unfreeze(Long amount) {
        if (frozenBalance < amount) {
            throw new IllegalStateException("Insufficient frozen balance for unfreeze");
        }
        this.frozenBalance -= amount;
        this.availableBalance += amount;
        this.updatedAt = Instant.now();
    }

    /** 扣款: 从冻结余额中扣除(已冻结的资金) */
    public void debit(Long amount) {
        if (frozenBalance < amount) {
            throw new IllegalStateException("Insufficient frozen balance for debit");
        }
        this.frozenBalance -= amount;
        this.updatedAt = Instant.now();
    }

    /** 入账: 增加可用余额 */
    public void credit(Long amount) {
        this.availableBalance += amount;
        this.updatedAt = Instant.now();
    }

    /** 退款扣除: 从可用余额中扣除(用于转账逆向补偿) */
    public void deductAvailable(Long amount) {
        if (availableBalance < amount) {
            throw new IllegalStateException("Insufficient available balance for deduction");
        }
        this.availableBalance -= amount;
        this.updatedAt = Instant.now();
    }
}
