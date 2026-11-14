package com.payflow.account.domain;

import com.payflow.account.exception.InvalidAmountException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    private static Account accountWith(long available, long frozen) {
        Account account = new Account();
        if (available + frozen > 0) {
            account.credit(available + frozen);
        }
        if (frozen > 0) {
            account.freeze(frozen);
        }
        return account;
    }

    private static void assertBalances(Account account, long available, long frozen) {
        assertThat(account.getAvailableBalance()).as("available").isEqualTo(available);
        assertThat(account.getFrozenBalance()).as("frozen").isEqualTo(frozen);
    }

    @Test
    void freezeMovesAvailableToFrozen() {
        Account account = accountWith(1_000, 0);
        account.freeze(300L);
        assertBalances(account, 700, 300);
    }

    @Test
    void unfreezeMovesFrozenBackToAvailable() {
        Account account = accountWith(700, 300);
        account.unfreeze(300L);
        assertBalances(account, 1_000, 0);
    }

    @Test
    void debitTakesOnlyFromFrozen() {
        Account account = accountWith(700, 300);
        account.debit(300L);
        assertBalances(account, 700, 0);
    }

    @Test
    void creditAddsToAvailable() {
        Account account = accountWith(100, 0);
        account.credit(50L);
        assertBalances(account, 150, 0);
    }

    @Test
    void freezeRejectsMoreThanAvailable() {
        Account account = accountWith(100, 0);
        assertThatThrownBy(() -> account.freeze(101L)).isInstanceOf(IllegalStateException.class);
        assertBalances(account, 100, 0);
    }

    @Test
    void unfreezeRejectsMoreThanFrozen() {
        Account account = accountWith(500, 100);
        assertThatThrownBy(() -> account.unfreeze(101L)).isInstanceOf(IllegalStateException.class);
        assertBalances(account, 500, 100);
    }

    @Test
    void debitRejectsMoreThanFrozenEvenWhenAvailableCovers() {
        Account account = accountWith(500, 100);
        assertThatThrownBy(() -> account.debit(101L)).isInstanceOf(IllegalStateException.class);
        assertBalances(account, 500, 100);
    }

    static Stream<Arguments> nonPositiveAmounts() {
        BiConsumer<Account, Long> freeze = Account::freeze;
        BiConsumer<Account, Long> unfreeze = Account::unfreeze;
        BiConsumer<Account, Long> debit = Account::debit;
        BiConsumer<Account, Long> credit = Account::credit;
        return Stream.of(freeze, unfreeze, debit, credit)
                .flatMap(op -> Stream.of(0L, -1L, -300_000L, null)
                        .map(amount -> Arguments.of(op, amount)));
    }

    @ParameterizedTest
    @MethodSource("nonPositiveAmounts")
    void everyBalanceOperationRejectsNonPositiveAmounts(BiConsumer<Account, Long> operation, Long amount) {
        Account account = accountWith(1_000, 500);
        assertThatThrownBy(() -> operation.accept(account, amount)).isInstanceOf(InvalidAmountException.class);
        assertBalances(account, 1_000, 500);
    }

    @Test
    void freezeThenTransferConservesTotalBalance() {
        Account payer = accountWith(1_000, 0);
        Account payee = accountWith(200, 0);

        payer.freeze(400L);
        payer.debit(400L);
        payee.credit(400L);

        assertBalances(payer, 600, 0);
        assertBalances(payee, 600, 0);
        assertThat(payer.getAvailableBalance() + payer.getFrozenBalance()
                + payee.getAvailableBalance() + payee.getFrozenBalance()).isEqualTo(1_200);
    }

    @Test
    void newAccountLeavesVersionUnsetSoSaveTreatsItAsNew() {
        assertThat(ReflectionTestUtils.getField(new Account(), "version")).isNull();
    }
}
