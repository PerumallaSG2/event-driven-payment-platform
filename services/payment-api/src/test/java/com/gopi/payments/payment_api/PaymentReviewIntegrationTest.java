package com.gopi.payments.payment_api;

import static org.assertj.core.api.Assertions.assertThat;

import com.gopi.payments.payment_api.messaging.PaymentOutcomeService;
import com.gopi.payments.payment_api.service.PaymentReviewService;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * fraud-detection's verdict (FraudChecked on payment.events) is stored on the payment; REVIEW makes it
 * PENDING_REVIEW, and {@code POST /payments/{id}/review} hands a person's decision to the saga on payment.reviews.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestcontainer.class, KafkaTestcontainer.class})
class PaymentReviewIntegrationTest {

    private static final Duration WITHIN = Duration.ofSeconds(30);

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
    void reviewMakesThePaymentPendingReviewWithTheVerdict() {
        String id = createPayment();
        UUID fraudId = UUID.randomUUID();

        fraudChecked(id, fraudId, "REVIEW", 60);

        await(() -> "PENDING_REVIEW".equals(get(id).get("status").asString()));
        JsonNode fraud = get(id).get("fraud");
        assertThat(fraud.get("transactionId").asString()).isEqualTo(fraudId.toString());
        assertThat(fraud.get("decision").asString()).isEqualTo("REVIEW");
        assertThat(fraud.get("score").intValue()).isEqualTo(60);
        assertThat(fraud.get("triggeredRules").get(0).get("rule").asString()).isEqualTo("ODD_HOURS");
        assertThat(fraud.get("triggeredRules").get(0).get("points").intValue()).isEqualTo(25);
        assertThat(fraud.get("triggeredRules").get(0).get("reason").asString()).isEqualTo("02:30 local time");
        assertThat(get(id).get("review").isNull()).isTrue();
    }

