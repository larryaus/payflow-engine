package com.payflow.payment.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper;

    public PaymentEventConsumer(PaymentService paymentService, ObjectMapper objectMapper) {
        this.paymentService = paymentService;
        this.objectMapper = objectMapper;
    }

    /**
     * 消费 payment.created 事件, 异步执行转账流程
     */
    @KafkaListener(topics = "payment.created", groupId = "payment-processor")
    public void onPaymentCreated(String message) {
        try {
            Map<?, ?> event = objectMapper.readValue(message, Map.class);
            String paymentId = (String) event.get("payment_id");
            log.info("Received payment.created event: {}", paymentId);
            paymentService.processPaymentAsync(paymentId);
        } catch (Exception e) {
            log.error("Failed to process payment.created message, will retry: {}", e.getMessage());
            throw new RuntimeException("payment.created processing failed", e);
        }
    }
}
