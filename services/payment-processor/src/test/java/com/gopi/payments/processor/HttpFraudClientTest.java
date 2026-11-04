package com.gopi.payments.processor;

import static com.gopi.payments.processor.FraudStub.ACCEPTED;
import static com.gopi.payments.processor.FraudStub.NOT_SCORED;
import static com.gopi.payments.processor.FraudStub.decided;
import static com.gopi.payments.processor.FraudStub.slow;
import static org.assertj.core.api.Assertions.assertThat;

import com.gopi.payments.processor.FraudStub.Reply;
import com.gopi.payments.processor.fraud.FraudDecision;
import com.gopi.payments.processor.fraud.FraudProperties;
import com.gopi.payments.processor.fraud.FraudRequest;
import com.gopi.payments.processor.fraud.FraudResult;
import com.gopi.payments.processor.fraud.FraudResult.Scored;
import com.gopi.payments.processor.fraud.FraudResult.TriggeredRule;
import com.gopi.payments.processor.fraud.FraudResult.Unavailable;
import com.gopi.payments.processor.fraud.HttpFraudClient;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** {@link HttpFraudClient} against {@link FraudStub}: no Spring, no Docker, no real fraud-detection. */
class HttpFraudClientTest {

    private static final Duration BUDGET = Duration.ofMillis(1500);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final FraudRequest REQUEST = new FraudRequest("card-1", new BigDecimal("42.50"), "Corner Coffee",
            "RESTAURANT", "Austin", 30.2672, -97.7431, OffsetDateTime.parse("2026-03-14T02:30:00-05:00"));

    private final FraudStub stub = FraudStub.start();

