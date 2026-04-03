package com.payflow.payment.config;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

    /**
     * Retries up to 3 times with a 1s delay, then publishes to the dead-letter
     * topic (<original-topic>.DLT) so no message is silently dropped.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (ConsumerRecord<?, ?> record, Exception ex) ->
                        new TopicPartition(record.topic() + ".DLT", record.partition())
        );

        // 3 retries, 1 000 ms apart; after the 3rd failure the recoverer sends to DLT
        FixedBackOff backOff = new FixedBackOff(1_000L, 3L);
        return new DefaultErrorHandler(recoverer, backOff);
    }
}
