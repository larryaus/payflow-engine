package com.payflow.payment.service;

import com.payflow.payment.client.AccountClient;
import com.payflow.payment.client.LedgerClient;
import com.payflow.payment.config.IdempotencyFilter;
import com.payflow.payment.controller.PaymentController.RefundRequest;
import com.payflow.payment.domain.PaymentOrder;
import com.payflow.payment.domain.PaymentStatus;
import com.payflow.payment.domain.RefundOrder;
import com.payflow.payment.domain.RefundStatus;
import com.payflow.payment.exception.PaymentException;
import com.payflow.payment.mq.PaymentEventProducer;
import com.payflow.payment.repository.PaymentOrderRepository;
import com.payflow.payment.repository.RefundOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final PaymentOrderRepository paymentOrderRepository;
    private final RefundOrderRepository refundOrderRepository;
    private final IdempotencyFilter idempotencyFilter;
    private final AccountClient accountClient;
    private final LedgerClient ledgerClient;
    private final PaymentEventProducer eventProducer;

    public RefundService(
            PaymentOrderRepository paymentOrderRepository,
            RefundOrderRepository refundOrderRepository,
            IdempotencyFilter idempotencyFilter,
            AccountClient accountClient,
            LedgerClient ledgerClient,
            PaymentEventProducer eventProducer) {
        this.paymentOrderRepository = paymentOrderRepository;
        this.refundOrderRepository = refundOrderRepository;
        this.idempotencyFilter = idempotencyFilter;
        this.accountClient = accountClient;
        this.ledgerClient = ledgerClient;
        this.eventProducer = eventProducer;
    }

    @Transactional
    public RefundOrder createRefund(String paymentId, String idempotencyKey, RefundRequest request) {
        if (idempotencyFilter.isDuplicate(idempotencyKey)) {
            return refundOrderRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new PaymentException("IDEMPOTENCY_CONFLICT", "Duplicate refund request"));
        }

        PaymentOrder payment = paymentOrderRepository.findByPaymentId(paymentId)
                .orElseThrow(() -> new PaymentException("NOT_FOUND", "Payment not found"));

        if (payment.getStatus() != PaymentStatus.COMPLETED) {
            throw new PaymentException("INVALID_STATUS", "Only completed payments can be refunded");
        }

        if (request.amount() > payment.getAmount()) {
            throw new PaymentException("INVALID_AMOUNT", "Refund amount exceeds payment amount");
        }

        RefundOrder refund = buildRefundOrder(paymentId, idempotencyKey, request);
        refundOrderRepository.save(refund);

        executeRefundSaga(refund, payment, request);

        refundOrderRepository.save(refund);
        return refund;
    }

    // --- Saga orchestration ---

    private void executeRefundSaga(RefundOrder refund, PaymentOrder payment, RefundRequest request) {
        if (!stepFreeze(refund, payment, request.amount())) {
            return;
        }
        if (!stepTransferAndRecord(refund, payment, request.amount())) {
            compensateUnfreeze(refund, payment, request.amount());
            return;
        }
        markCompleted(refund, payment, request.amount());
    }

    // --- Saga steps ---

    private boolean stepFreeze(RefundOrder refund, PaymentOrder payment, Long amount) {
        try {
            accountClient.freezeAmount(payment.getToAccount(), amount);
            return true;
        } catch (Exception e) {
            log.error("Refund {} freeze failed: {}", refund.getRefundId(), e.getMessage());
            refund.setStatus(RefundStatus.FAILED);
            return false;
        }
    }

    private boolean stepTransferAndRecord(RefundOrder refund, PaymentOrder payment, Long amount) {
        try {
            accountClient.transfer(payment.getToAccount(), payment.getFromAccount(), amount);
            ledgerClient.createEntry(refund.getRefundId(),
                    payment.getToAccount(), payment.getFromAccount(), amount);
            return true;
        } catch (Exception e) {
            log.error("Refund {} transfer/ledger failed, compensating: {}", refund.getRefundId(), e.getMessage());
            return false;
        }
    }

    // --- Compensation ---

    private void compensateUnfreeze(RefundOrder refund, PaymentOrder payment, Long amount) {
        try {
            accountClient.unfreezeAmount(payment.getToAccount(), amount);
        } catch (Exception e) {
            log.error("Refund {} compensation (unfreeze) failed — manual intervention required: {}",
                    refund.getRefundId(), e.getMessage());
        }
        refund.setStatus(RefundStatus.FAILED);
    }

    // --- Post-saga state update ---

    private void markCompleted(RefundOrder refund, PaymentOrder payment, Long amount) {
        refund.setStatus(RefundStatus.COMPLETED);
        refund.setCompletedAt(Instant.now());

        if (amount.equals(payment.getAmount())) {
            payment.transitTo(PaymentStatus.REFUNDED);
            paymentOrderRepository.save(payment);
        }
    }

    // --- Helpers ---

    private RefundOrder buildRefundOrder(String paymentId, String idempotencyKey, RefundRequest request) {
        RefundOrder refund = new RefundOrder();
        refund.setRefundId(generateRefundId());
        refund.setPaymentId(paymentId);
        refund.setIdempotencyKey(idempotencyKey);
        refund.setAmount(request.amount());
        refund.setReason(request.reason());
        return refund;
    }

    private String generateRefundId() {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return "REF_" + date + "_" + suffix;
    }
}
