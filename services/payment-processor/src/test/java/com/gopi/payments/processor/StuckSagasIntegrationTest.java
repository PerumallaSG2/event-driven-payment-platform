package com.gopi.payments.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * {@code GET /internal/reconciliation/stuck-sagas}: each rule just past its limit is reported, just inside it is
 * not, and a finished saga never is. The sagas are written with SQL (the sweep is off in these tests); other tests
 * share the table, so only this test's payment ids are looked at.
 */
class StuckSagasIntegrationTest extends SagaTestSupport {

    @LocalServerPort int port;

    @Test
    void onlySagasPastTheirLimitAreReported() {
        Instant now = Instant.now();
        UUID holdLate = saga("HOLD_REQUESTED", 1, now.minus(Duration.ofMinutes(2)), now);
        UUID holdInGrace = saga("HOLD_REQUESTED", 1, now.minus(Duration.ofSeconds(30)), now);
        UUID reviewLate = saga("PENDING_REVIEW", 1, now.minus(Duration.ofMinutes(2)), now);
        UUID reviewOpen = saga("PENDING_REVIEW", 1, now.plus(Duration.ofHours(1)), now);
        // fraud-check-stale-after (1 min) + grace (1 min)
        UUID fraudLate = saga("FRAUD_CHECK", 1, now.plus(Duration.ofMinutes(5)), now.minus(Duration.ofMinutes(3)));
        UUID fraudRecent = saga("FRAUD_CHECK", 1, now.plus(Duration.ofMinutes(5)), now.minus(Duration.ofSeconds(90)));
        UUID captureTen = saga("CAPTURE_REQUESTED", 10, now.minus(Duration.ofHours(1)), now);
        UUID captureNine = saga("CAPTURE_REQUESTED", 9, now.minus(Duration.ofHours(1)), now);
        UUID releaseMany = saga("RELEASE_REQUESTED", 12, now.minus(Duration.ofHours(1)), now);
        UUID completed = saga("COMPLETED", 20, now.minus(Duration.ofHours(1)), now.minus(Duration.ofHours(1)));
        UUID failed = saga("FAILED", 20, now.minus(Duration.ofHours(1)), now.minus(Duration.ofHours(1)));

        JsonNode report = RestClient.create("http://localhost:" + port).get()
                .uri("/internal/reconciliation/stuck-sagas").retrieve().body(JsonNode.class);

        Map<UUID, JsonNode> reported = new HashMap<>();
        report.get("sagas").forEach(s -> reported.put(UUID.fromString(s.get("paymentId").asString()), s));
        assertThat(report.get("truncated").booleanValue()).isFalse();
        assertThat(reported).containsKeys(holdLate, reviewLate, fraudLate, captureTen, releaseMany)
                .doesNotContainKeys(holdInGrace, reviewOpen, fraudRecent, captureNine, completed, failed);
        assertThat(reported.get(reviewLate).get("state").asString()).isEqualTo("PENDING_REVIEW");
        assertThat(reported.get(reviewLate).get("reason").asString()).isEqualTo("past its deadline");
        assertThat(reported.get(fraudLate).get("reason").asString()).isEqualTo("fraud check never finished");
        assertThat(reported.get(captureTen).get("attempts").intValue()).isEqualTo(10);
        assertThat(reported.get(captureTen).get("reason").asString())
                .isEqualTo("no answer from the ledger after 10 sends");
    }

    private UUID saga(String state, int attempts, Instant deadline, Instant updated) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sagas (payment_id, payer_account_id, merchant_account_id, amount, currency, state,
                    failure_reason, attempts, created_at, updated_at, deadline_at, version)
                VALUES (?, ?, ?, 10.00, 'USD', ?, ?, ?, ?, ?, ?, 0)
                """, id, UUID.randomUUID(), UUID.randomUUID(), state, "FAILED".equals(state) ? "HOLD_REJECTED" : null,
                attempts, Timestamp.from(updated.minus(Duration.ofHours(2))), Timestamp.from(updated),
                Timestamp.from(deadline));
        return id;
    }
}
