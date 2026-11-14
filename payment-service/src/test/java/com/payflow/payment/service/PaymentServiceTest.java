package com.payflow.payment.service;

import com.payflow.payment.audit.AuditService;
import com.payflow.payment.client.AccountClient;
import com.payflow.payment.client.LedgerClient;
import com.payflow.payment.domain.PaymentOrder;
import com.payflow.payment.domain.PaymentStatus;
import com.payflow.payment.exception.PaymentException;
import com.payflow.payment.mq.PaymentEventProducer;
import com.payflow.payment.repository.PaymentOrderRepository;
import com.payflow.payment.service.pipeline.PaymentCreationPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
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

/**
 * processPaymentAsync 的 Saga 路径。
 *
 * 仓库用一个按 @Version 校验的内存实现代替: 与 JPA 对游离实体的 merge 一样,
 * save() 返回带新版本号的副本, 用旧版本号再次保存会抛乐观锁异常。
 */
class PaymentServiceTest {

    private static final String PAYMENT_ID = "PAY_TEST_1";
    private static final String PAYER = "acc001";
    private static final String PAYEE = "acc002";
    private static final long AMOUNT = 1_000L;

    private VersionedOrderStore store;
    private AccountClient accountClient;
    private LedgerClient ledgerClient;
    private PaymentEventProducer eventProducer;
    private AuditService auditService;
    private RLock lock;
    private PaymentService service;

    @BeforeEach
    void setUp() throws Exception {
        store = new VersionedOrderStore();
        PaymentOrderRepository repository = mock(PaymentOrderRepository.class);
        when(repository.findByPaymentId(anyString())).thenAnswer(inv -> store.find(inv.getArgument(0)));
        when(repository.save(any())).thenAnswer(inv -> store.save(inv.getArgument(0)));

        accountClient = mock(AccountClient.class);
        ledgerClient = mock(LedgerClient.class);
        eventProducer = mock(PaymentEventProducer.class);
        auditService = mock(AuditService.class);

        lock = mock(RLock.class);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getLock("account:lock:" + PAYER)).thenReturn(lock);

        service = new PaymentService(repository, mock(PaymentCreationPipeline.class), accountClient,
                ledgerClient, eventProducer, redisson, auditService);

