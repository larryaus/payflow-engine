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
        // 幂等检查
        if (idempotencyFilter.isDuplicate(idempotencyKey)) {
            return refundOrderRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new PaymentException("IDEMPOTENCY_CONFLICT", "Duplicate refund request"));
        }

        // 校验原支付单
        PaymentOrder payment = paymentOrderRepository.findByPaymentId(paymentId)
                .orElseThrow(() -> new PaymentException("NOT_FOUND", "Payment not found"));

        if (payment.getStatus() != PaymentStatus.COMPLETED) {
            throw new PaymentException("INVALID_STATUS", "Only completed payments can be refunded");
        }

        if (request.amount() > payment.getAmount()) {
            throw new PaymentException("INVALID_AMOUNT", "Refund amount exceeds payment amount");
        }

        // 创建退款单
        RefundOrder refund = new RefundOrder();
        refund.setRefundId(generateRefundId());
        refund.setPaymentId(paymentId);
        refund.setIdempotencyKey(idempotencyKey);
        refund.setAmount(request.amount());
        refund.setReason(request.reason());
        refundOrderRepository.save(refund);

        // 反向转账: 收款方 → 付款方 (freeze-then-transfer)
        try {
            accountClient.freezeAmount(payment.getToAccount(), request.amount());
            try {
                accountClient.transfer(payment.getToAccount(), payment.getFromAccount(), request.amount());
                ledgerClient.createEntry(refund.getRefundId(),
                        payment.getToAccount(), payment.getFromAccount(), request.amount());

                refund.setStatus(RefundStatus.COMPLETED);
                refund.setCompletedAt(Instant.now());

                // 如果全额退款, 更新原支付单状态
                if (request.amount().equals(payment.getAmount())) {
                    payment.transitTo(PaymentStatus.REFUNDED);
                    paymentOrderRepository.save(payment);
                }
            } catch (Exception e) {
                log.error("Refund {} failed after freeze, rolling back: {}", refund.getRefundId(), e.getMessage());
                accountClient.unfreezeAmount(payment.getToAccount(), request.amount());
                refund.setStatus(RefundStatus.FAILED);
            }
        } catch (Exception e) {
            log.error("Refund {} failed: {}", refund.getRefundId(), e.getMessage());
            refund.setStatus(RefundStatus.FAILED);
        }

        refundOrderRepository.save(refund);
        return refund;
    }

    private String generateRefundId() {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return "REF_" + date + "_" + suffix;
    }
}
