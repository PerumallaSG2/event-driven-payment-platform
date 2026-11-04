package com.gopi.payments.ledger.messaging;

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
 * Topics and consumer error handling. Boot applies the {@link DefaultErrorHandler} bean to the listener container:
 * a failing record is retried {@code 2} more times, 500 ms apart, then published to {@code <topic>.DLT} (same
 * partition, with the exception in the headers) and skipped. Invalid records go to the DLT on the first failure.
 */
@Configuration(proxyBeanMethods = false)
class KafkaConfig {

    static final int PARTITIONS = 3;
    static final String DLT_SUFFIX = ".DLT";

    @Bean
    NewTopic ledgerCommands() {
        return TopicBuilder.name(LedgerCommandHandler.TOPIC_COMMANDS).partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    NewTopic ledgerCommandsDlt() {
        return TopicBuilder.name(LedgerCommandHandler.TOPIC_COMMANDS + DLT_SUFFIX).partitions(PARTITIONS).replicas(1)
                .build();
    }

    @Bean
    NewTopic ledgerEvents() {
        return TopicBuilder.name(LedgerCommandHandler.TOPIC_EVENTS).partitions(PARTITIONS).replicas(1).build();
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
