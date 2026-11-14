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
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 退款流程
 *
 * 并发: 整个流程持有 refund:lock:{paymentId}, 最终状态提交之后才释放 — 同一笔支付的退款串行执行。
 *
 * 事务边界 (远程调用不在数据库事务内):
 *   1. reserve: 校验支付状态与可退余额 (原金额 − PROCESSING/COMPLETED 退款之和), 写入 PROCESSING 退款单并提交
 *   2. Saga:    freeze(原收款方) → ledger → transfer, 与支付流程同序; 转账放在最后, 资金移动后不再有会触发补偿的步骤
 *   3. finish:  写入 COMPLETED/FAILED; 已完成退款累计等于原金额时, 支付单 COMPLETED → REFUNDED
 *
 * 幂等键在退款单落库后才与之绑定; 落库前的任何失败都会释放该键, 客户端可以用同一个 key 重试。
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private static final String REFUND_LOCK_PREFIX = "refund:lock:";
    private static final List<RefundStatus> RESERVED_STATUSES =
            List.of(RefundStatus.PROCESSING, RefundStatus.COMPLETED);
    private static final List<RefundStatus> COMPLETED_STATUSES = List.of(RefundStatus.COMPLETED);

    private final PaymentOrderRepository paymentOrderRepository;
    private final RefundOrderRepository refundOrderRepository;
    private final IdempotencyFilter idempotencyFilter;
    private final AccountClient accountClient;
    private final LedgerClient ledgerClient;
    private final RedissonClient redissonClient;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;

    public RefundService(
            PaymentOrderRepository paymentOrderRepository,
            RefundOrderRepository refundOrderRepository,
            IdempotencyFilter idempotencyFilter,
            AccountClient accountClient,
            LedgerClient ledgerClient,
            RedissonClient redissonClient,
            AuditService auditService,
            PlatformTransactionManager transactionManager) {
        this.paymentOrderRepository = paymentOrderRepository;
        this.refundOrderRepository = refundOrderRepository;
        this.idempotencyFilter = idempotencyFilter;
        this.accountClient = accountClient;
        this.ledgerClient = ledgerClient;
        this.redissonClient = redissonClient;
        this.auditService = auditService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public RefundOrder createRefund(String paymentId, String idempotencyKey, RefundRequest request) {
        if (request == null || request.amount() == null || request.amount() <= 0) {
            throw new PaymentException("INVALID_AMOUNT", "Refund amount must be a positive number of cents");
        }

        if (idempotencyFilter.isDuplicate(idempotencyKey)) {
            return refundOrderRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new PaymentException("IDEMPOTENCY_CONFLICT", "Duplicate refund request"));
        }

        RLock lock = redissonClient.getLock(REFUND_LOCK_PREFIX + paymentId);
        boolean reserved = false;
        try {
            if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                throw new PaymentException("PAYMENT_BUSY", "Payment is being processed, please retry");
            }

            Reservation reservation = transactionTemplate.execute(
                    status -> reserve(paymentId, idempotencyKey, request.amount(), request.reason()));
            reserved = true;

            RefundStatus outcome = executeRefundSaga(reservation.refund(), reservation.payment());

            Result result = transactionTemplate.execute(
                    status -> finish(reservation.refund(), reservation.payment().getAmount(), outcome));
            if (result.paymentRefunded()) {
                auditService.log("PAYMENT_REFUNDED", "PAYMENT", paymentId,
                        "fully refunded by " + reservation.refund().getRefundId(), "SUCCESS", null);
            }
            return result.refund();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaymentException("PAYMENT_BUSY", "Lock interrupted, please retry");
        } finally {
            if (!reserved) {
                idempotencyFilter.release(idempotencyKey);
            }
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public List<RefundOrder> listRefunds(String paymentId) {
        return refundOrderRepository.findByPaymentId(paymentId);
    }

    // --- Step 1: reservation (local transaction) ---

    private Reservation reserve(String paymentId, String idempotencyKey, Long amount, String reason) {
        PaymentOrder payment = paymentOrderRepository.findByPaymentId(paymentId)
                .orElseThrow(() -> new PaymentException("NOT_FOUND", "Payment not found"));

        if (payment.getStatus() != PaymentStatus.COMPLETED) {
            throw new PaymentException("INVALID_STATUS", "Only completed payments can be refunded");
        }

        long refundable = payment.getAmount()
                - refundOrderRepository.sumAmountByPaymentIdAndStatusIn(paymentId, RESERVED_STATUSES);
        if (amount > refundable) {
            throw new PaymentException("INVALID_AMOUNT",
                    "Refund amount exceeds refundable balance (refundable=" + refundable + ")");
        }

        RefundOrder refund = refundOrderRepository.save(buildRefundOrder(paymentId, idempotencyKey, amount, reason));
        return new Reservation(refund, payment);
    }

    // --- Step 2: Saga (no DB transaction) ---

    private RefundStatus executeRefundSaga(RefundOrder refund, PaymentOrder payment) {
        String refundId = refund.getRefundId();
        String payee = payment.getToAccount();   // 原收款方: 退款时付出
        String payer = payment.getFromAccount(); // 原付款方: 退款时收回
        Long amount = refund.getAmount();

        boolean froze = false;
        boolean ledgerCreated = false;
        try {
            accountClient.freezeAmount(payee, amount);
            froze = true;
            auditService.log("FREEZE_AMOUNT", "REFUND", refundId,
                    "account=" + payee + " amount=" + amount, "SUCCESS", null);

            ledgerClient.createEntry(refundId, payee, payer, amount);
            ledgerCreated = true;
            auditService.log("CREATE_LEDGER_ENTRY", "REFUND", refundId,
                    "debit=" + payee + " credit=" + payer, "SUCCESS", null);

            accountClient.transfer(payee, payer, amount);
            auditService.log("TRANSFER", "REFUND", refundId,
                    "from=" + payee + " to=" + payer + " amount=" + amount, "SUCCESS", null);
            return RefundStatus.COMPLETED;
        } catch (Exception e) {
            log.error("Refund {} failed before the transfer completed, compensating: {}", refundId, e.getMessage());
            compensate(refundId, payee, payer, amount, froze, ledgerCreated);
            return RefundStatus.FAILED;
        }
    }

    /** 逆序补偿, 每步独立 try-catch, 一步失败不影响下一步 */
    private void compensate(String refundId, String payee, String payer, Long amount,
                            boolean froze, boolean ledgerCreated) {
        auditService.log("COMPENSATION_START", "REFUND", refundId,
                "froze=" + froze + " ledgerCreated=" + ledgerCreated, "INFO", null);
        if (ledgerCreated) {
            try {
                ledgerClient.reverseEntry(refundId, payee, payer, amount);
                auditService.log("REVERSE_LEDGER", "REFUND", refundId, null, "SUCCESS", null);
            } catch (Exception ex) {
                log.error("Refund {} compensation: ledger reversal failed — manual intervention required: {}",
                        refundId, ex.getMessage());
                auditService.log("REVERSE_LEDGER", "REFUND", refundId, ex.getMessage(), "FAILED", null);
            }
        }
        if (froze) {
            try {
                accountClient.unfreezeAmount(payee, amount);
                auditService.log("UNFREEZE_AMOUNT", "REFUND", refundId, null, "SUCCESS", null);
            } catch (Exception ex) {
                log.error("Refund {} compensation: unfreeze failed — manual intervention required: {}",
                        refundId, ex.getMessage());
                auditService.log("UNFREEZE_AMOUNT", "REFUND", refundId, ex.getMessage(), "FAILED", null);
            }
        }
    }

    // --- Step 3: final state (local transaction) ---

    private Result finish(RefundOrder refund, Long paymentAmount, RefundStatus outcome) {
        boolean paymentRefunded = false;
        if (outcome == RefundStatus.COMPLETED) {
            // 该退款单在库中仍是 PROCESSING, 这里得到的是此前已完成的累计金额
            long completedBefore = refundOrderRepository.sumAmountByPaymentIdAndStatusIn(
                    refund.getPaymentId(), COMPLETED_STATUSES);
            refund.setStatus(RefundStatus.COMPLETED);
            refund.setCompletedAt(Instant.now());

            if (completedBefore + refund.getAmount() == paymentAmount) {
                PaymentOrder payment = paymentOrderRepository.findByPaymentId(refund.getPaymentId())
                        .orElseThrow(() -> new PaymentException("NOT_FOUND", "Payment not found"));
                payment.transitTo(PaymentStatus.REFUNDED);
                paymentOrderRepository.save(payment);
                paymentRefunded = true;
            }
        } else {
            refund.setStatus(RefundStatus.FAILED);
        }
        return new Result(refundOrderRepository.save(refund), paymentRefunded);
    }

    // --- Helpers ---

    private RefundOrder buildRefundOrder(String paymentId, String idempotencyKey, Long amount, String reason) {
        RefundOrder refund = new RefundOrder();
        refund.setRefundId(generateRefundId());
        refund.setPaymentId(paymentId);
        refund.setIdempotencyKey(idempotencyKey);
        refund.setAmount(amount);
        refund.setReason(reason);
        return refund;
    }

    private String generateRefundId() {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return "REF_" + date + "_" + suffix;
    }

    private record Reservation(RefundOrder refund, PaymentOrder payment) {}

    private record Result(RefundOrder refund, boolean paymentRefunded) {}
}
