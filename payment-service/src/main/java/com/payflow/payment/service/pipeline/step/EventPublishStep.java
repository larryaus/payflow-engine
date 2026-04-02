package com.payflow.payment.service.pipeline.step;

import com.payflow.payment.mq.PaymentEventProducer;
import com.payflow.payment.service.pipeline.PaymentCreationContext;
import com.payflow.payment.service.pipeline.PaymentCreationStep;
import org.springframework.stereotype.Component;

/**
 * Step 4: 事件发布
 * 向 Kafka 发布 payment.created 事件，触发异步的冻结→记账→转账→通知流程
 */
@Component
public class EventPublishStep implements PaymentCreationStep {

    private final PaymentEventProducer eventProducer;

    public EventPublishStep(PaymentEventProducer eventProducer) {
        this.eventProducer = eventProducer;
    }

    @Override
    public void execute(PaymentCreationContext ctx) {
        eventProducer.sendPaymentCreated(ctx.getOrder());
    }
}
