package com.gopi.payments.processor;

import static com.gopi.payments.processor.FraudStub.ACCEPTED;
import static com.gopi.payments.processor.FraudStub.NOT_SCORED;
import static com.gopi.payments.processor.FraudStub.decided;
import static com.gopi.payments.processor.FraudStub.slow;
import static org.assertj.core.api.Assertions.assertThat;

import com.gopi.payments.processor.FraudStub.Reply;
import com.gopi.payments.processor.messaging.Topics;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The fraud check between the hold and the capture, with {@link FraudStub} as fraud-detection: APPROVE captures,
 * BLOCK releases, REVIEW waits for {@code PaymentReviewed} (or the review deadline), and no usable answer fails
 * closed. A released hold FAILS the payment once the ledger confirms the release.
 */
class FraudSagaIntegrationTest extends SagaTestSupport {

    @Test
    void approvedIsCapturedAndCompletesWithTheDecisionPublishedFirst() {
        FRAUD.decides("APPROVE", 12);
        hold();

        awaitRecord(commands, "CaptureHold");
        assertThat(state()).isEqualTo("CAPTURE_REQUESTED");
        ledgerReplies("HoldCaptured", Map.of("paymentId", paymentId, "holdId", UUID.randomUUID()));

        awaitRecord(outcomes, "PaymentCompleted");
        assertThat(eventTypes()).containsExactly("FraudChecked", "PaymentCompleted");
        JsonNode checked = awaitRecord(outcomes, "FraudChecked");
        assertThat(checked.get("decision").asString()).isEqualTo("APPROVE");
        assertThat(checked.get("riskScore").intValue()).isEqualTo(12);
        assertThat(checked.get("triggeredRules").get(0).get("rule").asString()).isEqualTo("ODD_HOURS");
        assertThat(checked.get("fraudTransactionId").asString()).isEqualTo(
                jdbc.queryForObject("SELECT fraud_transaction_id::text FROM sagas WHERE payment_id = ?",
                        String.class, paymentId));
        assertThat(state()).isEqualTo("COMPLETED");

        // fraud-detection got the payment's card fields, with the timestamp's original offset
        JsonNode sent = objectMapper.readTree(FRAUD.postBodies().get(0));
        assertThat(sent.get("timestamp").asString()).isEqualTo("2026-03-14T02:30:00-05:00");
        assertThat(sent.get("cardId").asString()).isEqualTo("card-1");
        assertThat(sent.get("merchant").asString()).isEqualTo("Corner Coffee");
        assertThat(sent.get("amount").decimalValue()).isEqualByComparingTo("42.50");
    }

    @Test
    void blockedReleasesTheHoldAndFails() {
        FRAUD.decides("BLOCK", 95);
        hold();

        awaitRecord(commands, "ReleaseHold");
        ledgerReplies("HoldReleased", Map.of("paymentId", paymentId, "holdId", UUID.randomUUID()));

        assertThat(awaitRecord(outcomes, "PaymentFailed").get("reason").asString()).isEqualTo("FRAUD_BLOCKED");
        assertThat(awaitRecord(outcomes, "FraudChecked").get("decision").asString()).isEqualTo("BLOCK");
        assertThat(state()).isEqualTo("FAILED");
        assertThat(commandsInOutbox("CaptureHold")).isZero();
    }

    @Test
    void reviewKeepsTheHoldUntilApproved() {
        FRAUD.decides("REVIEW", 60);
        hold();

        awaitRecord(outcomes, "FraudChecked");
        await(() -> "PENDING_REVIEW".equals(state()));
        assertThat(commandsInOutbox("CaptureHold") + commandsInOutbox("ReleaseHold")).isZero();

        reviewed(true);
        awaitRecord(commands, "CaptureHold");
        ledgerReplies("HoldCaptured", Map.of("paymentId", paymentId, "holdId", UUID.randomUUID()));
        awaitRecord(outcomes, "PaymentCompleted");
        assertThat(state()).isEqualTo("COMPLETED");
        assertThat(commandsInOutbox("ReleaseHold")).isZero();
    }

