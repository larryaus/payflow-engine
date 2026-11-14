package com.payflow.payment.service;

import com.payflow.payment.audit.AuditService;
import com.payflow.payment.client.AccountClient;
import com.payflow.payment.client.LedgerClient;
import com.payflow.payment.config.IdempotencyFilter;
import com.payflow.payment.controller.PaymentController.RefundRequest;
import com.payflow.payment.domain.PaymentOrder;
import com.payflow.payment.domain.PaymentStatus;
import com.payflow.payment.domain.RefundOrder;
import com.payflow.payment.domain.RefundStatus;
import com.payflow.payment.exception.PaymentException;
import com.payflow.payment.repository.PaymentOrderRepository;
import com.payflow.payment.repository.RefundOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RefundServiceTest {

    private static final String PAYMENT_ID = "PAY_TEST_1";
    private static final String PAYER = "acc001";
    private static final String PAYEE = "acc002";
    private static final String KEY = "refund-key-1";
    private static final long PAYMENT_AMOUNT = 100L;

    private static final List<RefundStatus> RESERVED = List.of(RefundStatus.PROCESSING, RefundStatus.COMPLETED);
    private static final List<RefundStatus> COMPLETED = List.of(RefundStatus.COMPLETED);

    private PaymentOrderRepository paymentRepository;
    private RefundOrderRepository refundRepository;
    private IdempotencyFilter idempotencyFilter;
    private AccountClient accountClient;
    private LedgerClient ledgerClient;
    private AuditService auditService;
    private PlatformTransactionManager transactionManager;
    private RLock lock;
    private PaymentOrder payment;
    private RefundService service;

    @BeforeEach
    void setUp() throws Exception {
        paymentRepository = mock(PaymentOrderRepository.class);
        refundRepository = mock(RefundOrderRepository.class);
        idempotencyFilter = mock(IdempotencyFilter.class);
        accountClient = mock(AccountClient.class);
        ledgerClient = mock(LedgerClient.class);
        auditService = mock(AuditService.class);
        transactionManager = mock(PlatformTransactionManager.class);

        lock = mock(RLock.class);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getLock("refund:lock:" + PAYMENT_ID)).thenReturn(lock);

        payment = new PaymentOrder();
        payment.setPaymentId(PAYMENT_ID);
        payment.setFromAccount(PAYER);
        payment.setToAccount(PAYEE);
        payment.setAmount(PAYMENT_AMOUNT);
        payment.setStatus(PaymentStatus.COMPLETED);
        when(paymentRepository.findByPaymentId(PAYMENT_ID)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        when(refundRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(idempotencyFilter.isDuplicate(KEY)).thenReturn(false);
        givenRefunds(0, 0);

        service = new RefundService(paymentRepository, refundRepository, idempotencyFilter,
                accountClient, ledgerClient, redisson, auditService, transactionManager);
    }

    /** reserved = PROCESSING + COMPLETED 之和, completed = 仅 COMPLETED 之和 (都不含本次退款) */
    private void givenRefunds(long reserved, long completed) {
        when(refundRepository.sumAmountByPaymentIdAndStatusIn(PAYMENT_ID, RESERVED)).thenReturn(reserved);
        when(refundRepository.sumAmountByPaymentIdAndStatusIn(PAYMENT_ID, COMPLETED)).thenReturn(completed);
    }

    private RefundOrder refund(long amount) {
        return service.createRefund(PAYMENT_ID, KEY, new RefundRequest(amount, "test"));
    }

    private static String errorCode(Throwable e) {
        return ((PaymentException) e).getErrorCode();
    }

    // --- validation ---

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L, -300_000L})
    void rejectsNonPositiveAmountBeforeTouchingAnything(Long amount) {
        assertThatThrownBy(() -> service.createRefund(PAYMENT_ID, KEY, new RefundRequest(amount, "neg")))
                .isInstanceOf(PaymentException.class)
                .extracting(RefundServiceTest::errorCode).isEqualTo("INVALID_AMOUNT");
        verifyNoInteractions(idempotencyFilter, accountClient, ledgerClient, refundRepository);
    }

    @Test
    void rejectsRefundsThatWouldExceedThePaymentInTotal() {
        givenRefunds(60, 60);

        assertThatThrownBy(() -> refund(60))
                .isInstanceOf(PaymentException.class)
                .extracting(RefundServiceTest::errorCode).isEqualTo("INVALID_AMOUNT");
        verifyNoInteractions(accountClient, ledgerClient);
        verify(refundRepository, never()).save(any());
    }

    @Test
    void inFlightRefundsCountAgainstTheRefundableBalance() {
        givenRefunds(100, 0); // a full refund is still PROCESSING

        assertThatThrownBy(() -> refund(1))
                .extracting(RefundServiceTest::errorCode).isEqualTo("INVALID_AMOUNT");
        verifyNoInteractions(accountClient, ledgerClient);
    }

    @Test
    void rejectsPaymentThatIsNotCompleted() {
        payment.setStatus(PaymentStatus.REFUNDED);

        assertThatThrownBy(() -> refund(10))
                .extracting(RefundServiceTest::errorCode).isEqualTo("INVALID_STATUS");
        verifyNoInteractions(accountClient, ledgerClient);
    }

    // --- idempotency key lifecycle ---

    @Test
    void releasesTheIdempotencyKeyWhenRejectedBeforeReservation() {
        givenRefunds(60, 60);

        assertThatThrownBy(() -> refund(60)).isInstanceOf(PaymentException.class);
        verify(idempotencyFilter).release(KEY);
        verify(lock).unlock();
    }

    @Test
    void releasesTheIdempotencyKeyWhenThePaymentIsBusy() throws Exception {
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        when(lock.isHeldByCurrentThread()).thenReturn(false);

        assertThatThrownBy(() -> refund(10))
                .extracting(RefundServiceTest::errorCode).isEqualTo("PAYMENT_BUSY");
        verify(idempotencyFilter).release(KEY);
    }

    @Test
    void keepsTheIdempotencyKeyOnceTheRefundIsReserved() {
        doThrow(new PaymentException("SERVICE_UNAVAILABLE", "down")).when(accountClient).freezeAmount(PAYEE, 10L);

        assertThat(refund(10).getStatus()).isEqualTo(RefundStatus.FAILED);
        verify(idempotencyFilter, never()).release(anyString());
    }

    @Test
    void duplicateKeyReturnsTheExistingRefund() {
        RefundOrder existing = new RefundOrder();
        existing.setRefundId("REF_EXISTING");
        when(idempotencyFilter.isDuplicate(KEY)).thenReturn(true);
        when(refundRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(existing));

        assertThat(refund(10)).isSameAs(existing);
        verifyNoInteractions(accountClient, ledgerClient, lock);
    }

    // --- saga ---

    @Test
    void partialRefundMovesMoneyLastAndKeepsPaymentCompleted() {
        RefundOrder result = refund(30);

        assertThat(result.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(result.getCompletedAt()).isNotNull();
        InOrder saga = inOrder(accountClient, ledgerClient);
        saga.verify(accountClient).freezeAmount(PAYEE, 30L);
        saga.verify(ledgerClient).createEntry(result.getRefundId(), PAYEE, PAYER, 30L);
        saga.verify(accountClient).transfer(PAYEE, PAYER, 30L);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void refundThatCompletesTheTotalMarksPaymentRefunded() {
        givenRefunds(60, 60);

        assertThat(refund(40).getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(paymentRepository).save(payment);
        verify(auditService).log(eq("PAYMENT_REFUNDED"), eq("PAYMENT"), eq(PAYMENT_ID), any(), any(), any());
    }

    @Test
    void ledgerFailureUnfreezesAndNeverTransfers() {
        doThrow(new PaymentException("SERVICE_UNAVAILABLE", "ledger down"))
                .when(ledgerClient).createEntry(anyString(), eq(PAYEE), eq(PAYER), eq(30L));

        assertThat(refund(30).getStatus()).isEqualTo(RefundStatus.FAILED);
        verify(accountClient).unfreezeAmount(PAYEE, 30L);
        verify(accountClient, never()).transfer(anyString(), anyString(), anyLong());
        verify(ledgerClient, never()).reverseEntry(anyString(), anyString(), anyString(), anyLong());
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    }

    @Test
    void transferFailureReversesLedgerThenUnfreezes() {
        doThrow(new PaymentException("SERVICE_UNAVAILABLE", "transfer down"))
                .when(accountClient).transfer(PAYEE, PAYER, 30L);

        RefundOrder result = refund(30);

        assertThat(result.getStatus()).isEqualTo(RefundStatus.FAILED);
        InOrder compensation = inOrder(ledgerClient, accountClient);
        compensation.verify(ledgerClient).reverseEntry(result.getRefundId(), PAYEE, PAYER, 30L);
        compensation.verify(accountClient).unfreezeAmount(PAYEE, 30L);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    }

    // --- transaction and lock boundaries ---

    @Test
    void reservationCommitsBeforeMoneyMovesAndFinalStateCommitsAfter() {
        refund(30);

        InOrder order = inOrder(transactionManager, accountClient);
        order.verify(transactionManager).commit(any());
        order.verify(accountClient).freezeAmount(PAYEE, 30L);
        order.verify(accountClient).transfer(PAYEE, PAYER, 30L);
        order.verify(transactionManager).commit(any());
    }

    @Test
    void lockIsHeldUntilTheFinalStateIsSaved() {
        refund(30);

        InOrder order = inOrder(refundRepository, lock);
        order.verify(refundRepository, times(2)).save(any());
        order.verify(lock).unlock();
    }
}
