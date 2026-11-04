package com.gopi.payments.processor;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;

/**
 * A real single-node Kafka (KRaft) for tests that run the whole app. Import it with
 * {@code @Import(KafkaTestcontainer.class)}. One container per Spring test context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestcontainer {

    /** Same image as infra/docker-compose.yml. */
    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer("apache/kafka:3.9.2")
                .withEnv("KAFKA_HEAP_OPTS", "-Xmx256m -Xms256m")
                .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0");
    }
}
