package com.gopi.payments.payment_api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code GET /internal/reconciliation/payments}: payment-api's own record of the final payments, for ledger-service's
 * reconciliation. Other tests share the database, so these payments get ids above {@code ffffffff-ffff-ffff-…}
 * (after every random UUID in practice) and the test pages from there.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestcontainer.class, KafkaTestcontainer.class})
class ReconciliationEndpointIntegrationTest {

    private static final String FROM = "ffffffff-ffff-ffff-0000-000000000000";

    @LocalServerPort int port;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void onlyFinalPaymentsArePagedThroughByIdWithTheirAmountAndReason() {
        UUID completed = insert("0001", "COMPLETED", "10.50", "USD", null);
        insert("0002", "CREATED", "11.00", "USD", null);
        UUID failed = insert("0003", "FAILED", "12.00", "EUR", "FRAUD_BLOCKED");
        insert("0004", "PENDING_REVIEW", "13.00", "USD", null);
        UUID last = insert("0005", "COMPLETED", "14.00", "USD", null);

        PaymentApi api = new PaymentApi(port, objectMapper);
        List<JsonNode> seen = new ArrayList<>();
        List<String> nexts = new ArrayList<>();
        String after = FROM;
        while (after != null) {
            PaymentApi.Reply reply = api.get("/internal/reconciliation/payments?limit=2&after=" + after);
            assertThat(reply.status()).isEqualTo(HttpStatus.OK);
            JsonNode page = reply.json(objectMapper);
            page.get("payments").forEach(seen::add);
            after = page.get("next").isNull() ? null : page.get("next").asString();
            nexts.add(after);
        }

        assertThat(seen).extracting(p -> p.get("id").asString())
                .containsExactly(completed.toString(), failed.toString(), last.toString());
        assertThat(nexts).containsExactly(failed.toString(), null);
        JsonNode f = seen.get(1);
        assertThat(f.get("status").asString()).isEqualTo("FAILED");
        assertThat(f.get("amount").decimalValue()).isEqualByComparingTo("12.00");
        assertThat(f.get("currency").asString()).isEqualTo("EUR");
        assertThat(f.get("failureReason").asString()).isEqualTo("FRAUD_BLOCKED");
        assertThat(seen.get(0).get("failureReason").isNull()).isTrue();
    }

    @Test
    void withoutAfterItStartsAtTheFirstPayment() {
        insert(UUID.randomUUID(), "COMPLETED", "1.00", "USD", null);

        PaymentApi.Reply reply = new PaymentApi(port, objectMapper).get("/internal/reconciliation/payments?limit=1");

        assertThat(reply.status()).isEqualTo(HttpStatus.OK);
        assertThat(reply.json(objectMapper).get("payments")).hasSize(1);
        assertThat(reply.json(objectMapper).get("next").isNull()).isFalse();
    }

    private UUID insert(String suffix, String status, String amount, String currency, String reason) {
        return insert(UUID.fromString(FROM.substring(0, 24) + "00000000" + suffix), status, amount, currency, reason);
    }

    private UUID insert(UUID id, String status, String amount, String currency, String reason) {
        jdbc.update("""
                INSERT INTO payments (id, amount, currency, status, failure_reason, created_at, payer_account_id,
                    merchant_account_id, card_id, merchant, merchant_category, city, latitude, longitude,
                    transaction_time, transaction_offset_seconds)
                VALUES (?, CAST(? AS numeric), ?, ?, ?, now(), ?, ?, 'card-1', 'Corner Coffee', 'RESTAURANT', 'Austin',
                    30.2672, -97.7431, now(), 0)
                """, id, amount, currency, status, reason, UUID.randomUUID(), UUID.randomUUID());
        return id;
    }
}