    @AfterEach
    void stop() {
        stub.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVE", "REVIEW", "BLOCK"})
    void aScoredTransactionGivesItsDecisionScoreAndRules(String decision) {
        stub.decides(decision, 55);

        Scored scored = (Scored) client().check(REQUEST);

        assertThat(scored.decision()).isEqualTo(FraudDecision.valueOf(decision));
        assertThat(scored.riskScore()).isEqualTo(55);
        assertThat(scored.rules()).containsExactly(new TriggeredRule("ODD_HOURS", 55, "02:30 local time"));
        assertThat(scored.fraudTransactionId()).isNotNull();
        assertThat(stub.postCount()).isEqualTo(1);
    }

    @Test
    void theRequestCarriesTheCardFieldsAndTheOriginalOffsetUnchanged() {
        stub.decides("APPROVE", 0);

        client().check(REQUEST);

        JsonNode sent = MAPPER.readTree(stub.postBodies().get(0));
        assertThat(sent.get("timestamp").asString()).isEqualTo("2026-03-14T02:30:00-05:00");
        assertThat(sent.get("cardId").asString()).isEqualTo("card-1");
        assertThat(sent.get("amount").decimalValue()).isEqualByComparingTo("42.50");
        assertThat(sent.get("merchant").asString()).isEqualTo("Corner Coffee");
        assertThat(sent.get("merchantCategory").asString()).isEqualTo("RESTAURANT");
        assertThat(sent.get("city").asString()).isEqualTo("Austin");
        assertThat(sent.get("latitude").doubleValue()).isEqualTo(30.2672);
        assertThat(sent.get("longitude").doubleValue()).isEqualTo(-97.7431);
        assertThat(sent.properties()).hasSize(8);
    }

    @Test
    void notScoredYetIsPolledUntilTheDecision() {
        stub.script(List.of(ACCEPTED), List.of(NOT_SCORED, NOT_SCORED, decided("APPROVE", 5)));

        FraudResult result = client().check(REQUEST);

        assertThat(result).isInstanceOf(Scored.class);
        assertThat(stub.getCount()).isEqualTo(3);
        assertThat(stub.postCount()).isEqualTo(1);
    }

    @Test
    void noDecisionWithinTheBudgetIsATimeout() {
        stub.script(List.of(ACCEPTED), List.of(slow(decided("APPROVE", 5), Duration.ofSeconds(5))));

        long start = System.nanoTime();
        FraudResult result = client().check(REQUEST);

        assertThat(cause(result)).isEqualTo(Unavailable.TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(BUDGET.plusMillis(700));
    }

    @Test
    void neverScoredIsATimeout() {
        stub.script(List.of(ACCEPTED), List.of(NOT_SCORED));

        assertThat(cause(client().check(REQUEST))).isEqualTo(Unavailable.TIMEOUT);
        assertThat(stub.getCount()).isGreaterThan(1);
    }

    @Test
    void aPostThatTimesOutIsNotSentAgain() {
        stub.script(List.of(slow(ACCEPTED, Duration.ofSeconds(5))), List.of(decided("APPROVE", 5)));

        FraudResult result = client().check(REQUEST);

        assertThat(cause(result)).isEqualTo(Unavailable.TIMEOUT);
        assertThat(stub.postCount()).isEqualTo(1);   // it may have been accepted: no duplicate in fraud-detection
        assertThat(stub.getCount()).isZero();
    }

    @Test
    void fraudDetectionDownIsDownAfterTheSubmitAttempts() {
        URI closed = stub.baseUrl();
        stub.close();

        long start = System.nanoTime();
        FraudResult result = client(closed).check(REQUEST);

        assertThat(cause(result)).isEqualTo(Unavailable.DOWN);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(BUDGET.plusMillis(700));
    }

    @Test
    void a503IsRetriedBecauseNothingWasQueued() {
        Reply unavailable = new Reply(503, "{\"status\":503}", Duration.ZERO);
        stub.script(List.of(unavailable, ACCEPTED), List.of(decided("BLOCK", 90)));

        FraudResult result = client().check(REQUEST);

        assertThat(((Scored) result).decision()).isEqualTo(FraudDecision.BLOCK);
        assertThat(stub.postCount()).isEqualTo(2);
    }

    @Test
    void only503sIsDown() {
        stub.script(List.of(new Reply(503, "{}", Duration.ZERO)), List.of(decided("APPROVE", 5)));

        assertThat(cause(client().check(REQUEST))).isEqualTo(Unavailable.DOWN);
        assertThat(stub.postCount()).isEqualTo(3);
    }

    @Test
    void aRefusedRequestIsRejectedAndNotRetried() {
        stub.script(List.of(new Reply(400, "{\"status\":400,\"error\":\"Bad Request\"}", Duration.ZERO)),
                List.of(decided("APPROVE", 5)));

        assertThat(cause(client().check(REQUEST))).isEqualTo(Unavailable.REJECTED);
        assertThat(stub.postCount()).isEqualTo(1);
    }

    @Test
    void anAcceptedResponseWithoutAnIdIsRejected() {
        stub.script(List.of(new Reply(202, "{\"status\":\"PENDING\"}", Duration.ZERO)),
                List.of(decided("APPROVE", 5)));

        assertThat(cause(client().check(REQUEST))).isEqualTo(Unavailable.REJECTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<html>oops</html>",
            "{\"id\":\"{id}\",\"riskScore\":10,\"decision\":\"MAYBE\",\"triggeredRules\":[]}",
            "{\"id\":\"{id}\",\"decision\":\"APPROVE\",\"triggeredRules\":[]}",
            "{\"id\":\"{id}\",\"riskScore\":\"high\",\"decision\":\"APPROVE\"}",
            "{\"id\":\"00000000-0000-0000-0000-000000000000\",\"riskScore\":10,\"decision\":\"APPROVE\"}",
            "{\"id\":\"{id}\",\"riskScore\":10,\"decision\":\"APPROVE\",\"triggeredRules\":{\"rule\":\"X\"}}",
            "{\"id\":\"{id}\",\"riskScore\":10,\"decision\":\"APPROVE\",\"triggeredRules\":[{\"points\":3}]}"})
    void aMalformedDecisionIsMalformed(String body) {
        stub.script(List.of(ACCEPTED), List.of(new Reply(200, body, Duration.ZERO)));

        assertThat(cause(client().check(REQUEST))).isEqualTo(Unavailable.MALFORMED);
    }

    private HttpFraudClient client() {
        return client(stub.baseUrl());
    }

    private static HttpFraudClient client(URI baseUrl) {
        return new HttpFraudClient(new FraudProperties(baseUrl, Duration.ofMillis(300), Duration.ofMillis(800),
                BUDGET, Duration.ofMillis(50), 3), MAPPER);
    }

    private static String cause(FraudResult result) {
        assertThat(result).isInstanceOf(Unavailable.class);
        return ((Unavailable) result).cause();
    }
}
