package com.gopi.payments.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.gopi.payments.ledger.domain.AccountKind;
import com.gopi.payments.ledger.messaging.LedgerCommandHandler;
import com.gopi.payments.ledger.service.AccountService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * The ledger's Kafka interface over real Kafka and PostgreSQL: commands in on ledger.commands, replies out on
 * ledger.events through the outbox. Each test uses its own currency and payment ids.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestcontainer.class, KafkaTestcontainer.class})
class LedgerCommandsIntegrationTest {

    private static final Duration WITHIN = Duration.ofSeconds(30);

    @Autowired KafkaContainer kafka;
    @Autowired AccountService accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    private final String currency = TestCurrencies.next();
    private final UUID paymentId = UUID.randomUUID();
    private KafkaProducer<String, String> producer;
    private KafkaConsumer<String, String> events;
    private final List<ConsumerRecord<String, String>> seen = new ArrayList<>();

    @BeforeEach
    void connect() {
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
        events = consumer(LedgerCommandHandler.TOPIC_EVENTS);
    }

    @AfterEach
    void close() {
        producer.close();
        events.close();
    }

    @Test
    void holdThenCaptureOverKafka() {
        UUID payer = customerWith("100.00");
        UUID merchant = merchant();

        send("HoldFunds", holdFunds(payer, merchant, "30.00"));
        JsonNode held = awaitReply("FundsHeld");
        assertThat(held.get("holdId").asString()).isNotBlank();
        assertBalance(payer, "70.00");

        send("CaptureHold", Map.of("paymentId", paymentId));
        JsonNode captured = awaitReply("HoldCaptured");
        assertThat(captured.get("holdId").asString()).isEqualTo(held.get("holdId").asString());
        assertBalance(payer, "70.00");
        assertBalance(merchant, "30.00");
        assertThat(heldPaymentHolds()).isEqualTo(1);
    }

