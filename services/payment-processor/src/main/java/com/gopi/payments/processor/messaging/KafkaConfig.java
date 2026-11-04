package com.gopi.payments.processor.messaging;

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
 * Every topic this service touches (produced, consumed, and the DLTs of the consumed ones), so they exist with
 * {@link #PARTITIONS} partitions before any listener subscribes, whichever service starts first; and consumer error
 * handling as in the other
 * services: 2 retries 500 ms apart, then {@code <topic>.DLT} (same partition); invalid records skip the retries.
 */
@Configuration(proxyBeanMethods = false)
class KafkaConfig {

    static final int PARTITIONS = 3;

    @Bean
    NewTopic ledgerCommands() {
        return topic(Topics.LEDGER_COMMANDS);
    }

    @Bean
    NewTopic paymentEvents() {
        return topic(Topics.PAYMENT_EVENTS);
    }

    @Bean
    NewTopic paymentInitiated() {
        return topic(Topics.PAYMENT_INITIATED);
    }

    @Bean
    NewTopic ledgerEvents() {
        return topic(Topics.LEDGER_EVENTS);
    }

    @Bean
    NewTopic paymentReviews() {
        return topic(Topics.PAYMENT_REVIEWS);
    }

    @Bean
    NewTopic paymentReviewsDlt() {
        return topic(Topics.PAYMENT_REVIEWS + Topics.DLT_SUFFIX);
    }

    @Bean
    NewTopic paymentInitiatedDlt() {
        return topic(Topics.PAYMENT_INITIATED + Topics.DLT_SUFFIX);
    }

    @Bean
    NewTopic ledgerEventsDlt() {
        return topic(Topics.LEDGER_EVENTS + Topics.DLT_SUFFIX);
    }

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafka) {
        // Spring Kafka 4 defaults to "<topic>-dlt"; name it explicitly, same partition as the original record.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, e) -> new TopicPartition(record.topic() + Topics.DLT_SUFFIX, record.partition()));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(500, 2));
        handler.addNotRetryableExceptions(InvalidMessageException.class);
        return handler;
    }

    private static NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(PARTITIONS).replicas(1).build();
    }
}
