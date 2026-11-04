package com.gopi.payments.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.gopi.payments.processor.messaging.Topics;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.MessageListenerContainer;

/** The app starts against real PostgreSQL and Kafka: Flyway migrates and Hibernate validates the schema. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = SagaTestSupport.TEST_PROPERTIES)
@Import({PostgresTestcontainer.class, KafkaTestcontainer.class})
class PaymentProcessorApplicationTests {

    @Autowired KafkaAdmin admin;
    @Autowired KafkaListenerEndpointRegistry listeners;

    @Test
    void contextLoads() {
    }

    /**
     * Regression: the consumed topics used to be declared only by the service producing them, so if this service
     * started first its consumer auto-created them with 1 partition, and it kept reading only partition 0 after the
     * producer added the others. Now every topic it touches exists with 3 partitions, all assigned to its listeners.
     */
    @Test
    void consumedTopicsHaveThreePartitionsAllAssigned() {
        Map<String, TopicDescription> topics = admin.describeTopics(Topics.PAYMENT_INITIATED, Topics.LEDGER_EVENTS,
                Topics.LEDGER_COMMANDS, Topics.PAYMENT_EVENTS, Topics.PAYMENT_REVIEWS);
        assertThat(topics.values()).allSatisfy(t -> assertThat(t.partitions()).as(t.name()).hasSize(3));

        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (assigned().size() < 9 && Instant.now().isBefore(deadline)) {
            Thread.onSpinWait();
        }
        assertThat(assigned()).extracting(TopicPartition::toString).containsExactlyInAnyOrder(
                "payment.initiated-0", "payment.initiated-1", "payment.initiated-2",
                "ledger.events-0", "ledger.events-1", "ledger.events-2",
                "payment.reviews-0", "payment.reviews-1", "payment.reviews-2");
    }

    private List<TopicPartition> assigned() {
        return listeners.getListenerContainers().stream()
                .map(MessageListenerContainer::getAssignedPartitions)
                .filter(p -> p != null)
                .flatMap(Collection::stream)
                .toList();
    }
}
