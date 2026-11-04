package com.gopi.payments.payment_api;

import static org.assertj.core.api.Assertions.assertThat;

import com.gopi.payments.payment_api.outbox.OutboxRelay;
import com.gopi.payments.payment_api.service.PaymentService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.ObjectMapper;

/** The outbox over real PostgreSQL and Kafka: written with the payment, published by the relay, survives an outage. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestcontainer.class, KafkaTestcontainer.class})
class OutboxIntegrationTest {

    private static final Duration WITHIN = Duration.ofSeconds(30);

    @LocalServerPort int port;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaContainer kafka;
    @Autowired OutboxRelay relay;

    @Test
    void thePaymentAndItsOutboxRowCommitTogetherAndTheRelayPublishesIt() {
        Map<String, Object> body = PaymentApi.validBody();
        String key = UUID.randomUUID().toString();
        String id = paymentId(api().post(key, body));
        api().post(key, body);   // replay: no second payment, so no second event

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE msg_key = ?", Integer.class, id))
                .isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT topic, event_type, payload FROM outbox WHERE msg_key = ?", id);
        assertThat(row.get("topic")).isEqualTo(PaymentService.TOPIC_PAYMENT_INITIATED);
        assertThat(row.get("event_type")).isEqualTo(PaymentService.EVENT_PAYMENT_INITIATED);
        assertThat(objectMapper.readTree((String) row.get("payload")).get("paymentId").asString()).isEqualTo(id);
        await(() -> isPublished(id));
    }

    @Test
    void aPaymentIsAcceptedWhileKafkaIsDownAndPublishedWhenItIsBack() {
        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(PaymentService.TOPIC_PAYMENT_INITIATED));
            String id;
            pauseKafka();
            try {
                PaymentApi.Reply reply = api().post(UUID.randomUUID().toString(), PaymentApi.validBody());
                assertThat(reply.status()).isEqualTo(HttpStatus.CREATED);
                id = paymentId(reply);
                sleep(Duration.ofSeconds(3));   // several relay runs fail meanwhile
                assertThat(isPublished(id)).isFalse();
            } finally {
                unpauseKafka();
            }

            await(() -> isPublished(id));
            assertThat(recordsFor(consumer, List.of(id), WITHIN).get(id)).isEqualTo(1);
        }
    }

    @Test
    void parallelRelaysPublishEveryRowExactlyOnce() throws Exception {
        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(PaymentService.TOPIC_PAYMENT_INITIATED));
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                ids.add(paymentId(api().post(UUID.randomUUID().toString(), PaymentApi.validBody())));
            }

            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
                List<Future<Integer>> runs = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    runs.add(pool.submit(() -> {
                        start.await();
                        return relay.publishBatch();
                    }));
                }
                start.countDown();
                for (Future<Integer> run : runs) {
                    run.get();
                }
            }

            await(() -> ids.stream().allMatch(this::isPublished));
            Map<String, Integer> counts = recordsFor(consumer, ids, WITHIN);
            sleep(Duration.ofSeconds(1));   // a duplicate would arrive right behind the first copy
            counts.putAll(drain(consumer, ids, counts));
            assertThat(counts).hasSize(ids.size()).allSatisfy((id, n) -> assertThat(n).as(id).isEqualTo(1));
        }
    }

    private boolean isPublished(String paymentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT published_at IS NOT NULL FROM outbox WHERE msg_key = ?", Boolean.class, paymentId));
    }

    /** Counts records per payment id until every id was seen at least once. */
    private Map<String, Integer> recordsFor(KafkaConsumer<String, String> consumer, List<String> ids, Duration max) {
        Map<String, Integer> counts = new HashMap<>();
        Instant deadline = Instant.now().plus(max);
        while (!counts.keySet().containsAll(ids) && Instant.now().isBefore(deadline)) {
            count(consumer, ids, counts);
        }
        assertThat(counts.keySet()).containsAll(ids);
        return counts;
    }

    private Map<String, Integer> drain(KafkaConsumer<String, String> consumer, List<String> ids,
                                       Map<String, Integer> counts) {
        Map<String, Integer> copy = new HashMap<>(counts);
        count(consumer, ids, copy);
        return copy;
    }

    private static void count(KafkaConsumer<String, String> consumer, List<String> ids, Map<String, Integer> counts) {
        for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(200))) {
            if (ids.contains(r.key())) {
                assertThat(new String(r.headers().lastHeader(OutboxRelay.EVENT_ID_HEADER).value(),
                        StandardCharsets.UTF_8)).isNotBlank();
                counts.merge(r.key(), 1, Integer::sum);
            }
        }
    }

    private void pauseKafka() {
        DockerClientFactory.instance().client().pauseContainerCmd(kafka.getContainerId()).exec();
    }

    private void unpauseKafka() {
        DockerClientFactory.instance().client().unpauseContainerCmd(kafka.getContainerId()).exec();
    }

    private static void await(BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(WITHIN);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Condition not met within " + WITHIN);
            }
            sleep(Duration.ofMillis(100));
        }
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private String paymentId(PaymentApi.Reply reply) {
        return reply.json(objectMapper).get("paymentId").asString();
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
}
