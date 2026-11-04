package com.gopi.payments.payment_api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestcontainer.class, KafkaTestcontainer.class})
class PaymentQueryIntegrationTest {

    @LocalServerPort int port;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void getReturnsEveryField() {
        Map<String, Object> body = PaymentApi.validBody();
        UUID id = create(body);

        PaymentApi.Reply reply = api().get("/payments/" + id);

        assertThat(reply.status()).isEqualTo(HttpStatus.OK);
        JsonNode json = reply.json(objectMapper);
        assertThat(json.get("paymentId").asString()).isEqualTo(id.toString());
        assertThat(json.get("status").asString()).isEqualTo("CREATED");
        assertThat(json.get("failureReason").isNull()).isTrue();
        assertThat(json.get("payerAccountId").asString()).isEqualTo(body.get("payerAccountId"));
        assertThat(json.get("merchantAccountId").asString()).isEqualTo(body.get("merchantAccountId"));
        assertThat(json.get("amount").decimalValue()).isEqualByComparingTo("42.50");
        assertThat(json.get("currency").asString()).isEqualTo("USD");
        assertThat(json.get("cardId").asString()).isEqualTo(body.get("cardId"));
        assertThat(json.get("merchant").asString()).isEqualTo("Corner Coffee");
        assertThat(json.get("merchantCategory").asString()).isEqualTo("RESTAURANT");
        assertThat(json.get("city").asString()).isEqualTo("Austin");
        assertThat(json.get("latitude").doubleValue()).isEqualTo(30.2672);
        assertThat(json.get("longitude").doubleValue()).isEqualTo(-97.7431);
        assertThat(json.get("timestamp").asString()).isEqualTo("2026-03-14T02:30:00-05:00");
        assertThat(json.get("createdAt").asString()).isNotBlank();
    }

    /**
     * fraud-detection must receive the timestamp unchanged (its odd-hours rule reads the local time), so GET returns
     * the instant and the offset that POST received, not the instant normalized to UTC.
     */
    @ParameterizedTest
    @ValueSource(strings = {"2026-03-14T02:30:00+05:30", "2026-03-14T23:15:45-08:00", "2026-03-14T03:00:00Z",
            "2026-03-14T01:02:03+14:00"})
    void getReturnsTheTimestampWithTheOffsetPostReceived(String timestamp) {
        Map<String, Object> body = PaymentApi.validBody();
        body.put("timestamp", timestamp);
        UUID id = create(body);

        String returned = api().get("/payments/" + id).json(objectMapper).get("timestamp").asString();

        OffsetDateTime sent = OffsetDateTime.parse(timestamp);
        OffsetDateTime got = OffsetDateTime.parse(returned);
        assertThat(got).isEqualTo(sent);   // OffsetDateTime.equals compares the local time and the offset
        assertThat(got.getOffset()).isEqualTo(sent.getOffset());
        assertThat(got.toLocalTime()).isEqualTo(sent.toLocalTime());
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT transaction_time, transaction_offset_seconds FROM payments WHERE id = ?", id);
        assertThat(row.get("transaction_offset_seconds")).isEqualTo(sent.getOffset().getTotalSeconds());
        assertThat(((java.sql.Timestamp) row.get("transaction_time")).toInstant()).isEqualTo(sent.toInstant());
    }

    @Test
    void unknownIdIs404ProblemDetail() {
        PaymentApi.Reply reply = api().get("/payments/" + UUID.randomUUID());

        assertThat(reply.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(reply.headers().getContentType()).hasToString("application/problem+json");
        assertThat(reply.json(objectMapper).get("title").asString()).isEqualTo("Not found");
    }

    @Test
    void malformedIdIs400() {
        PaymentApi.Reply reply = api().get("/payments/not-a-uuid");

        assertThat(reply.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reply.headers().getContentType()).hasToString("application/problem+json");
    }

    private UUID create(Map<String, Object> body) {
        PaymentApi.Reply reply = api().post(UUID.randomUUID().toString(), body);
        assertThat(reply.status()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(reply.json(objectMapper).get("paymentId").asString());
    }

    private PaymentApi api() {
        return new PaymentApi(port, objectMapper);
    }
}
