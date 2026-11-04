package com.gopi.payments.payment_api.messaging;

import com.gopi.payments.payment_api.domain.Payment;
import com.gopi.payments.payment_api.events.PaymentEvents.Outcome;
import com.gopi.payments.payment_api.events.PaymentEvents.PaymentCompleted;
import com.gopi.payments.payment_api.events.PaymentEvents.PaymentFailed;
import com.gopi.payments.payment_api.repo.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Applies the saga's outcome to the payment, effectively once: inbox row and status change commit together. */
@Service
public class PaymentOutcomeService {

    public static final String TOPIC_PAYMENT_EVENTS = "payment.events";

    private static final Logger log = LoggerFactory.getLogger(PaymentOutcomeService.class);

    private final InboxRepository inbox;
    private final PaymentRepository payments;

    public PaymentOutcomeService(InboxRepository inbox, PaymentRepository payments) {
        this.inbox = inbox;
        this.payments = payments;
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
            case PaymentCompleted c -> payment.complete();
            case PaymentFailed f -> payment.fail(f.reason());
        };
        if (!changed) {
            log.warn("Ignoring {} for payment {}: already {}", outcome.getClass().getSimpleName(),
                    payment.getId(), payment.getStatus());
        }
    }
}