    @Test
    void reviewRejectedReleasesTheHoldAndFails() {
        FRAUD.decides("REVIEW", 60);
        hold();
        await(() -> "PENDING_REVIEW".equals(state()));

        UUID eventId = UUID.randomUUID();
        reviewed(eventId, false);
        reviewed(eventId, false);   // redelivered: one release
        awaitRecord(commands, "ReleaseHold");
        ledgerReplies("HoldReleased", Map.of("paymentId", paymentId, "holdId", UUID.randomUUID()));

        assertThat(awaitRecord(outcomes, "PaymentFailed").get("reason").asString()).isEqualTo("FRAUD_REJECTED");
        assertThat(commandsInOutbox("ReleaseHold")).isEqualTo(1);
        assertThat(commandsInOutbox("CaptureHold")).isZero();
    }

    @Test
    void aReviewThatNeverComesReleasesTheHoldAtTheDeadline() {
        FRAUD.decides("REVIEW", 60);
        hold();
        await(() -> "PENDING_REVIEW".equals(state()));
        Integer hours = jdbc.queryForObject("SELECT round(extract(epoch FROM deadline_at - updated_at) / 3600)::int"
                + " FROM sagas WHERE payment_id = ?", Integer.class, paymentId);
        assertThat(hours).isEqualTo(24);   // saga.review-deadline

        sweep.sweep();   // not due yet
        assertThat(state()).isEqualTo("PENDING_REVIEW");

        makeDue(true);
        sweep.sweep();
        awaitRecord(commands, "ReleaseHold");

        reviewed(true);   // too late: ignored
        ledgerReplies("HoldReleased", Map.of("paymentId", paymentId, "holdId", UUID.randomUUID()));
        assertThat(awaitRecord(outcomes, "PaymentFailed").get("reason").asString()).isEqualTo("REVIEW_TIMEOUT");
        assertThat(state()).isEqualTo("FAILED");
        assertThat(commandsInOutbox("CaptureHold")).isZero();
    }

    @Test
    void fraudDetectionDownFailsClosed() {
        FRAUD.script(List.of(new Reply(503, "{\"status\":503}", Duration.ZERO)), List.of(NOT_SCORED));
        hold();

        assertFailsClosed();
        assertThat(FRAUD.postCount()).isEqualTo(3);   // fraud.submit-attempts
    }

    @Test
    void noDecisionInTimeFailsClosed() {
        FRAUD.script(List.of(ACCEPTED), List.of(slow(decided("APPROVE", 5), Duration.ofSeconds(4))));
        hold();

        assertFailsClosed();
    }

    @Test
    void notScoredThenScoredIsPolledUntilTheDecision() {
        FRAUD.script(List.of(ACCEPTED), List.of(NOT_SCORED, NOT_SCORED, decided("APPROVE", 5)));
        hold();

        awaitRecord(commands, "CaptureHold");
        assertThat(FRAUD.getCount()).isEqualTo(3);
        assertThat(FRAUD.postCount()).isEqualTo(1);
    }

    @Test
    void aMalformedDecisionFailsClosed() {
        FRAUD.script(List.of(ACCEPTED), List.of(new Reply(200, "{\"id\":\"{id}\",\"decision\":\"MAYBE\"}",
                Duration.ZERO)));
        hold();

        assertFailsClosed();
    }

    @Test
    void aFraudCheckInterruptedByAStopFailsClosedWithoutAskingAgain() {
        initiate(paymentId);
        awaitRecord(commands, "HoldFunds");
        // what a stop between the FundsHeld commit and the fraud call leaves behind
        jdbc.update("UPDATE sagas SET state = 'FRAUD_CHECK', updated_at = now() - interval '1 hour'"
                + " WHERE payment_id = ?", paymentId);

        sweep.sweep();

        awaitRecord(commands, "ReleaseHold");
        assertThat(FRAUD.postCount()).isZero();
        ledgerReplies("HoldReleased", Map.of("paymentId", paymentId, "holdId", UUID.randomUUID()));
        assertThat(awaitRecord(outcomes, "PaymentFailed").get("reason").asString()).isEqualTo("FRAUD_UNAVAILABLE");
    }

