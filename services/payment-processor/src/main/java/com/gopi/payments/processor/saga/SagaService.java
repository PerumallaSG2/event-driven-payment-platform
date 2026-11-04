package com.gopi.payments.processor.saga;

import com.gopi.payments.processor.fraud.FraudRequest;
import com.gopi.payments.processor.fraud.FraudResult;
import com.gopi.payments.processor.fraud.FraudResult.Scored;
import com.gopi.payments.processor.fraud.FraudResult.Unavailable;
import com.gopi.payments.processor.messaging.InboxRepository;
import com.gopi.payments.processor.messaging.Messages.CaptureHold;
import com.gopi.payments.processor.messaging.Messages.FraudChecked;
import com.gopi.payments.processor.messaging.Messages.FundsHeld;
import com.gopi.payments.processor.messaging.Messages.HoldCaptured;
import com.gopi.payments.processor.messaging.Messages.HoldFunds;
import com.gopi.payments.processor.messaging.Messages.HoldRejected;
import com.gopi.payments.processor.messaging.Messages.HoldReleased;
import com.gopi.payments.processor.messaging.Messages.LedgerEvent;
import com.gopi.payments.processor.messaging.Messages.PaymentCompleted;
import com.gopi.payments.processor.messaging.Messages.PaymentFailed;
import com.gopi.payments.processor.messaging.Messages.PaymentInitiated;
import com.gopi.payments.processor.messaging.Messages.PaymentReviewed;
import com.gopi.payments.processor.messaging.Messages.ReleaseHold;
import com.gopi.payments.processor.messaging.Messages.SettlementRejected;
import com.gopi.payments.processor.messaging.Messages.TriggeredRule;
import com.gopi.payments.processor.messaging.OutboxRepository;
import com.gopi.payments.processor.messaging.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The orchestrator. Every transition is one transaction: the inbox row (or the sweep's row lock), the saga update,
 * and the next command or the outcome as an outbox row. Events that don't fit the saga's state (duplicates, late
 * replies) are logged and acknowledged without a change.
 * <p>
 * The fraud check is the one step that is not a message: {@link #on} moves a held saga to FRAUD_CHECK and returns
 * true, the caller asks fraud-detection after that transaction committed (no HTTP call inside a transaction or under
 * a row lock), and {@link #applyFraud} records the answer in a new one.
 */
@Service
public class SagaService {

    public static final String HOLD_TIMEOUT = "HOLD_TIMEOUT";
    public static final String FRAUD_BLOCKED = "FRAUD_BLOCKED";
    public static final String FRAUD_REJECTED = "FRAUD_REJECTED";
    public static final String FRAUD_UNAVAILABLE = "FRAUD_UNAVAILABLE";
    public static final String REVIEW_TIMEOUT = "REVIEW_TIMEOUT";

    private static final Logger log = LoggerFactory.getLogger(SagaService.class);

    private final SagaRepository sagas;
    private final InboxRepository inbox;
    private final OutboxRepository outbox;
    private final SagaProperties properties;

    public SagaService(SagaRepository sagas, InboxRepository inbox, OutboxRepository outbox,
                       SagaProperties properties) {
        this.sagas = sagas;
        this.inbox = inbox;
        this.outbox = outbox;
        this.properties = properties;
    }

    @Transactional
    public void start(UUID eventId, PaymentInitiated e) {
        if (!inbox.markHandled(eventId, Topics.PAYMENT_INITIATED)) {
            return;
        }
        if (sagas.existsById(e.paymentId())) {
            log.info("Saga for payment {} already exists; ignoring payment.initiated {}", e.paymentId(), eventId);
            return;
        }
        Instant now = Timestamps.now();
        Saga saga = new Saga(e, now, now.plus(properties.holdDeadline()));
        sagas.save(saga);
        sendCommand(saga);
    }

    /** Returns true when the funds were just held: the caller then runs the fraud check, after the commit. */
    @Transactional
    public boolean on(UUID eventId, LedgerEvent event) {
        if (!inbox.markHandled(eventId, Topics.LEDGER_EVENTS)) {
            return false;
        }
        Optional<Saga> found = sagas.lockById(event.paymentId());
        if (found.isEmpty()) {
            log.warn("{} for unknown saga {}; ignored", event.getClass().getSimpleName(), event.paymentId());
            return false;
        }
        Saga saga = found.get();
        SagaState state = saga.getState();
        Instant now = Timestamps.now();
        switch (event) {
            case FundsHeld f when state == SagaState.HOLD_REQUESTED -> {
                saga.moveTo(SagaState.FRAUD_CHECK, now);
                return true;
            }
            case HoldCaptured c when state == SagaState.CAPTURE_REQUESTED -> complete(saga, now);
            case HoldRejected r when state == SagaState.HOLD_REQUESTED -> fail(saga, r.reason(), now);
            case HoldReleased r when state == SagaState.RELEASE_REQUESTED -> fail(saga, saga.getFailureReason(), now);
            case SettlementRejected s when state.waitingForLedger() -> followLedger(saga, s, now);
            default -> log.info("Ignoring {} for saga {} in {}", event.getClass().getSimpleName(),
                    saga.getPaymentId(), state);
        }
        return false;
    }

    /**
     * What to send fraud-detection for a saga in FRAUD_CHECK; empty when it is no longer there (already decided, or
     * failed closed by the sweep). A saga from before item 5 has no card data: it fails closed here.
     */
    @Transactional
    public Optional<FraudRequest> fraudRequestFor(UUID paymentId) {
        Optional<Saga> found = sagas.lockById(paymentId).filter(s -> s.getState() == SagaState.FRAUD_CHECK);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Saga s = found.get();
        if (s.getCardId() == null) {
            log.error("Saga {} has no card data to check; failing closed", paymentId);
            failClosed(s, "no card data", Timestamps.now());
            return Optional.empty();
        }
        return Optional.of(new FraudRequest(s.getCardId(), s.getAmount(), s.getMerchant(), s.getMerchantCategory(),
                s.getCity(), s.getLatitude(), s.getLongitude(), s.getTransactionTime()));
    }

    /**
     * Records fraud-detection's answer, if the saga is still in FRAUD_CHECK (the sweep may have failed it closed in
     * the meantime). APPROVE captures; BLOCK and no usable answer release the hold; REVIEW waits for a person.
     */
    @Transactional
    public void applyFraud(UUID paymentId, FraudResult result) {
        Optional<Saga> found = sagas.lockById(paymentId);
        if (found.isEmpty() || found.get().getState() != SagaState.FRAUD_CHECK) {
            log.info("Fraud result for saga {} no longer applies ({}); ignored", paymentId,
                    found.map(Saga::getState).orElse(null));
            return;
        }
        Saga saga = found.get();
        Instant now = Timestamps.now();
        switch (result) {
            case Unavailable u -> failClosed(saga, u.cause() + ": " + u.detail(), now);
            case Scored scored -> {
                saga.scoredAs(scored.fraudTransactionId());
                publish(saga, "FraudChecked", new FraudChecked(paymentId, scored.fraudTransactionId(),
                        scored.decision().name(), scored.riskScore(), scored.rules().stream()
                        .map(r -> new TriggeredRule(r.rule(), r.points(), r.reason())).toList()));
                switch (scored.decision()) {
                    case APPROVE -> {
                        saga.moveTo(SagaState.CAPTURE_REQUESTED, now);
                        sendCommand(saga);
                    }
                    case BLOCK -> {
                        log.info("Saga {}: fraud-detection blocked it (score {}); releasing the hold", paymentId,
                                scored.riskScore());
                        saga.compensate(FRAUD_BLOCKED, now);
                        sendCommand(saga);
                    }
                    case REVIEW -> {
                        log.info("Saga {}: fraud-detection wants a review (score {}); holding until {}", paymentId,
                                scored.riskScore(), now.plus(properties.reviewDeadline()));
                        saga.awaitReview(now.plus(properties.reviewDeadline()), now);
                    }
                }
            }
        }
    }

    /** A person's decision on a payment in review: capture, or release the hold. Anything else is too late. */
    @Transactional
    public void review(UUID eventId, PaymentReviewed reviewed) {
        if (!inbox.markHandled(eventId, Topics.PAYMENT_REVIEWS)) {
            return;
        }
        Optional<Saga> found = sagas.lockById(reviewed.paymentId());
        if (found.isEmpty() || found.get().getState() != SagaState.PENDING_REVIEW) {
            log.warn("Review ({}) for saga {} in {} ignored: not waiting for a review", reviewed.approve()
                    ? "approve" : "reject", reviewed.paymentId(), found.map(Saga::getState).orElse(null));
            return;
        }
        Saga saga = found.get();
        if (reviewed.approve()) {
            saga.moveTo(SagaState.CAPTURE_REQUESTED, Timestamps.now());
        } else {
            saga.compensate(FRAUD_REJECTED, Timestamps.now());
        }
        sendCommand(saga);
    }

    /**
     * Called by the sweep with the saga already row-locked. Past its hold deadline a saga is compensated, and so is
     * one past its review deadline or stuck in a fraud check (fail closed; the check is not repeated, so
     * fraud-detection never sees the transaction twice). Otherwise its current command is sent again (the ledger is
     * idempotent by paymentId). A capture is never compensated: once decided, it is re-sent until the ledger answers.
     */
    void sweep(Saga saga, Instant now) {
        SagaState state = saga.getState();
        if (state == SagaState.HOLD_REQUESTED && saga.getDeadlineAt().isBefore(now)) {
            log.warn("Saga {}: no hold after {}, compensating", saga.getPaymentId(), properties.holdDeadline());
            saga.compensate(HOLD_TIMEOUT, now);
        } else if (state == SagaState.PENDING_REVIEW && saga.getDeadlineAt().isBefore(now)) {
            log.warn("Saga {}: no review within {}, releasing the hold", saga.getPaymentId(),
                    properties.reviewDeadline());
            saga.compensate(REVIEW_TIMEOUT, now);
        } else if (state == SagaState.FRAUD_CHECK
                && saga.getUpdatedAt().isBefore(now.minus(properties.fraudCheckStaleAfter()))) {
            failClosed(saga, "fraud check unfinished after " + properties.fraudCheckStaleAfter(), now);
            return;
        } else if (saga.getState().waitingForLedger()) {
            saga.resent(now);
            log.info("Saga {}: re-sending for {} (attempt {})", saga.getPaymentId(), saga.getState(),
                    saga.getAttempts());
        } else {
            return;
        }
        sendCommand(saga);
    }

    /** A capture or release the ledger refused: the hold's actual status decides the outcome. */
    private void followLedger(Saga saga, SettlementRejected s, Instant now) {
        switch (s.holdStatus()) {
            case "CAPTURED" -> complete(saga, now);
            case "RELEASED" -> fail(saga, saga.getFailureReason() != null ? saga.getFailureReason() : "RELEASED", now);
            default -> log.error("Saga {} in {}: ledger refused {} with hold status {}; left for the sweep",
                    saga.getPaymentId(), saga.getState(), s.command(), s.holdStatus());
        }
    }

    /** No usable fraud decision: release the hold, the payment FAILS (fail closed). */
    private void failClosed(Saga saga, String why, Instant now) {
        log.warn("Saga {}: no fraud decision ({}); failing closed", saga.getPaymentId(), why);
        saga.compensate(FRAUD_UNAVAILABLE, now);
        sendCommand(saga);
    }

    private void complete(Saga saga, Instant now) {
        saga.moveTo(SagaState.COMPLETED, now);
        publish(saga, "PaymentCompleted", new PaymentCompleted(saga.getPaymentId()));
    }

    private void fail(Saga saga, String reason, Instant now) {
        saga.fail(reason, now);
        publish(saga, "PaymentFailed", new PaymentFailed(saga.getPaymentId(), reason));
    }

    private void sendCommand(Saga saga) {
        String key = saga.getPaymentId().toString();
        switch (saga.getState()) {
            case HOLD_REQUESTED -> outbox.append(Topics.LEDGER_COMMANDS, key, "HoldFunds",
                    new HoldFunds(saga.getPaymentId(), saga.getPayerAccountId(), saga.getMerchantAccountId(),
                            saga.getAmount(), saga.getCurrency()));
            case CAPTURE_REQUESTED -> outbox.append(Topics.LEDGER_COMMANDS, key, "CaptureHold",
                    new CaptureHold(saga.getPaymentId()));
            case RELEASE_REQUESTED -> outbox.append(Topics.LEDGER_COMMANDS, key, "ReleaseHold",
                    new ReleaseHold(saga.getPaymentId()));
            default -> throw new IllegalStateException("no command for " + saga.getState());
        }
    }

    private void publish(Saga saga, String type, Object event) {
        outbox.append(Topics.PAYMENT_EVENTS, saga.getPaymentId().toString(), type, event);
    }
}
