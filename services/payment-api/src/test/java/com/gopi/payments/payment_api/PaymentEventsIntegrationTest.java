package com.gopi.payments.payment_api;

import static org.assertj.core.api.Assertions.assertThat;

import com.gopi.payments.payment_api.messaging.PaymentOutcomeService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The saga's outcome on payment.events updates the payment once, and only from CREATED. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestcontainer.class, KafkaTestcontainer.class})
class PaymentEventsIntegrationTest {

    private static final Duration WITHIN = Duration.ofSeconds(30);
    private static final String TOPIC = PaymentOutcomeService.TOPIC_PAYMENT_EVENTS;

    @LocalServerPort int port;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaContainer kafka;

    private KafkaProducer<String, String> producer;

    @BeforeEach
    void connect() {
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
    }

    @AfterEach
    void close() {
        producer.close();
    }

    @Test
    void paymentCompletedMakesThePaymentCompleted() {
        String id = createPayment();

        send(UUID.randomUUID(), id, "PaymentCompleted", "{\"paymentId\":\"" + id + "\"}");

        await(() -> "COMPLETED".equals(get(id).get("status").asString()));
        assertThat(get(id).get("failureReason").isNull()).isTrue();
    }

    @Test
    void paymentFailedMakesThePaymentFailedWithItsReason() {
        String id = createPayment();

        send(UUID.randomUUID(), id, "PaymentFailed",
                "{\"paymentId\":\"" + id + "\",\"reason\":\"INSUFFICIENT_FUNDS\"}");

        await(() -> "FAILED".equals(get(id).get("status").asString()));
        assertThat(get(id).get("failureReason").asString()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void aRedeliveredEventIsAppliedOnceAndAFinalStatusNeverChanges() {
        String id = createPayment();
        UUID eventId = UUID.randomUUID();
        String completed = "{\"paymentId\":\"" + id + "\"}";

        send(eventId, id, "PaymentCompleted", completed);
        send(eventId, id, "PaymentCompleted", completed);
        UUID lateFailure = UUID.randomUUID();
        send(lateFailure, id, "PaymentFailed", "{\"paymentId\":\"" + id + "\",\"reason\":\"HOLD_TIMEOUT\"}");

        // Same key, same partition: once the last event is in the inbox, all three were handled.
        await(() -> inboxed(lateFailure));
        assertThat(get(id).get("status").asString()).isEqualTo("COMPLETED");
        assertThat(get(id).get("failureReason").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inbox WHERE event_id = ?", Integer.class, eventId))
                .isEqualTo(1);
    }

    @Test
    void invalidEventsAndUnknownPaymentsGoToTheDlt() {
        String key = UUID.randomUUID().toString();
        try (KafkaConsumer<String, String> dlt = consumer(TOPIC + ".DLT")) {
            send(UUID.randomUUID(), key, "PaymentCompleted", "{not json");
            producer.send(new ProducerRecord<>(TOPIC, key, "{\"paymentId\":\"" + key + "\"}"));   // no headers
            send(UUID.randomUUID(), key, "PaymentRefunded", "{\"paymentId\":\"" + key + "\"}");   // unknown type
            send(UUID.randomUUID(), key, "PaymentCompleted", "{\"paymentId\":\"" + key + "\"}");  // no such payment
            producer.flush();

            List<ConsumerRecord<String, String>> dead = awaitRecords(dlt, key, 4);
            assertThat(dead).allSatisfy(r -> {
                assertThat(header(r, "kafka_dlt-original-topic")).isEqualTo(TOPIC);
                assertThat(header(r, "kafka_dlt-exception-cause-fqcn")).endsWith("InvalidMessageException");
            });
        }
        String id = createPayment();   // the consumer moved on
        send(UUID.randomUUID(), id, "PaymentCompleted", "{\"paymentId\":\"" + id + "\"}");
        await(() -> "COMPLETED".equals(get(id).get("status").asString()));
    }

    private String createPayment() {
        PaymentApi.Reply reply = api().post(UUID.randomUUID().toString(), PaymentApi.validBody());
        return reply.json(objectMapper).get("paymentId").asString();
    }

    private JsonNode get(String id) {
        return api().get("/payments/" + id).json(objectMapper);
    }

    private boolean inboxed(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM inbox WHERE event_id = ?", Integer.class, eventId) == 1;
    }

    private void send(UUID eventId, String key, String type, String value) {
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, key, value);
        record.headers().add("event-id", eventId.toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("event-type", type.getBytes(StandardCharsets.UTF_8));
        producer.send(record);
        producer.flush();
    }

    private List<ConsumerRecord<String, String>> awaitRecords(KafkaConsumer<String, String> consumer, String key,
                                                             int n) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        Instant deadline = Instant.now().plus(WITHIN);
        while (found.size() < n && Instant.now().isBefore(deadline)) {
            for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(200))) {
                if (key.equals(r.key())) {
                    found.add(r);
                }
            }
        }
        assertThat(found).hasSize(n);
        return found;
    }

    private static String header(ConsumerRecord<String, String> r, String name) {
        Header h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private static void await(BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(WITHIN);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Condition not met within " + WITHIN);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    private KafkaConsumer<String, String> consumer(String topic) {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    private PaymentApi api() {
        return new PaymentApi(port, objectMapper);
    }
}