    @Test
    void aFreshFraudCheckIsLeftAloneByTheSweep() {
        FRAUD.decides("REVIEW", 60);
        hold();
        await(() -> "PENDING_REVIEW".equals(state()));
        jdbc.update("UPDATE sagas SET state = 'FRAUD_CHECK' WHERE payment_id = ?", paymentId);   // just started

        sweep.sweep();

        assertThat(state()).isEqualTo("FRAUD_CHECK");
        assertThat(commandsInOutbox("ReleaseHold")).isZero();
    }

    @Test
    void aDuplicateFundsHeldAsksFraudDetectionOnce() {
        FRAUD.decides("REVIEW", 60);
        initiate(paymentId);
        awaitRecord(commands, "HoldFunds");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ledgerReplies(first, "FundsHeld", Map.of("paymentId", paymentId, "holdId", UUID.randomUUID()));
        ledgerReplies(first, "FundsHeld", Map.of("paymentId", paymentId, "holdId", UUID.randomUUID()));
        ledgerReplies(second, "FundsHeld", Map.of("paymentId", paymentId, "holdId", UUID.randomUUID()));

        await(() -> inboxed(second));
        await(() -> "PENDING_REVIEW".equals(state()));
        assertThat(FRAUD.postCount()).isEqualTo(1);
        assertThat(eventTypes()).containsExactly("FraudChecked");
    }

    @Test
    void invalidReviewsGoToTheDlt() {
        try (KafkaConsumer<String, String> dlt = consumer(Topics.PAYMENT_REVIEWS + Topics.DLT_SUFFIX)) {
            send(Topics.PAYMENT_REVIEWS, UUID.randomUUID(), "PaymentReviewed", "{not json");
            send(Topics.PAYMENT_REVIEWS, UUID.randomUUID(), "PaymentReviewed",
                    "{\"paymentId\":\"" + paymentId + "\"}");   // no decision
            send(Topics.PAYMENT_REVIEWS, UUID.randomUUID(), "PaymentApproved", "{}");

            assertThat(awaitDead(dlt, 3)).allSatisfy(r ->
                    assertThat(header(r, "kafka_dlt-exception-cause-fqcn")).endsWith("InvalidMessageException"));
        }
    }

    // --- helpers ---

    /** payment.initiated, HoldFunds, and the ledger's FundsHeld: the fraud check runs next. */
    private void hold() {
        initiate(paymentId);
        awaitRecord(commands, "HoldFunds");
        ledgerReplies("FundsHeld", Map.of("paymentId", paymentId, "holdId", UUID.randomUUID()));
    }

    private void assertFailsClosed() {
        awaitRecord(commands, "ReleaseHold");
        assertThat(state()).isEqualTo("RELEASE_REQUESTED");
        ledgerReplies("HoldReleased", Map.of("paymentId", paymentId, "holdId", UUID.randomUUID()));
        assertThat(awaitRecord(outcomes, "PaymentFailed").get("reason").asString()).isEqualTo("FRAUD_UNAVAILABLE");
        assertThat(eventTypes()).containsExactly("PaymentFailed");   // no score, no FraudChecked
        assertThat(commandsInOutbox("CaptureHold")).isZero();
    }

    private void reviewed(boolean approve) {
        reviewed(UUID.randomUUID(), approve);
    }

    private void reviewed(UUID eventId, boolean approve) {
        send(Topics.PAYMENT_REVIEWS, eventId, "PaymentReviewed",
                objectMapper.writeValueAsString(Map.of("paymentId", paymentId, "approve", approve)));
    }

    /** This payment's payment.events types in outbox order. */
    private List<String> eventTypes() {
        return jdbc.queryForList("SELECT event_type FROM outbox WHERE msg_key = ? AND topic = ? ORDER BY id",
                String.class, paymentId.toString(), Topics.PAYMENT_EVENTS);
    }
}