    @Test
    void anApprovalIsPublishedOnceAndAnOppositeDecisionIsRefused() {
        String id = pendingReview();

        try (KafkaConsumer<String, String> reviews = consumer(PaymentReviewService.TOPIC_PAYMENT_REVIEWS)) {
            PaymentApi.Reply reply = review(id, "approve");
            assertThat(reply.status()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(reply.headers().getLocation()).hasToString("/payments/" + id);

            assertThat(review(id, "approve").status()).isEqualTo(HttpStatus.ACCEPTED);   // a retry
            PaymentApi.Reply opposite = review(id, "reject");
            assertThat(opposite.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(opposite.json(objectMapper).get("title").asString()).isEqualTo("Not reviewable");

            ConsumerRecord<String, String> record = awaitRecords(reviews, id, 1).get(0);
            assertThat(header(record, "event-type")).isEqualTo("PaymentReviewed");
            JsonNode event = objectMapper.readTree(record.value());
            assertThat(event.get("paymentId").asString()).isEqualTo(id);
            assertThat(event.get("approve").booleanValue()).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE msg_key = ? AND event_type = ?",
                Integer.class, id, "PaymentReviewed")).isEqualTo(1);
        JsonNode payment = get(id);
        assertThat(payment.get("status").asString()).isEqualTo("PENDING_REVIEW");   // until the saga reports
        assertThat(payment.get("review").get("decision").asString()).isEqualTo("APPROVE");
        assertThat(payment.get("review").get("reviewedAt").isNull()).isFalse();

        UUID completed = UUID.randomUUID();
        send(completed, id, "PaymentCompleted", "{\"paymentId\":\"" + id + "\"}");
        await(() -> "COMPLETED".equals(get(id).get("status").asString()));
    }

    @Test
    void aRejectionEndsFailedWhenTheSagaReportsIt() {
        String id = pendingReview();

        assertThat(review(id, "reject").status()).isEqualTo(HttpStatus.ACCEPTED);
        send(UUID.randomUUID(), id, "PaymentFailed", "{\"paymentId\":\"" + id + "\",\"reason\":\"FRAUD_REJECTED\"}");

        await(() -> "FAILED".equals(get(id).get("status").asString()));
        assertThat(get(id).get("failureReason").asString()).isEqualTo("FRAUD_REJECTED");
        assertThat(get(id).get("review").get("decision").asString()).isEqualTo("REJECT");
    }

    @Test
    void onlyAPaymentInReviewCanBeReviewed() {
        String created = createPayment();
        assertThat(review(created, "approve").status()).isEqualTo(HttpStatus.CONFLICT);

        String completed = createPayment();
        fraudChecked(completed, UUID.randomUUID(), "APPROVE", 5);
        UUID last = UUID.randomUUID();
        send(last, completed, "PaymentCompleted", "{\"paymentId\":\"" + completed + "\"}");
        await(() -> "COMPLETED".equals(get(completed).get("status").asString()));
        assertThat(review(completed, "approve").status()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(review(UUID.randomUUID().toString(), "approve").status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api().postTo("/payments/" + created + "/review", "{\"decision\":\"maybe\"}").status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(api().postTo("/payments/" + created + "/review", "{}").status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE event_type = 'PaymentReviewed'"
                + " AND msg_key IN (?, ?)", Integer.class, created, completed)).isZero();
    }

    @Test
    void anApprovedVerdictIsStoredAndTheOutcomeCompletesThePayment() {
        String id = createPayment();
        UUID fraudId = UUID.randomUUID();

        fraudChecked(id, fraudId, "APPROVE", 5);
        send(UUID.randomUUID(), id, "PaymentCompleted", "{\"paymentId\":\"" + id + "\"}");

        await(() -> "COMPLETED".equals(get(id).get("status").asString()));
        assertThat(get(id).get("fraud").get("decision").asString()).isEqualTo("APPROVE");
        assertThat(get(id).get("fraud").get("transactionId").asString()).isEqualTo(fraudId.toString());
    }

    @Test
    void aReviewThatTimedOutEndsFailed() {
        String id = pendingReview();

        send(UUID.randomUUID(), id, "PaymentFailed", "{\"paymentId\":\"" + id + "\",\"reason\":\"REVIEW_TIMEOUT\"}");

        await(() -> "FAILED".equals(get(id).get("status").asString()));
        assertThat(get(id).get("failureReason").asString()).isEqualTo("REVIEW_TIMEOUT");
        assertThat(review(id, "approve").status()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void anIncompleteVerdictGoesToTheDlt() {
        String id = createPayment();
        try (KafkaConsumer<String, String> dlt = consumer(PaymentOutcomeService.TOPIC_PAYMENT_EVENTS + ".DLT")) {
            send(UUID.randomUUID(), id, "FraudChecked", "{\"paymentId\":\"" + id + "\",\"decision\":\"MAYBE\"}");

            assertThat(awaitRecords(dlt, id, 1)).allSatisfy(r ->
                    assertThat(header(r, "kafka_dlt-exception-cause-fqcn")).endsWith("InvalidMessageException"));
        }
        assertThat(get(id).get("fraud").isNull()).isTrue();
        assertThat(get(id).get("status").asString()).isEqualTo("CREATED");
    }

    // --- helpers ---

    private String pendingReview() {
        String id = createPayment();
        fraudChecked(id, UUID.randomUUID(), "REVIEW", 60);
        await(() -> "PENDING_REVIEW".equals(get(id).get("status").asString()));
        return id;
    }

    private void fraudChecked(String id, UUID fraudId, String decision, int score) {
        send(UUID.randomUUID(), id, "FraudChecked", objectMapper.writeValueAsString(Map.of(
                "paymentId", id, "fraudTransactionId", fraudId, "decision", decision, "riskScore", score,
                "triggeredRules", List.of(Map.of("rule", "ODD_HOURS", "points", 25, "reason", "02:30 local time")))));
    }

    private PaymentApi.Reply review(String id, String decision) {
        return api().postTo("/payments/" + id + "/review", "{\"decision\":\"" + decision + "\"}");
    }

    private String createPayment() {
        PaymentApi.Reply reply = api().post(UUID.randomUUID().toString(), PaymentApi.validBody());
        return reply.json(objectMapper).get("paymentId").asString();
    }

    private JsonNode get(String id) {
        return api().get("/payments/" + id).json(objectMapper);
    }

    private void send(UUID eventId, String key, String type, String value) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(PaymentOutcomeService.TOPIC_PAYMENT_EVENTS, key, value);
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
