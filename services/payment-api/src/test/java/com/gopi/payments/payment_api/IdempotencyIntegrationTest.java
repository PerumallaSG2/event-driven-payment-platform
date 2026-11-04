package com.gopi.payments.payment_api;

import static org.assertj.core.api.Assertions.assertThat;

import com.gopi.payments.payment_api.repo.IdempotencyKeyRepository;
import com.gopi.payments.payment_api.service.RequestHash;
import com.gopi.payments.payment_api.api.CreatePaymentRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Idempotency-Key behavior over real HTTP and PostgreSQL. Every test uses its own random key and cardId. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestcontainer.class, KafkaTestcontainer.class})
class IdempotencyIntegrationTest {

    @LocalServerPort int port;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired IdempotencyKeyRepository keys;

    private final String key = "key-" + UUID.randomUUID();

    @Test
    void retriedRequestReplaysTheFirstResponse() {
        Map<String, Object> body = PaymentApi.validBody();

        PaymentApi.Reply first = api().post(key, body);
        PaymentApi.Reply second = api().post(key, body);

        assertThat(first.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(second.headers().getLocation()).isEqualTo(first.headers().getLocation());
        assertThat(first.headers().getFirst("Idempotent-Replayed")).isNull();
        assertThat(second.headers().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(paymentsFor(body)).isEqualTo(1);
    }

    @Test
    void sameBodyWithDifferentSpellingIsStillAReplay() {
        Map<String, Object> body = PaymentApi.validBody();
        PaymentApi.Reply first = api().post(key, body);

        // other key order, 42.5 instead of 42.50, an unknown field, extra whitespace
        Map<String, Object> rest = new java.util.LinkedHashMap<>(body);
        rest.remove("amount");
        String respelled = "{ \"unknown\": 1,  \"amount\" : 42.5 ,\n"
                + objectMapper.writeValueAsString(rest).substring(1);
        PaymentApi.Reply second = api().post(key, respelled);

        assertThat(second.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(second.headers().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(paymentsFor(body)).isEqualTo(1);
    }

    @Test
    void identicalRequestsAtTheSameMomentCreateOnePayment() throws Exception {
        Map<String, Object> body = PaymentApi.validBody();
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PaymentApi.Reply>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return api().post(key, body);
                }));
            }
            start.countDown();
            List<PaymentApi.Reply> replies = new ArrayList<>();
            for (Future<PaymentApi.Reply> f : futures) {
                replies.add(f.get());
            }

            assertThat(paymentsFor(body)).isEqualTo(1);
            String paymentId = jdbc.queryForObject("SELECT id::text FROM payments WHERE card_id = ?", String.class,
                    body.get("cardId"));
            assertThat(replies).allSatisfy(r -> {
                assertThat(r.status().value()).isIn(201, 409);
                if (r.status().value() == 201) {
                    assertThat(r.json(objectMapper).get("paymentId").asString()).isEqualTo(paymentId);
                } else {
                    assertThat(r.headers().getFirst("Retry-After")).isEqualTo("1");
                }
            });
            assertThat(replies).anySatisfy(r -> assertThat(r.status()).isEqualTo(HttpStatus.CREATED));
        }
    }

    @Test
    void reusedKeyWithADifferentBodyIs422AndTheOriginalStillReplays() {
        Map<String, Object> body = PaymentApi.validBody();
        PaymentApi.Reply first = api().post(key, body);
        Map<String, Object> other = new java.util.LinkedHashMap<>(body);
        other.put("amount", "99.99");

        PaymentApi.Reply reused = api().post(key, other);

        assertThat(reused.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(reused.headers().getContentType()).hasToString("application/problem+json");
        assertThat(reused.json(objectMapper).get("title").asString()).isEqualTo("Idempotency-Key reused");
        assertThat(paymentsFor(body)).isEqualTo(1);
        PaymentApi.Reply replay = api().post(key, body);
        assertThat(replay.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.body()).isEqualTo(first.body());
    }

    @Test
    void inProgressKeyIs409UntilItsLockGoesStale() {
        Map<String, Object> body = PaymentApi.validBody();
        insertInProgress(body, "now() + interval '1 hour'");

        PaymentApi.Reply inFlight = api().post(key, body);

        assertThat(inFlight.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(inFlight.headers().getFirst("Retry-After")).isEqualTo("1");
        assertThat(inFlight.headers().getContentType()).hasToString("application/problem+json");
        assertThat(paymentsFor(body)).isZero();

        jdbc.update("UPDATE idempotency_keys SET locked_until = now() - interval '1 second' WHERE idempotency_key = ?",
                key);
        PaymentApi.Reply reclaimed = api().post(key, body);

        assertThat(reclaimed.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(reclaimed.headers().getFirst("Idempotent-Replayed")).isNull();
        assertThat(paymentsFor(body)).isEqualTo(1);
        assertThat(keyStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void staleClaimOfADifferentRequestIsNotTakenOver() {
        Map<String, Object> body = PaymentApi.validBody();
        insertInProgress(body, "now() - interval '1 second'");
        Map<String, Object> other = new java.util.LinkedHashMap<>(body);
        other.put("city", "Dallas");

        assertThat(api().post(key, other).status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(paymentsFor(body)).isZero();
    }

    @Test
    void missingBlankOrTooLongKeyIs400() {
        Map<String, Object> body = PaymentApi.validBody();

        for (String bad : new String[] {null, "", "   ", "k".repeat(256)}) {
            PaymentApi.Reply reply = api().post(bad, body);
            assertThat(reply.status()).as("key %s", bad).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(reply.headers().getContentType()).hasToString("application/problem+json");
        }
        assertThat(api().post("k".repeat(255), body).status()).isEqualTo(HttpStatus.CREATED);
        assertThat(paymentsFor(body)).isEqualTo(1);
    }

    @Test
    void invalidBodyDoesNotUseUpTheKey() {
        Map<String, Object> invalid = PaymentApi.validBody();
        invalid.remove("cardId");
        assertThat(api().post(key, invalid).status()).isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> samePayer = PaymentApi.validBody();
        samePayer.put("merchantAccountId", samePayer.get("payerAccountId"));
        assertThat(api().post(key, samePayer).status()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(api().post(key, PaymentApi.validBody()).status()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void afterExpiryTheSameKeyCreatesANewPayment() {
        Map<String, Object> body = PaymentApi.validBody();
        String firstId = paymentId(api().post(key, body));
        expireKey(key);

        PaymentApi.Reply again = api().post(key, body);

        assertThat(again.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.headers().getFirst("Idempotent-Replayed")).isNull();
        assertThat(paymentId(again)).isNotEqualTo(firstId);
        assertThat(paymentsFor(body)).isEqualTo(2);
        assertThat(api().get("/payments/" + firstId).status()).isEqualTo(HttpStatus.OK);   // the old one stays
    }

    @Test
    void expiredKeyMayBeReusedWithADifferentBody() {
        api().post(key, PaymentApi.validBody());
        expireKey(key);

        assertThat(api().post(key, PaymentApi.validBody()).status()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void deleteExpiredRemovesOnlyExpiredKeys() {
        String liveKey = "key-" + UUID.randomUUID();
        api().post(key, PaymentApi.validBody());
        api().post(liveKey, PaymentApi.validBody());
        expireKey(key);

        assertThat(keys.deleteExpired()).isGreaterThanOrEqualTo(1);

        assertThat(keys.find(key)).isEmpty();
        assertThat(keys.find(liveKey)).isPresent();
    }

    private void insertInProgress(Map<String, Object> body, String lockedUntilSql) {
        String hash = RequestHash.of(objectMapper.convertValue(body, CreatePaymentRequest.class));
        jdbc.update("INSERT INTO idempotency_keys (idempotency_key, request_hash, status, claim_token, locked_until,"
                + " created_at, expires_at) VALUES (?, ?, 'IN_PROGRESS', ?, " + lockedUntilSql + ", now(),"
                + " now() + interval '1 day')", key, hash, UUID.randomUUID());
    }

    private void expireKey(String k) {
        jdbc.update("UPDATE idempotency_keys SET expires_at = now() - interval '1 second' WHERE idempotency_key = ?",
                k);
    }

    private String keyStatus() {
        return jdbc.queryForObject("SELECT status FROM idempotency_keys WHERE idempotency_key = ?", String.class, key);
    }

    private int paymentsFor(Map<String, Object> body) {
        return jdbc.queryForObject("SELECT count(*) FROM payments WHERE card_id = ?", Integer.class,
                body.get("cardId"));
    }

    private String paymentId(PaymentApi.Reply reply) {
        return reply.json(objectMapper).get("paymentId").asString();
    }

    private PaymentApi api() {
        return new PaymentApi(port, objectMapper);
    }
}
