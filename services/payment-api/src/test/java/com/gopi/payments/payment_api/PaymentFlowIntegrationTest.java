package com.gopi.payments.payment_api;

import static org.assertj.core.api.Assertions.assertThat;

import com.gopi.payments.payment_api.api.CreatePaymentResponse;
import com.gopi.payments.payment_api.domain.Payment;
import com.gopi.payments.payment_api.domain.PaymentStatus;
import com.gopi.payments.payment_api.events.PaymentInitiatedEvent;
import com.gopi.payments.payment_api.repo.PaymentRepository;
import com.gopi.payments.payment_api.service.PaymentService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * Today's behavior end to end, over real HTTP, PostgreSQL and Kafka: POST /payments stores the payment and publishes
 * payment.initiated. Same configuration as {@link PaymentApiApplicationTests}, so both share one context.
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

    @Test
    void createStoresThePaymentAndPublishesPaymentInitiated() {
        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(PaymentService.TOPIC_PAYMENT_INITIATED));

            CreatePaymentResponse response = http().post().uri("/payments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("amount", new BigDecimal("12.34"), "currency", "USD"))
                    .retrieve()
                    .toEntity(CreatePaymentResponse.class)
                    .getBody();

            assertThat(response.status()).isEqualTo("CREATED");
            Payment stored = payments.findById(response.paymentId()).orElseThrow();
            assertThat(stored.getAmount()).isEqualByComparingTo("12.34");
            assertThat(stored.getCurrency()).isEqualTo("USD");
            assertThat(stored.getStatus()).isEqualTo(PaymentStatus.CREATED);

            ConsumerRecord<String, String> record = awaitRecord(consumer, response.paymentId());
            PaymentInitiatedEvent event = objectMapper.readValue(record.value(), PaymentInitiatedEvent.class);
            assertThat(event.paymentId()).isEqualTo(response.paymentId());
            assertThat(event.amount()).isEqualByComparingTo("12.34");
            assertThat(event.currency()).isEqualTo("USD");
        }
    }

    @Test
    void invalidRequestIsRejectedAndNothingIsStored() {
        long before = payments.count();

        HttpStatusCode status = http().post().uri("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("amount", new BigDecimal("-1"), "currency", ""))
                .exchange((request, response) -> response.getStatusCode());

        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(payments.count()).isEqualTo(before);
    }

    private RestClient http() {
        return RestClient.create("http://localhost:" + port);
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
