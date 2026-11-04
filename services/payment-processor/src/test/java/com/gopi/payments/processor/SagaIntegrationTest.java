package com.gopi.payments.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.gopi.payments.processor.messaging.Topics;
import com.gopi.payments.processor.saga.SagaSweep;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The saga over real Kafka and PostgreSQL. The test plays payment-api (payment.initiated in) and the ledger: it reads
 * the processor's commands on ledger.commands and answers on ledger.events. The scheduled sweep is effectively off
 * ({@link #TEST_PROPERTIES}); tests that need it make a saga due through SQL and call {@link SagaSweep#sweep()}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = SagaIntegrationTest.TEST_PROPERTIES)
@Import({PostgresTestcontainer.class, KafkaTestcontainer.class})
class SagaIntegrationTest {

    static final String TEST_PROPERTIES = "saga.sweep-interval=PT1H\nsaga.resend-after=PT10M";
    private static final Duration WITHIN = Duration.ofSeconds(30);

    @Autowired KafkaContainer kafka;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired SagaSweep sweep;

    private final UUID paymentId = UUID.randomUUID();
    private final UUID payer = UUID.randomUUID();
    private final UUID merchant = UUID.randomUUID();
    private KafkaProducer<String, String> producer;
    private KafkaConsumer<String, String> commands;
    private KafkaConsumer<String, String> outcomes;
    private final List<ConsumerRecord<String, String>> seen = new ArrayList<>();

    @BeforeEach
    void connect() {
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
        commands = consumer(Topics.LEDGER_COMMANDS);
        outcomes = consumer(Topics.PAYMENT_EVENTS);
    }

    @AfterEach
    void close() {
        producer.close();
        commands.close();
        outcomes.close();
    }

    @Test
    void heldThenCapturedCompletesThePayment() {
        initiate(paymentId);

        JsonNode hold = awaitRecord(commands, "HoldFunds");
        assertThat(hold.get("payerAccountId").asString()).isEqualTo(payer.toString());
        assertThat(hold.get("merchantAccountId").asString()).isEqualTo(merchant.toString());
        assertThat(hold.get("amount").decimalValue()).isEqualByComparingTo("42.50");
        assertThat(hold.get("currency").asString()).isEqualTo("USD");
        assertThat(state()).isEqualTo("HOLD_REQUESTED");

        ledgerReplies("FundsHeld", Map.of("paymentId", paymentId, "holdId", UUID.randomUUID()));
        awaitRecord(commands, "CaptureHold");
        assertThat(state()).isEqualTo("CAPTURE_REQUESTED");

        ledgerReplies("HoldCaptured", Map.of("paymentId", paymentId, "holdId", UUID.randomUUID()));
        awaitRecord(outcomes, "PaymentCompleted");
        assertThat(state()).isEqualTo("COMPLETED");
    }

    @Test
    void aRejectedHoldFailsThePaymentWithTheLedgersReason() {
        initiate(paymentId);
        awaitRecord(commands, "HoldFunds");

        ledgerReplies("HoldRejected", Map.of("paymentId", paymentId, "reason", "INSUFFICIENT_FUNDS",
                "detail", "account has 10.00"));

        JsonNode failed = awaitRecord(outcomes, "PaymentFailed");
        assertThat(failed.get("reason").asString()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(state()).isEqualTo("FAILED");
        assertThat(commandsInOutbox("ReleaseHold")).isZero();   // nothing was held, nothing to compensate
    }

    @Test
    void aRedeliveredOrRepeatedPaymentInitiatedStartsOneSaga() {
        UUID eventId = UUID.randomUUID();
        initiate(eventId, paymentId);
        initiate(eventId, paymentId);              // redelivery: same event id
        UUID other = UUID.randomUUID();
        initiate(other, paymentId);                // same payment, new event id

        await(() -> inboxed(other));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sagas WHERE payment_id = ?", Integer.class, paymentId))
                .isEqualTo(1);
        assertThat(commandsInOutbox("HoldFunds")).isEqualTo(1);
    }

    @Test
    void aDuplicateFundsHeldSendsOneCapture() {
        initiate(paymentId);
        awaitRecord(commands, "HoldFunds");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        ledgerReplies(first, "FundsHeld", Map.of("paymentId", paymentId, "holdId", UUID.randomUUID()));
        ledgerReplies(first, "FundsHeld", Map.of("paymentId", paymentId, "holdId", UUID.randomUUID()));
        ledgerReplies(second, "FundsHeld", Map.of("paymentId", paymentId, "holdId", UUID.randomUUID()));

        await(() -> inboxed(second));
        assertThat(commandsInOutbox("CaptureHold")).isEqualTo(1);
        assertThat(state()).isEqualTo("CAPTURE_REQUESTED");
    }

    @Test
    void withNoReplyTheSweepResendsTheHold() {
        initiate(paymentId);
        awaitRecord(commands, "HoldFunds");

        makeDue(false);
        sweep.sweep();

        assertThat(awaitRecords(commands, "HoldFunds", 2)).hasSize(2);
        assertThat(state()).isEqualTo("HOLD_REQUESTED");
        assertThat(attempts()).isEqualTo(2);
    }

    @Test
    void pastTheHoldDeadlineTheSagaIsCompensatedAndFails() {
        initiate(paymentId);
        awaitRecord(commands, "HoldFunds");

        makeDue(true);
        sweep.sweep();

        awaitRecord(commands, "ReleaseHold");
        assertThat(state()).isEqualTo("RELEASE_REQUESTED");

        // A late FundsHeld (the hold landed after all) is ignored: the release follows it on the same partition.
        ledgerReplies("FundsHeld", Map.of("paymentId", paymentId, "holdId", UUID.randomUUID()));
        ledgerReplies("HoldReleased", Map.of("paymentId", paymentId, "holdId", UUID.randomUUID()));

        JsonNode failed = awaitRecord(outcomes, "PaymentFailed");
        assertThat(failed.get("reason").asString()).isEqualTo("HOLD_TIMEOUT");
        assertThat(state()).isEqualTo("FAILED");
        assertThat(commandsInOutbox("CaptureHold")).isZero();
    }

    @Test
    void aStuckCaptureIsResentAndNeverReleased() {
        initiate(paymentId);
        awaitRecord(commands, "HoldFunds");
        ledgerReplies("FundsHeld", Map.of("paymentId", paymentId, "holdId", UUID.randomUUID()));
        awaitRecord(commands, "CaptureHold");

        makeDue(true);   // even with the hold deadline long gone
        sweep.sweep();

        assertThat(awaitRecords(commands, "CaptureHold", 2)).hasSize(2);
        assertThat(state()).isEqualTo("CAPTURE_REQUESTED");
        assertThat(commandsInOutbox("ReleaseHold")).isZero();

        ledgerReplies("HoldCaptured", Map.of("paymentId", paymentId, "holdId", UUID.randomUUID()));
        awaitRecord(outcomes, "PaymentCompleted");
    }

    @Test
    void aRefusedReleaseFollowsTheLedger() {
        initiate(paymentId);
        awaitRecord(commands, "HoldFunds");
        makeDue(true);
        sweep.sweep();
        awaitRecord(commands, "ReleaseHold");

        // The ledger says the hold was captured after all: the money is with the merchant, so the payment completed.
        ledgerReplies("SettlementRejected", Map.of("paymentId", paymentId, "command", "ReleaseHold",
                "holdStatus", "CAPTURED"));

        awaitRecord(outcomes, "PaymentCompleted");
        assertThat(state()).isEqualTo("COMPLETED");
    }

    @Test
    void parallelSweepsHandleEachDueSagaOnce() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            initiate(id);
        }
        await(() -> ids.stream().allMatch(id -> holdFundsInOutbox(id) == 1));
        jdbc.update("UPDATE sagas SET updated_at = now() - interval '1 hour' WHERE payment_id = ANY (?)",
                (Object) ids.toArray(new UUID[0]));

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    return sweep.sweep();
                }));
            }
            start.countDown();
            for (Future<Integer> run : runs) {
                run.get();
            }
        }

        assertThat(ids).allSatisfy(id -> {
            assertThat(holdFundsInOutbox(id)).as(id.toString()).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT attempts FROM sagas WHERE payment_id = ?", Integer.class, id))
                    .isEqualTo(2);
        });
    }

    @Test
    void invalidRecordsGoToTheDlt() {
        String key = paymentId.toString();
        try (KafkaConsumer<String, String> initiatedDlt = consumer(Topics.PAYMENT_INITIATED + Topics.DLT_SUFFIX);
             KafkaConsumer<String, String> ledgerDlt = consumer(Topics.LEDGER_EVENTS + Topics.DLT_SUFFIX)) {
            send(Topics.PAYMENT_INITIATED, UUID.randomUUID(), "PaymentInitiated", "{not json");
            send(Topics.PAYMENT_INITIATED, UUID.randomUUID(), "PaymentInitiated",
                    "{\"paymentId\":\"" + key + "\"}");   // fields missing
            producer.send(new ProducerRecord<>(Topics.LEDGER_EVENTS, key, "{}"));   // no headers
            send(Topics.LEDGER_EVENTS, UUID.randomUUID(), "HoldVanished", "{\"paymentId\":\"" + key + "\"}");
            producer.flush();

            assertThat(awaitDead(initiatedDlt, 2)).allSatisfy(r ->
                    assertThat(header(r, "kafka_dlt-exception-cause-fqcn")).endsWith("InvalidMessageException"));
            assertThat(awaitDead(ledgerDlt, 2)).allSatisfy(r ->
                    assertThat(header(r, "kafka_dlt-exception-cause-fqcn")).endsWith("InvalidMessageException"));
        }
        initiate(paymentId);   // the consumers moved on
        awaitRecord(commands, "HoldFunds");
    }

    // --- helpers ---

    private void initiate(UUID id) {
        initiate(UUID.randomUUID(), id);
    }

    private void initiate(UUID eventId, UUID id) {
        send(Topics.PAYMENT_INITIATED, id, eventId, "PaymentInitiated", objectMapper.writeValueAsString(Map.of(
                "paymentId", id, "amount", "42.50", "currency", "USD",
                "payerAccountId", payer, "merchantAccountId", merchant)));
    }

    private void ledgerReplies(String type, Map<String, Object> event) {
        ledgerReplies(UUID.randomUUID(), type, event);
    }

    private void ledgerReplies(UUID eventId, String type, Map<String, Object> event) {
        send(Topics.LEDGER_EVENTS, eventId, type, objectMapper.writeValueAsString(event));
    }

    private void send(String topic, UUID eventId, String type, String value) {
        send(topic, paymentId, eventId, type, value);
    }

    private void send(String topic, UUID key, UUID eventId, String type, String value) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key.toString(), value);
        record.headers().add("event-id", eventId.toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("event-type", type.getBytes(StandardCharsets.UTF_8));
        producer.send(record);
        producer.flush();
    }

    /** Sagas wait since an hour ago; with {@code deadlinePassed} their hold deadline is over too. */
    private void makeDue(boolean deadlinePassed) {
        jdbc.update("UPDATE sagas SET updated_at = now() - interval '1 hour'"
                + (deadlinePassed ? ", deadline_at = now() - interval '1 second'" : "") + " WHERE payment_id = ?",
                paymentId);
    }

    private String state() {
        return jdbc.queryForObject("SELECT state FROM sagas WHERE payment_id = ?", String.class, paymentId);
    }

    private int attempts() {
        return jdbc.queryForObject("SELECT attempts FROM sagas WHERE payment_id = ?", Integer.class, paymentId);
    }

    private int commandsInOutbox(String type) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE msg_key = ? AND event_type = ?", Integer.class,
                paymentId.toString(), type);
    }

    private int holdFundsInOutbox(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE msg_key = ? AND event_type = 'HoldFunds'",
                Integer.class, id.toString());
    }

    private boolean inboxed(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM inbox WHERE event_id = ?", Integer.class, eventId) == 1;
    }

    private JsonNode awaitRecord(KafkaConsumer<String, String> consumer, String type) {
        List<JsonNode> found = awaitRecords(consumer, type, 1);
        return found.get(found.size() - 1);
    }

    /** Waits until at least n records of this type for this test's payment were seen on the consumer's topic. */
    private List<JsonNode> awaitRecords(KafkaConsumer<String, String> consumer, String type, int n) {
        String topic = consumer.subscription().iterator().next();
        Instant deadline = Instant.now().plus(WITHIN);
        while (true) {
            List<JsonNode> found = seen.stream()
                    .filter(r -> r.topic().equals(topic) && type.equals(header(r, "event-type")))
                    .map(r -> objectMapper.readTree(r.value())).toList();
            if (found.size() >= n) {
                return found;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Fewer than " + n + " " + type + " on " + topic + " within " + WITHIN);
            }
            for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(200))) {
                if (paymentId.toString().equals(r.key())) {
                    seen.add(r);
                }
            }
        }
    }

    private List<ConsumerRecord<String, String>> awaitDead(KafkaConsumer<String, String> consumer, int n) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        Instant deadline = Instant.now().plus(WITHIN);
        while (found.size() < n && Instant.now().isBefore(deadline)) {
            for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(200))) {
                if (paymentId.toString().equals(r.key())) {
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
}
