package com.payflow.payment.service.pipeline.step;

import com.payflow.payment.client.RiskClient;
import com.payflow.payment.domain.PaymentOrder;
import com.payflow.payment.domain.PaymentStatus;
import com.payflow.payment.exception.PaymentException;
import com.payflow.payment.repository.PaymentOrderRepository;
import com.payflow.payment.service.pipeline.PaymentCreationContext;
import com.payflow.payment.service.pipeline.PaymentCreationStep;
import org.springframework.stereotype.Component;

/**
 * Step 3: 风控检查
 * 调用 risk-service，通过则将订单状态推进到 PENDING，拒绝则转为 REJECTED 并抛出异常
 */
@Component
public class RiskCheckStep implements PaymentCreationStep {

    private final RiskClient riskClient;
    private final PaymentOrderRepository paymentOrderRepository;

    public RiskCheckStep(RiskClient riskClient, PaymentOrderRepository paymentOrderRepository) {
        this.riskClient = riskClient;
        this.paymentOrderRepository = paymentOrderRepository;
    }

    @Override
    public void execute(PaymentCreationContext ctx) {
        PaymentOrder order = ctx.getOrder();
        boolean riskPass = riskClient.checkRisk(
                order.getFromAccount(), order.getToAccount(), order.getAmount());
        if (!riskPass) {
            order.transitTo(PaymentStatus.REJECTED);
            paymentOrderRepository.save(order);
            throw new PaymentException("RISK_REJECTED", "风控拒绝此交易");
        }
        order.transitTo(PaymentStatus.PENDING);
        paymentOrderRepository.save(order);
    }
}
