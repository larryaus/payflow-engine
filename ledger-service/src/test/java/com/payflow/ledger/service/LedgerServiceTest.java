package com.payflow.ledger.service;

import com.payflow.ledger.domain.LedgerEntry;
import com.payflow.ledger.repository.LedgerEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LedgerServiceTest {

    private LedgerEntryRepository repository;
    private LedgerService service;

    @BeforeEach
    void setUp() {
        repository = mock(LedgerEntryRepository.class);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new LedgerService(repository);
    }

    @Test
    void createEntryRecordsBothSides() {
        LedgerEntry entry = service.createEntry("PAY_1", "acc001", "acc002", 500L);

        assertThat(entry.getPaymentId()).isEqualTo("PAY_1");
        assertThat(entry.getDebitAccount()).isEqualTo("acc001");
        assertThat(entry.getCreditAccount()).isEqualTo("acc002");
        assertThat(entry.getAmount()).isEqualTo(500L);
        assertThat(entry.getEntryType()).isEqualTo("PAYMENT");
    }

    @Test
    void reverseEntrySwapsDebitAndCredit() {
        LedgerEntry reversal = service.reverseEntry("PAY_1", "acc001", "acc002", 500L);

        assertThat(reversal.getDebitAccount()).isEqualTo("acc002");
        assertThat(reversal.getCreditAccount()).isEqualTo("acc001");
        assertThat(reversal.getAmount()).isEqualTo(500L);
        assertThat(reversal.getEntryType()).isEqualTo("REVERSAL");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L, -300_000L})
    void rejectsNonPositiveAmounts(Long amount) {
        assertThatThrownBy(() -> service.createEntry("PAY_1", "acc001", "acc002", amount))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reverseEntry("PAY_1", "acc001", "acc002", amount))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsSameDebitAndCreditAccount() {
        assertThatThrownBy(() -> service.createEntry("PAY_1", "acc001", "acc001", 500L))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }
}
