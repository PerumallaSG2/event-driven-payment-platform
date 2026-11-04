package com.gopi.payments.payment_api.messaging;

import com.gopi.payments.payment_api.domain.Payment;
import com.gopi.payments.payment_api.events.PaymentEvents.FraudChecked;
import com.gopi.payments.payment_api.events.PaymentEvents.Outcome;
import com.gopi.payments.payment_api.events.PaymentEvents.PaymentCompleted;
import com.gopi.payments.payment_api.events.PaymentEvents.PaymentFailed;
import com.gopi.payments.payment_api.repo.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

import java.util.UUID;

/**
 * Applies what the saga reports to the payment, effectively once: inbox row and change commit together. The fraud
 * verdict is stored (REVIEW moves the payment to PENDING_REVIEW); the outcome sets the final status.
 */
@Service
public class PaymentOutcomeService {

    public static final String TOPIC_PAYMENT_EVENTS = "payment.events";

    private static final Logger log = LoggerFactory.getLogger(PaymentOutcomeService.class);

    private final InboxRepository inbox;
    private static final Set<String> DECISIONS = Set.of("APPROVE", "REVIEW", "BLOCK");

    private final PaymentRepository payments;
    private final ObjectMapper objectMapper;

    public PaymentOutcomeService(InboxRepository inbox, PaymentRepository payments, ObjectMapper objectMapper) {
        this.inbox = inbox;
        this.payments = payments;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void apply(UUID eventId, Outcome outcome) {
        if (!inbox.markHandled(eventId, TOPIC_PAYMENT_EVENTS)) {
            return;
        }
        // An outcome for a payment this service never created can't be applied or retried away: rolled back, DLT.
        Payment payment = payments.lockById(outcome.paymentId()).orElseThrow(() -> new InvalidMessageException(
                "outcome for unknown payment " + outcome.paymentId(), null));
        boolean changed = switch (outcome) {
            case FraudChecked f -> recordFraud(payment, f);
            case PaymentCompleted c -> payment.complete();
            case PaymentFailed f -> payment.fail(f.reason());
        };
        if (!changed) {
            log.warn("Ignoring {} for payment {}: already {}", outcome.getClass().getSimpleName(),
                    payment.getId(), payment.getStatus());
        }
    }

    private boolean recordFraud(Payment payment, FraudChecked f) {
        if (f.fraudTransactionId() == null || !DECISIONS.contains(f.decision()) || f.riskScore() == null) {
            throw new InvalidMessageException("incomplete FraudChecked for " + f.paymentId(), null);
        }
        List<?> rules = f.triggeredRules() == null ? List.of() : f.triggeredRules();
        return payment.recordFraud(f.fraudTransactionId(), f.decision(), f.riskScore(),
                objectMapper.writeValueAsString(rules));
    }
}
