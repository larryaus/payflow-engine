package com.payflow.payment.service;

import com.payflow.payment.controller.PaymentController.CreatePaymentRequest;
import com.payflow.payment.domain.PaymentOrder;
import com.payflow.payment.exception.PaymentException;
import com.payflow.payment.repository.PaymentOrderRepository;
import com.payflow.payment.service.pipeline.PaymentCreationPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentCreationPipeline paymentCreationPipeline;

    public PaymentService(
            PaymentOrderRepository paymentOrderRepository,
            PaymentCreationPipeline paymentCreationPipeline) {
        this.paymentOrderRepository = paymentOrderRepository;
        this.paymentCreationPipeline = paymentCreationPipeline;
    }

    /**
     * 创建支付订单 — 委托给 PaymentCreationPipeline 执行
     *
     * 流水线顺序: 幂等占位 → 订单持久化 → 风控检查 → 启动 Temporal Workflow
     * 冻结 → 记账 → 转账 → 通知 由 PaymentWorkflowImpl 异步编排
     */
    @Transactional
    public PaymentOrder createPayment(String idempotencyKey, CreatePaymentRequest request) {
        try {
            return paymentCreationPipeline.execute(idempotencyKey, request);
        } catch (Exception e) {
            log.error("Payment creation failed for idempotencyKey={}: {}", idempotencyKey, e.getMessage());
            throw e;
        }
    }

    public Page<PaymentOrder> listPayments(int page, int size) {
        // page param is 1-based from frontend, Spring Pageable is 0-based
        PageRequest pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return paymentOrderRepository.findAll(pageable);
    }

    public PaymentOrder getPayment(String paymentId) {
        return paymentOrderRepository.findByPaymentId(paymentId)
                .orElseThrow(() -> new PaymentException("NOT_FOUND", "Payment not found: " + paymentId));
    }
}
