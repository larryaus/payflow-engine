package com.payflow.payment.service.pipeline;

import com.payflow.payment.controller.PaymentController.CreatePaymentRequest;
import com.payflow.payment.domain.PaymentOrder;

/**
 * 支付创建流水线上下文 — 在各 Step 之间传递共享状态
 */
public class PaymentCreationContext {

    private final String idempotencyKey;
    private final CreatePaymentRequest request;
    private PaymentOrder order;
    // 当重复请求直接返回已有订单时置为 true，后续 Step 跳过执行
    private boolean shortCircuit;

    public PaymentCreationContext(String idempotencyKey, CreatePaymentRequest request) {
        this.idempotencyKey = idempotencyKey;
        this.request = request;
    }

    public String getIdempotencyKey() { return idempotencyKey; }
    public CreatePaymentRequest getRequest() { return request; }
    public PaymentOrder getOrder() { return order; }
    public void setOrder(PaymentOrder order) { this.order = order; }
    public boolean isShortCircuit() { return shortCircuit; }
    public void shortCircuit() { this.shortCircuit = true; }
}