    @Test
    void aRedeliveredCommandIsHandledOnce() {
        UUID payer = customerWith("100.00");
        UUID merchant = merchant();
        UUID eventId = UUID.randomUUID();

        send(eventId, "HoldFunds", holdFunds(payer, merchant, "30.00"));
        send(eventId, "HoldFunds", holdFunds(payer, merchant, "30.00"));
        send("CaptureHold", Map.of("paymentId", paymentId));   // same partition: handled after both copies
        awaitReply("HoldCaptured");

        assertThat(replies("FundsHeld")).hasSize(1);
        assertThat(heldPaymentHolds()).isEqualTo(1);
        assertBalance(payer, "70.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inbox WHERE event_id = ?", Integer.class, eventId))
                .isEqualTo(1);
    }

    @Test
    void aResentCommandWithANewEventIdReplaysTheSameHold() {
        UUID payer = customerWith("100.00");
        UUID merchant = merchant();

        send("HoldFunds", holdFunds(payer, merchant, "30.00"));
        String first = awaitReply("FundsHeld").get("holdId").asString();
        send("HoldFunds", holdFunds(payer, merchant, "30.00"));
        awaitReplies("FundsHeld", 2);

        assertThat(replies("FundsHeld")).extracting(r -> r.get("holdId").asString()).containsOnly(first);
        assertThat(heldPaymentHolds()).isEqualTo(1);
        assertBalance(payer, "70.00");

        send("CaptureHold", Map.of("paymentId", paymentId));
        send("CaptureHold", Map.of("paymentId", paymentId));
        awaitReplies("HoldCaptured", 2);
        assertBalance(merchant, "30.00");
    }

    @Test
    void anUnderfundedHoldIsRejectedAndNothingMoves() {
        UUID payer = customerWith("10.00");
        UUID merchant = merchant();

        send("HoldFunds", holdFunds(payer, merchant, "30.00"));
        JsonNode rejected = awaitReply("HoldRejected");

        assertThat(rejected.get("reason").asString()).isEqualTo("INSUFFICIENT_FUNDS");
        assertBalance(payer, "10.00");
        assertThat(heldPaymentHolds()).isZero();
    }

    @Test
    void anUnknownAccountIsRejected() {
        send("HoldFunds", holdFunds(UUID.randomUUID(), merchant(), "1.00"));

        assertThat(awaitReply("HoldRejected").get("reason").asString()).isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    void releaseAfterCaptureIsRefusedWithTheHoldsStatus() {
        UUID payer = customerWith("100.00");
        UUID merchant = merchant();
        send("HoldFunds", holdFunds(payer, merchant, "30.00"));
        send("CaptureHold", Map.of("paymentId", paymentId));
        awaitReply("HoldCaptured");

        send("ReleaseHold", Map.of("paymentId", paymentId));
        JsonNode refused = awaitReply("SettlementRejected");

        assertThat(refused.get("command").asString()).isEqualTo("ReleaseHold");
        assertThat(refused.get("holdStatus").asString()).isEqualTo("CAPTURED");
        assertBalance(merchant, "30.00");
        assertBalance(payer, "70.00");
    }

    @Test
    void holdThenReleaseGivesTheMoneyBack() {
        UUID payer = customerWith("100.00");
        UUID merchant = merchant();
        send("HoldFunds", holdFunds(payer, merchant, "30.00"));
        awaitReply("FundsHeld");

        send("ReleaseHold", Map.of("paymentId", paymentId));
        assertThat(awaitReply("HoldReleased").get("holdId").asString()).isNotBlank();
        assertBalance(payer, "100.00");
    }

    @Test
    void aReleaseBeforeTheHoldVoidsThePaymentSoALateHoldIsRejected() {
        UUID payer = customerWith("100.00");
        UUID merchant = merchant();

        send("ReleaseHold", Map.of("paymentId", paymentId));
        assertThat(awaitReply("HoldReleased").get("holdId").isNull()).isTrue();

        send("HoldFunds", holdFunds(payer, merchant, "30.00"));
        assertThat(awaitReply("HoldRejected").get("reason").asString()).isEqualTo("VOIDED");
        assertBalance(payer, "100.00");
        assertThat(heldPaymentHolds()).isZero();
    }

    @Test
    void captureWithoutAHoldIsRefused() {
        send("CaptureHold", Map.of("paymentId", paymentId));

        assertThat(awaitReply("SettlementRejected").get("holdStatus").asString()).isEqualTo("NO_HOLD");
    }

    @Test
    void invalidCommandsGoToTheDltAndTheNextCommandIsStillHandled() {
        try (KafkaConsumer<String, String> dlt = consumer(LedgerCommandHandler.TOPIC_COMMANDS + ".DLT")) {
            send(UUID.randomUUID(), "HoldFunds", "{not json");
            producer.send(new ProducerRecord<>(LedgerCommandHandler.TOPIC_COMMANDS, paymentId.toString(),
                    "{\"paymentId\":\"" + paymentId + "\"}"));   // no headers
            send("HoldFunds", Map.of("paymentId", paymentId));   // fields missing
            send("TransferEverything", Map.of("paymentId", paymentId));   // unknown type

            List<ConsumerRecord<String, String>> dead = awaitRecords(dlt, 4);
            assertThat(dead).allSatisfy(r -> {
                assertThat(header(r, "kafka_dlt-exception-fqcn")).isNotBlank();
                assertThat(header(r, "kafka_dlt-original-topic")).isEqualTo(LedgerCommandHandler.TOPIC_COMMANDS);
            });
            assertThat(dead).extracting(r -> header(r, "kafka_dlt-exception-cause-fqcn"))
                    .allSatisfy(cause -> assertThat(cause).endsWith("InvalidMessageException"));

            UUID payer = customerWith("100.00");
            send("HoldFunds", holdFunds(payer, merchant(), "30.00"));
            awaitReply("FundsHeld");
        }
    }

    // --- helpers ---

    private Map<String, Object> holdFunds(UUID payer, UUID merchant, String amount) {
        return Map.of("paymentId", paymentId, "payerAccountId", payer, "merchantAccountId", merchant,
                "amount", new BigDecimal(amount), "currency", currency);
    }

    private void send(String type, Object command) {
        send(UUID.randomUUID(), type, command);
    }

    private void send(UUID eventId, String type, Object command) {
        String value = command instanceof String s ? s : objectMapper.writeValueAsString(command);
        ProducerRecord<String, String> record =
                new ProducerRecord<>(LedgerCommandHandler.TOPIC_COMMANDS, paymentId.toString(), value);
        record.headers().add("event-id", eventId.toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("event-type", type.getBytes(StandardCharsets.UTF_8));
        producer.send(record);
        producer.flush();
    }

    /** The first reply of this type for this test's payment. */
    private JsonNode awaitReply(String type) {
        return awaitReplies(type, 1).get(0);
    }

    private List<JsonNode> awaitReplies(String type, int n) {
        Instant deadline = Instant.now().plus(WITHIN);
        while (replies(type).size() < n) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Fewer than " + n + " " + type + " replies for " + paymentId + " within "
                        + WITHIN + "; seen: " + seen.stream().map(r -> header(r, "event-type")).toList());
            }
            for (ConsumerRecord<String, String> r : events.poll(Duration.ofMillis(200))) {
                if (paymentId.toString().equals(r.key())) {
                    seen.add(r);
                }
            }
        }
        return replies(type);
    }

    private List<JsonNode> replies(String type) {
        return seen.stream().filter(r -> type.equals(header(r, "event-type")))
                .map(r -> objectMapper.readTree(r.value())).toList();
    }

    private List<ConsumerRecord<String, String>> awaitRecords(KafkaConsumer<String, String> consumer, int n) {
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

    private UUID customerWith(String amount) {
        UUID id = accounts.create("customer", AccountKind.CUSTOMER, currency, false).getId();
        accounts.deposit(id, new BigDecimal(amount));
        return id;
    }

    private UUID merchant() {
        return accounts.create("merchant", AccountKind.MERCHANT, currency, false).getId();
    }

    private void assertBalance(UUID account, String expected) {
        assertThat(accounts.get(account).getBalance()).isEqualByComparingTo(expected);
    }

    private int heldPaymentHolds() {
        return jdbc.queryForObject("SELECT count(*) FROM holds WHERE payment_id = ?", Integer.class, paymentId);
    }
}
