package com.payflow.payment.mq;

import com.payflow.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final PaymentService paymentService;

    public PaymentEventConsumer(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * 消费 payment.created 事件, 异步执行转账流程
     */
    @KafkaListener(topics = "payment.created", groupId = "payment-processor")
    public void onPaymentCreated(Map<String, Object> event) {
        String paymentId = (String) event.get("payment_id");
        log.info("Received payment.created event: {}", paymentId);

        try {
            paymentService.processPaymentAsync(paymentId);
        } catch (Exception e) {
            log.error("Failed to process payment {}: {}", paymentId, e.getMessage());
            // Kafka 会根据重试策略自动重试, 最终进入死信队列(DLQ)
        }
    }
}
