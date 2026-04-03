package com.payflow.payment.mq;

/**
 * [已弃用] payment.created 的异步处理已迁移到 Temporal Workflow (PaymentWorkflowImpl)。
 * 保留此类仅作参考，不再注册为 Spring Bean。
 *
 * 原逻辑: 消费 Kafka payment.created 事件 → 调用 PaymentService.processPaymentAsync()
 * 新逻辑: PaymentService.createPayment() 直接启动 Temporal Workflow
 */
public class PaymentEventConsumer {
}
