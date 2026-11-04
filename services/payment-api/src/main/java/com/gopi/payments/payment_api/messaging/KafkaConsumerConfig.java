package com.gopi.payments.payment_api.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Consumer error handling, as in ledger-service: a failing record is retried 2 more times, 500 ms apart, then
 * published to {@code <topic>.DLT} (same partition, exception in the headers). Invalid records skip the retries.
 */
@Configuration(proxyBeanMethods = false)
class KafkaConsumerConfig {

    static final String DLT_SUFFIX = ".DLT";

    /** Declared here too, so it exists with 3 partitions before the listener subscribes (see allow.auto.create.topics). */
    @Bean
    NewTopic paymentEvents() {
        return TopicBuilder.name(PaymentOutcomeService.TOPIC_PAYMENT_EVENTS).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic paymentEventsDlt() {
        return TopicBuilder.name(PaymentOutcomeService.TOPIC_PAYMENT_EVENTS + DLT_SUFFIX).partitions(3).replicas(1)
                .build();
    }

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafka) {
        // Spring Kafka 4 defaults to "<topic>-dlt"; name it explicitly, same partition as the original record.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, e) -> new TopicPartition(record.topic() + DLT_SUFFIX, record.partition()));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(500, 2));
        handler.addNotRetryableExceptions(InvalidMessageException.class);
        return handler;
    }
}