        store.insert(order(PaymentStatus.PENDING));
    }

    @Test
    void happyPathCompletesWithoutFallingIntoReconciliation() {
        service.processPaymentAsync(PAYMENT_ID);

        assertThat(store.status(PAYMENT_ID)).isEqualTo(PaymentStatus.COMPLETED);
        InOrder flow = inOrder(accountClient, ledgerClient);
        flow.verify(accountClient).freezeAmount(PAYER, AMOUNT);
        flow.verify(ledgerClient).createEntry(PAYMENT_ID, PAYER, PAYEE, AMOUNT);
        flow.verify(accountClient).transfer(PAYER, PAYEE, AMOUNT);
        verify(eventProducer, times(1)).sendPaymentCompleted(any());
        verifyAudited("RECONCILE_REQUIRED", never());
        verifyAudited("PAYMENT_COMPLETED", times(1));
        verify(lock).unlock();
    }

    @Test
    void freezeFailureMarksFailedWithNothingToCompensate() {
        doThrow(new PaymentException("SERVICE_UNAVAILABLE", "down")).when(accountClient).freezeAmount(PAYER, AMOUNT);

        service.processPaymentAsync(PAYMENT_ID);

        assertThat(store.status(PAYMENT_ID)).isEqualTo(PaymentStatus.FAILED);
        verify(ledgerClient, never()).createEntry(anyString(), anyString(), anyString(), anyLong());
        verify(ledgerClient, never()).reverseEntry(anyString(), anyString(), anyString(), anyLong());
        verify(accountClient, never()).unfreezeAmount(anyString(), anyLong());
        verify(accountClient, never()).transfer(anyString(), anyString(), anyLong());
        verify(eventProducer).sendPaymentFailed(any(), eq("down"));
    }

    @Test
    void ledgerFailureUnfreezes() {
        doThrow(new PaymentException("SERVICE_UNAVAILABLE", "ledger down"))
                .when(ledgerClient).createEntry(PAYMENT_ID, PAYER, PAYEE, AMOUNT);

        service.processPaymentAsync(PAYMENT_ID);

        assertThat(store.status(PAYMENT_ID)).isEqualTo(PaymentStatus.FAILED);
        verify(accountClient).unfreezeAmount(PAYER, AMOUNT);
        verify(ledgerClient, never()).reverseEntry(anyString(), anyString(), anyString(), anyLong());
        verify(accountClient, never()).transfer(anyString(), anyString(), anyLong());
    }

    @Test
    void transferFailureReversesLedgerThenUnfreezes() {
        doThrow(new PaymentException("SERVICE_UNAVAILABLE", "transfer down"))
                .when(accountClient).transfer(PAYER, PAYEE, AMOUNT);

        service.processPaymentAsync(PAYMENT_ID);

        assertThat(store.status(PAYMENT_ID)).isEqualTo(PaymentStatus.FAILED);
        InOrder compensation = inOrder(ledgerClient, accountClient);
        compensation.verify(ledgerClient).reverseEntry(PAYMENT_ID, PAYER, PAYEE, AMOUNT);
        compensation.verify(accountClient).unfreezeAmount(PAYER, AMOUNT);
        verify(eventProducer).sendPaymentFailed(any(), eq("transfer down"));
    }

    @Test
    void unfreezeStillRunsWhenLedgerReversalFails() {
        doThrow(new PaymentException("SERVICE_UNAVAILABLE", "transfer down"))
                .when(accountClient).transfer(PAYER, PAYEE, AMOUNT);
        doThrow(new PaymentException("SERVICE_UNAVAILABLE", "ledger down"))
                .when(ledgerClient).reverseEntry(PAYMENT_ID, PAYER, PAYEE, AMOUNT);

        service.processPaymentAsync(PAYMENT_ID);

        verify(accountClient).unfreezeAmount(PAYER, AMOUNT);
        assertThat(store.status(PAYMENT_ID)).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void failureAfterTransferNeverCompensates() {
        doThrow(new RuntimeException("kafka metadata timeout"))
                .when(eventProducer).sendPaymentCompleted(any());

        service.processPaymentAsync(PAYMENT_ID);

        assertThat(store.status(PAYMENT_ID)).isEqualTo(PaymentStatus.COMPLETED);
        verify(ledgerClient, never()).reverseEntry(anyString(), anyString(), anyString(), anyLong());
        verify(accountClient, never()).unfreezeAmount(anyString(), anyLong());
        verifyAudited("RECONCILE_REQUIRED", times(1));
    }

    @Test
    void failedCompletedSaveIsFinalizedFromAFreshRead() {
        store.failNextSaveOf(PaymentStatus.COMPLETED);

        service.processPaymentAsync(PAYMENT_ID);

        assertThat(store.status(PAYMENT_ID)).isEqualTo(PaymentStatus.COMPLETED);
        verify(accountClient, never()).unfreezeAmount(anyString(), anyLong());
        verify(ledgerClient, never()).reverseEntry(anyString(), anyString(), anyString(), anyLong());
        verifyAudited("RECONCILE_REQUIRED", times(1));
        verify(eventProducer, times(1)).sendPaymentCompleted(any());
    }

    @Test
    void busyAccountLockFailsThePendingOrder() throws Exception {
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        when(lock.isHeldByCurrentThread()).thenReturn(false);

        service.processPaymentAsync(PAYMENT_ID);

        assertThat(store.status(PAYMENT_ID)).isEqualTo(PaymentStatus.FAILED);
        verifyNoInteractions(accountClient, ledgerClient);
        verify(eventProducer).sendPaymentFailed(any(), anyString());
        verify(lock, never()).unlock();
    }

    @Test
    void redeliveredMessageForNonPendingOrderIsSkipped() {
        store.insert(order(PaymentStatus.PROCESSING));

        service.processPaymentAsync(PAYMENT_ID);

        assertThat(store.status(PAYMENT_ID)).isEqualTo(PaymentStatus.PROCESSING);
        verifyNoInteractions(accountClient, ledgerClient, eventProducer);
    }

    // --- helpers ---

    private void verifyAudited(String action, org.mockito.verification.VerificationMode mode) {
        verify(auditService, mode).log(eq(action), any(), any(), any(), any(), any());
    }

    private static PaymentOrder order(PaymentStatus status) {
        PaymentOrder order = new PaymentOrder();
        order.setPaymentId(PAYMENT_ID);
        order.setIdempotencyKey("key-1");
        order.setFromAccount(PAYER);
        order.setToAccount(PAYEE);
        order.setAmount(AMOUNT);
        order.setPaymentMethod("BANK_TRANSFER");
        order.setStatus(status);
        return order;
    }

    /** 按 @Version 做乐观锁校验的内存"表" */
    private static final class VersionedOrderStore {
        private final Map<String, PaymentOrder> rows = new HashMap<>();
        private PaymentStatus failNextSaveOf;

        void insert(PaymentOrder order) {
            PaymentOrder row = copy(order);
            ReflectionTestUtils.setField(row, "version", 0L);
            rows.put(order.getPaymentId(), row);
        }

        Optional<PaymentOrder> find(String paymentId) {
            return Optional.ofNullable(rows.get(paymentId)).map(VersionedOrderStore::copy);
        }

        PaymentOrder save(PaymentOrder order) {
            if (order.getStatus() == failNextSaveOf) {
                failNextSaveOf = null;
                throw new RuntimeException("simulated DB failure saving " + order.getStatus());
            }
            PaymentOrder current = rows.get(order.getPaymentId());
            if (!Objects.equals(current.getVersion(), order.getVersion())) {
                throw new ObjectOptimisticLockingFailureException(PaymentOrder.class, order.getPaymentId());
            }
            PaymentOrder row = copy(order);
            ReflectionTestUtils.setField(row, "version", order.getVersion() + 1);
            rows.put(order.getPaymentId(), row);
            return copy(row);
        }

        void failNextSaveOf(PaymentStatus status) {
            this.failNextSaveOf = status;
        }

        PaymentStatus status(String paymentId) {
            return rows.get(paymentId).getStatus();
        }

        private static PaymentOrder copy(PaymentOrder source) {
            PaymentOrder target = new PaymentOrder();
            for (Field field : PaymentOrder.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                try {
                    field.set(target, field.get(source));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
            return target;
        }
    }
}
