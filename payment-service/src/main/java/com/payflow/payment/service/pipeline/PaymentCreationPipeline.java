package com.payflow.payment.service.pipeline;

import com.payflow.payment.controller.PaymentController.CreatePaymentRequest;
import com.payflow.payment.domain.PaymentOrder;
import com.payflow.payment.service.pipeline.step.EventPublishStep;
import com.payflow.payment.service.pipeline.step.IdempotencyStep;
import com.payflow.payment.service.pipeline.step.OrderPersistStep;
import com.payflow.payment.service.pipeline.step.RiskCheckStep;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 支付创建流水线编排器
 *
 * 执行顺序:
 *   IdempotencyStep  → 幂等占位 / 重复请求短路返回
 *   OrderPersistStep → 构建并持久化订单
 *   RiskCheckStep    → 风控审核，拒绝则抛出异常
 *   EventPublishStep → 发布 payment.created 事件，触发异步处理
 *
 * 任意 Step 将 ctx.shortCircuit() 置为 true 后，后续 Step 均跳过。
 */
@Component
public class PaymentCreationPipeline {

    private final List<PaymentCreationStep> steps;

    public PaymentCreationPipeline(
            IdempotencyStep idempotencyStep,
            OrderPersistStep orderPersistStep,
            RiskCheckStep riskCheckStep,
            EventPublishStep eventPublishStep) {
        this.steps = List.of(idempotencyStep, orderPersistStep, riskCheckStep, eventPublishStep);
    }

    public PaymentOrder execute(String idempotencyKey, CreatePaymentRequest request) {
        PaymentCreationContext ctx = new PaymentCreationContext(idempotencyKey, request);
        for (PaymentCreationStep step : steps) {
            if (ctx.isShortCircuit()) break;
            step.execute(ctx);
        }
        return ctx.getOrder();
    }
}
