package com.gopi.payments.payment_api;

import static org.assertj.core.api.Assertions.assertThat;

import com.gopi.payments.payment_api.domain.Payment;
import com.gopi.payments.payment_api.domain.PaymentStatus;
import com.gopi.payments.payment_api.events.PaymentInitiatedEvent;
import com.gopi.payments.payment_api.outbox.OutboxRelay;
import com.gopi.payments.payment_api.repo.PaymentRepository;
import com.gopi.payments.payment_api.service.PaymentService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
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
 * End to end over real HTTP, PostgreSQL and Kafka: POST /payments stores the payment with every field, and the outbox
 * relay publishes payment.initiated with its event-id and event-type headers. Same configuration as {@link PaymentApiApplicationTests}, so all these tests share one context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestcontainer.class, KafkaTestcontainer.class})
class PaymentFlowIntegrationTest {

    private static final Duration PUBLISHED_WITHIN = Duration.ofSeconds(20);

    @LocalServerPort
    int port;

    @Autowired
    PaymentRepository payments;

    @Autowired
    KafkaContainer kafka;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void createStoresThePaymentAndPublishesPaymentInitiated() {
        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(PaymentService.TOPIC_PAYMENT_INITIATED));
            Map<String, Object> body = PaymentApi.validBody();
            body.put("amount", "12.34");

            PaymentApi.Reply reply = api().post(UUID.randomUUID().toString(), body);

            assertThat(reply.status()).isEqualTo(HttpStatus.CREATED);
            JsonNode json = reply.json(objectMapper);
            assertThat(json.get("status").asString()).isEqualTo("CREATED");
            UUID id = UUID.fromString(json.get("paymentId").asString());
            assertThat(reply.headers().getLocation()).hasToString("/payments/" + id);

            Payment stored = payments.findById(id).orElseThrow();
            assertThat(stored.getAmount()).isEqualByComparingTo("12.34");
            assertThat(stored.getCurrency()).isEqualTo("USD");
            assertThat(stored.getStatus()).isEqualTo(PaymentStatus.CREATED);
            assertThat(stored.getPayerAccountId()).hasToString((String) body.get("payerAccountId"));
            assertThat(stored.getMerchantAccountId()).hasToString((String) body.get("merchantAccountId"));
            assertThat(stored.getCardId()).isEqualTo(body.get("cardId"));
            assertThat(stored.getMerchant()).isEqualTo("Corner Coffee");
            assertThat(stored.getMerchantCategory()).isEqualTo("RESTAURANT");
            assertThat(stored.getCity()).isEqualTo("Austin");
            assertThat(stored.getLatitude()).isEqualTo(30.2672);
            assertThat(stored.getLongitude()).isEqualTo(-97.7431);
            assertThat(stored.getTransactionTime()).isEqualTo(OffsetDateTime.parse("2026-03-14T02:30:00-05:00"));

            ConsumerRecord<String, String> record = awaitRecord(consumer, id);
            String eventId = new String(record.headers().lastHeader(OutboxRelay.EVENT_ID_HEADER).value(),
                    StandardCharsets.UTF_8);
            assertThat(eventId).isEqualTo(jdbc.queryForObject(
                    "SELECT event_id::text FROM outbox WHERE msg_key = ?", String.class, id.toString()));
            assertThat(new String(record.headers().lastHeader(OutboxRelay.EVENT_TYPE_HEADER).value(),
                    StandardCharsets.UTF_8)).isEqualTo(PaymentService.EVENT_PAYMENT_INITIATED);
            PaymentInitiatedEvent event = objectMapper.readValue(record.value(), PaymentInitiatedEvent.class);
            assertThat(event.paymentId()).isEqualTo(id);
            assertThat(event.amount()).isEqualByComparingTo("12.34");
            assertThat(event.currency()).isEqualTo("USD");
            assertThat(event.payerAccountId()).isEqualTo(stored.getPayerAccountId());
            assertThat(event.merchantAccountId()).isEqualTo(stored.getMerchantAccountId());
            // the card/merchant fields reach the saga (and fraud-detection) unchanged, offset included
            assertThat(event.cardId()).isEqualTo(body.get("cardId"));
            assertThat(event.merchant()).isEqualTo("Corner Coffee");
            assertThat(event.merchantCategory()).isEqualTo("RESTAURANT");
            assertThat(event.city()).isEqualTo("Austin");
            assertThat(event.latitude()).isEqualTo(30.2672);
            assertThat(event.longitude()).isEqualTo(-97.7431);
            assertThat(objectMapper.readTree(record.value()).get("timestamp").asString())
                    .isEqualTo("2026-03-14T02:30:00-05:00");
        }
    }

    @Test
    void invalidRequestIsRejectedAsProblemDetailAndNothingIsStored() {
        long before = payments.count();
        Map<String, Object> body = PaymentApi.validBody();
        body.put("amount", "-1");
        body.put("currency", "usd");

        PaymentApi.Reply reply = api().post(UUID.randomUUID().toString(), body);

        assertThat(reply.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reply.headers().getContentType()).hasToString("application/problem+json");
        assertThat(reply.json(objectMapper).get("title").asString()).isNotBlank();
        assertThat(payments.count()).isEqualTo(before);
    }

    @Test
    void duplicateJsonKeyIs400ProblemDetailAndDoesNotUseUpTheKey() {
        Map<String, Object> body = PaymentApi.validBody();
        String key = UUID.randomUUID().toString();
        String json = objectMapper.writeValueAsString(body);
        String duplicateTimestamp = "{\"timestamp\":\"2026-03-14T09:00:00Z\"," + json.substring(1);
        String duplicateCity = json.replace("\"city\":\"Austin\"", "\"city\":\"Austin\",\"city\":\"Dallas\"");

        for (String duplicate : new String[] {duplicateTimestamp, duplicateCity}) {
            PaymentApi.Reply reply = api().post(key, duplicate);

            assertThat(reply.status()).as(duplicate).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(reply.headers().getContentType()).hasToString("application/problem+json");
            assertThat(reply.json(objectMapper).get("title").asString()).isNotBlank();
        }
        assertThat(api().post(key, body).status()).isEqualTo(HttpStatus.CREATED);
        assertThat(payments.findAll()).filteredOn(p -> p.getCardId().equals(body.get("cardId"))).hasSize(1);
    }

    @Test
    void payerEqualToMerchantIsRejected() {
        Map<String, Object> body = PaymentApi.validBody();
        body.put("merchantAccountId", body.get("payerAccountId"));

        PaymentApi.Reply reply = api().post(UUID.randomUUID().toString(), body);

        assertThat(reply.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reply.json(objectMapper).get("title").asString()).isEqualTo("Invalid request");
    }

    private PaymentApi api() {
        return new PaymentApi(port, objectMapper);
    }

    private KafkaConsumer<String, String> consumer() {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
    }

    /** Polls until the record keyed by this payment id arrives. */
    private ConsumerRecord<String, String> awaitRecord(KafkaConsumer<String, String> consumer, UUID paymentId) {
        Instant deadline = Instant.now().plus(PUBLISHED_WITHIN);
        while (Instant.now().isBefore(deadline)) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                if (paymentId.toString().equals(record.key())) {
                    return record;
                }
            }
        }
        throw new AssertionError("No payment.initiated record for " + paymentId + " within " + PUBLISHED_WITHIN);
    }
}
