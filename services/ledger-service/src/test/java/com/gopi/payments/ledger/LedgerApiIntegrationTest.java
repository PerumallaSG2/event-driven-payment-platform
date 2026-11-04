package com.gopi.payments.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestcontainer.class, KafkaTestcontainer.class})
class LedgerApiIntegrationTest {

    @LocalServerPort int port;
    @Autowired ObjectMapper objectMapper;

    private final String currency = TestCurrencies.next();

    record Reply(HttpStatusCode status, String body) {}

    @Test
    void depositHoldCaptureOverHttp() {
        UUID payer = createAccount("alice", "CUSTOMER");
        UUID merchant = createAccount("shop", "MERCHANT");

        Reply deposit = post("/accounts/" + payer + "/deposits", "{\"amount\":100.00}");
        assertThat(deposit.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(field(deposit, "entryId")).isNotNull();

        Reply hold = post("/holds", holdJson(payer, merchant, "42.50"));
        assertThat(hold.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(field(hold, "status")).isEqualTo("OPEN");
        String holdId = (String) field(hold, "holdId");
        assertThat(balance(payer)).isEqualByComparingTo("57.50");

        Reply captured = post("/holds/" + holdId + "/capture", null);
        assertThat(captured.status()).isEqualTo(HttpStatus.OK);
        assertThat(field(captured, "status")).isEqualTo("CAPTURED");
        assertThat(field(get("/holds/" + holdId), "status")).isEqualTo("CAPTURED");
        assertThat(balance(payer)).isEqualByComparingTo("57.50");
        assertThat(balance(merchant)).isEqualByComparingTo("42.50");
    }

    @Test
    void errorsAreProblemDetails() {
        UUID payer = createAccount("bob", "CUSTOMER");
        UUID merchant = createAccount("shop", "MERCHANT");
        post("/accounts/" + payer + "/deposits", "{\"amount\":10.00}");

        // 400: bean validation, more than two decimals, malformed id, system account kind
        assertProblem(post("/accounts", "{\"name\":\"x\",\"kind\":\"CUSTOMER\",\"currency\":\"usd\"}"), 400);
        assertProblem(post("/accounts/" + payer + "/deposits", "{\"amount\":-1}"), 400);
        assertProblem(post("/holds", holdJson(payer, merchant, "1.005")), 400);
        assertProblem(get("/accounts/not-a-uuid"), 400);
        assertProblem(post("/accounts", "{\"name\":\"x\",\"kind\":\"HOLDS\",\"currency\":\"" + currency + "\"}"), 400);

        // 404
        assertProblem(get("/accounts/" + UUID.randomUUID()), 404);
        assertProblem(post("/holds/" + UUID.randomUUID() + "/capture", null), 404);

        // 422: insufficient funds, currency mismatch
        Reply tooMuch = post("/holds", holdJson(payer, merchant, "10.01"));
        assertProblem(tooMuch, 422);
        assertThat(field(tooMuch, "title")).isEqualTo("Insufficient funds");
        UUID foreignMerchant = createAccount("foreign", "MERCHANT", TestCurrencies.next());
        assertProblem(post("/holds", holdJson(payer, foreignMerchant, "1.00")), 422);

        // 409: settling a settled hold
        String holdId = (String) field(post("/holds", holdJson(payer, merchant, "1.00")), "holdId");
        assertThat(post("/holds/" + holdId + "/release", null).status()).isEqualTo(HttpStatus.OK);
        assertProblem(post("/holds/" + holdId + "/capture", null), 409);

        assertThat(balance(payer)).isEqualByComparingTo("10.00");
    }

    private UUID createAccount(String name, String kind) {
        return createAccount(name, kind, currency);
    }

    private UUID createAccount(String name, String kind, String accountCurrency) {
        Reply reply = post("/accounts",
                "{\"name\":\"" + name + "\",\"kind\":\"" + kind + "\",\"currency\":\"" + accountCurrency + "\"}");
        assertThat(reply.status()).as(reply.body()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) field(reply, "id"));
    }

    private String holdJson(UUID payer, UUID merchant, String amount) {
        return "{\"payerAccountId\":\"" + payer + "\",\"merchantAccountId\":\"" + merchant
                + "\",\"amount\":" + amount + ",\"currency\":\"" + currency + "\"}";
    }

    private BigDecimal balance(UUID account) {
        return new BigDecimal(field(get("/accounts/" + account), "balance").toString());
    }

    private void assertProblem(Reply reply, int status) {
        assertThat(reply.status().value()).as(reply.body()).isEqualTo(status);
        assertThat(field(reply, "status")).isEqualTo(status);
        assertThat(field(reply, "title")).isNotNull();
    }

    @SuppressWarnings("unchecked")
    private Object field(Reply reply, String name) {
        return objectMapper.readValue(reply.body(), Map.class).get(name);
    }

    private Reply post(String path, String json) {
        RestClient.RequestBodySpec request = http().post().uri(path).contentType(MediaType.APPLICATION_JSON);
        if (json != null) {
            request.body(json);
        }
        return request.exchange((req, res) -> new Reply(res.getStatusCode(),
                new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    private Reply get(String path) {
        return http().get().uri(path).exchange((req, res) -> new Reply(res.getStatusCode(),
                new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    private RestClient http() {
        return RestClient.create("http://localhost:" + port);
    }
}
