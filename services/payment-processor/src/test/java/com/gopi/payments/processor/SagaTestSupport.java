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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared setup for the saga tests over real Kafka and PostgreSQL. The test plays payment-api (payment.initiated and
 * payment.reviews in) and the ledger: it reads the processor's commands on ledger.commands and answers on
 * ledger.events; {@link #FRAUD} plays fraud-detection. The scheduled sweep is effectively off
 * ({@link #TEST_PROPERTIES}); tests that need it make a saga due through SQL and call {@link SagaSweep#sweep()}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = SagaTestSupport.TEST_PROPERTIES)
@Import({PostgresTestcontainer.class, KafkaTestcontainer.class})
abstract class SagaTestSupport {

    static final String TEST_PROPERTIES = "saga.sweep-interval=PT1H\nsaga.resend-after=PT10M\n"
            + "fraud.timeout=PT2S\nfraud.read-timeout=PT1S\nfraud.poll-interval=PT0.05S";
    protected static final Duration WITHIN = Duration.ofSeconds(30);

    /** fraud-detection, scripted; every test starts with it approving everything. */
    protected static final FraudStub FRAUD = FraudStub.start();

    @DynamicPropertySource
    static void fraudUrl(DynamicPropertyRegistry registry) {
        registry.add("fraud.base-url", () -> FRAUD.baseUrl().toString());
    }

    @Autowired protected KafkaContainer kafka;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected SagaSweep sweep;

    protected final UUID paymentId = UUID.randomUUID();
    protected final UUID payer = UUID.randomUUID();
    protected final UUID merchant = UUID.randomUUID();
    protected KafkaProducer<String, String> producer;
    protected KafkaConsumer<String, String> commands;
    protected KafkaConsumer<String, String> outcomes;
    protected final List<ConsumerRecord<String, String>> seen = new ArrayList<>();

    @BeforeEach
    void connect() {
        FRAUD.decides("APPROVE", 5);
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

    // --- helpers ---

    protected void initiate(UUID id) {
        initiate(UUID.randomUUID(), id);
    }

    protected void initiate(UUID eventId, UUID id) {
        send(Topics.PAYMENT_INITIATED, id, eventId, "PaymentInitiated", objectMapper.writeValueAsString(Map.ofEntries(
                Map.entry("paymentId", id), Map.entry("amount", "42.50"), Map.entry("currency", "USD"),
                Map.entry("payerAccountId", payer), Map.entry("merchantAccountId", merchant),
                Map.entry("cardId", "card-1"), Map.entry("merchant", "Corner Coffee"),
                Map.entry("merchantCategory", "RESTAURANT"), Map.entry("city", "Austin"),
                Map.entry("latitude", 30.2672), Map.entry("longitude", -97.7431),
                Map.entry("timestamp", "2026-03-14T02:30:00-05:00"))));
    }

    protected void ledgerReplies(String type, Map<String, Object> event) {
        ledgerReplies(UUID.randomUUID(), type, event);
    }

    protected void ledgerReplies(UUID eventId, String type, Map<String, Object> event) {
        send(Topics.LEDGER_EVENTS, eventId, type, objectMapper.writeValueAsString(event));
    }

    protected void send(String topic, UUID eventId, String type, String value) {
        send(topic, paymentId, eventId, type, value);
    }

    protected void send(String topic, UUID key, UUID eventId, String type, String value) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key.toString(), value);
        record.headers().add("event-id", eventId.toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("event-type", type.getBytes(StandardCharsets.UTF_8));
        producer.send(record);
        producer.flush();
    }

    /** Sagas wait since an hour ago; with {@code deadlinePassed} their hold deadline is over too. */
    protected void makeDue(boolean deadlinePassed) {
        jdbc.update("UPDATE sagas SET updated_at = now() - interval '1 hour'"
                + (deadlinePassed ? ", deadline_at = now() - interval '1 second'" : "") + " WHERE payment_id = ?",
                paymentId);
    }

    protected String state() {
        return jdbc.queryForObject("SELECT state FROM sagas WHERE payment_id = ?", String.class, paymentId);
    }

    protected int attempts() {
        return jdbc.queryForObject("SELECT attempts FROM sagas WHERE payment_id = ?", Integer.class, paymentId);
    }

    protected int commandsInOutbox(String type) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE msg_key = ? AND event_type = ?", Integer.class,
                paymentId.toString(), type);
    }

    protected int holdFundsInOutbox(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE msg_key = ? AND event_type = 'HoldFunds'",
                Integer.class, id.toString());
    }

    protected boolean inboxed(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM inbox WHERE event_id = ?", Integer.class, eventId) == 1;
    }

    protected JsonNode awaitRecord(KafkaConsumer<String, String> consumer, String type) {
        List<JsonNode> found = awaitRecords(consumer, type, 1);
        return found.get(found.size() - 1);
    }

    /** Waits until at least n records of this type for this test's payment were seen on the consumer's topic. */
    protected List<JsonNode> awaitRecords(KafkaConsumer<String, String> consumer, String type, int n) {
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

    protected List<ConsumerRecord<String, String>> awaitDead(KafkaConsumer<String, String> consumer, int n) {
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

    protected static String header(ConsumerRecord<String, String> r, String name) {
        Header h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    protected static void await(BooleanSupplier condition) {
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

    protected KafkaConsumer<String, String> consumer(String topic) {
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
