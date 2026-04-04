package com.payflow.payment.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.payment.config.TraceFilter;
import com.payflow.payment.domain.PaymentOrder;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class PaymentEventProducer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public PaymentEventProducer(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void sendPaymentCreated(PaymentOrder order) {
        log.info("Publishing payment.created event for {}", order.getPaymentId());
        sendWithTrace("payment.created", order.getFromAccount(), toJson(Map.of(
                "payment_id", order.getPaymentId(),
                "from_account", order.getFromAccount(),
                "to_account", order.getToAccount(),
                "amount", order.getAmount(),
                "event", "CREATED"
        )));
    }

    public void sendPaymentCompleted(PaymentOrder order) {
        log.info("Publishing payment.completed event for {}", order.getPaymentId());
        sendWithTrace("payment.completed", order.getFromAccount(), toJson(Map.of(
                "payment_id", order.getPaymentId(),
                "from_account", order.getFromAccount(),
                "to_account", order.getToAccount(),
                "amount", order.getAmount(),
                "callback_url", order.getCallbackUrl() != null ? order.getCallbackUrl() : "",
                "event", "COMPLETED"
        )));
    }

    public void sendPaymentFailed(PaymentOrder order, String reason) {
        log.info("Publishing payment.failed event for {}", order.getPaymentId());
        sendWithTrace("payment.failed", order.getFromAccount(), toJson(Map.of(
                "payment_id", order.getPaymentId(),
                "reason", reason,
                "event", "FAILED"
        )));
    }

    private void sendWithTrace(String topic, String key, String value) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
        String traceId = MDC.get(TraceFilter.MDC_TRACE_KEY);
        if (traceId != null) {
            record.headers().add(TraceFilter.TRACE_HEADER, traceId.getBytes(StandardCharsets.UTF_8));
        }
        kafkaTemplate.send(record);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize Kafka event payload", e);
        }
    }
}
