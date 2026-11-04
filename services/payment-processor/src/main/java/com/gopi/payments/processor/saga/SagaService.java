package com.gopi.payments.processor.saga;

import com.gopi.payments.processor.messaging.InboxRepository;
import com.gopi.payments.processor.messaging.Messages.CaptureHold;
import com.gopi.payments.processor.messaging.Messages.FundsHeld;
import com.gopi.payments.processor.messaging.Messages.HoldCaptured;
import com.gopi.payments.processor.messaging.Messages.HoldFunds;
import com.gopi.payments.processor.messaging.Messages.HoldRejected;
import com.gopi.payments.processor.messaging.Messages.HoldReleased;
import com.gopi.payments.processor.messaging.Messages.LedgerEvent;
import com.gopi.payments.processor.messaging.Messages.PaymentCompleted;
import com.gopi.payments.processor.messaging.Messages.PaymentFailed;
import com.gopi.payments.processor.messaging.Messages.PaymentInitiated;
import com.gopi.payments.processor.messaging.Messages.ReleaseHold;
import com.gopi.payments.processor.messaging.Messages.SettlementRejected;
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
 */
@Service
public class SagaService {

    public static final String HOLD_TIMEOUT = "HOLD_TIMEOUT";

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
        Instant now = Instant.now();
        Saga saga = new Saga(e.paymentId(), e.payerAccountId(), e.merchantAccountId(), e.amount(), e.currency(),
                now, now.plus(properties.holdDeadline()));
        sagas.save(saga);
        sendCommand(saga);
    }

    @Transactional
    public void on(UUID eventId, LedgerEvent event) {
        if (!inbox.markHandled(eventId, Topics.LEDGER_EVENTS)) {
            return;
        }
        Optional<Saga> found = sagas.lockById(event.paymentId());
        if (found.isEmpty()) {
            log.warn("{} for unknown saga {}; ignored", event.getClass().getSimpleName(), event.paymentId());
            return;
        }
        Saga saga = found.get();
        SagaState state = saga.getState();
        Instant now = Instant.now();
        switch (event) {
            case FundsHeld f when state == SagaState.HOLD_REQUESTED -> {
                // Item 5 puts the fraud check here; until then every held payment is captured.
                saga.moveTo(SagaState.CAPTURE_REQUESTED, now);
                sendCommand(saga);
            }
            case HoldCaptured c when state == SagaState.CAPTURE_REQUESTED -> complete(saga, now);
            case HoldRejected r when state == SagaState.HOLD_REQUESTED -> fail(saga, r.reason(), now);
            case HoldReleased r when state == SagaState.RELEASE_REQUESTED -> fail(saga, saga.getFailureReason(), now);
            case SettlementRejected s when state.waitingForLedger() -> followLedger(saga, s, now);
            default -> log.info("Ignoring {} for saga {} in {}", event.getClass().getSimpleName(),
                    saga.getPaymentId(), state);
        }
    }

    /**
     * Called by the sweep with the saga already row-locked. Past its hold deadline a saga is compensated;
     * otherwise its current command is sent again (the ledger is idempotent by paymentId). A capture is never
     * compensated: once decided, it is re-sent until the ledger answers.
     */
    void sweep(Saga saga, Instant now) {
        if (saga.getState() == SagaState.HOLD_REQUESTED && saga.getDeadlineAt().isBefore(now)) {
            log.warn("Saga {}: no hold after {}, compensating", saga.getPaymentId(), properties.holdDeadline());
            saga.compensate(HOLD_TIMEOUT, now);
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
