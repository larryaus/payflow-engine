package com.payflow.payment.service.pipeline;

/**
 * 支付创建流水线 Step 接口
 * 每个实现类封装单一职责，通过 PaymentCreationContext 共享状态
 */
public interface PaymentCreationStep {
    void execute(PaymentCreationContext ctx);
}
