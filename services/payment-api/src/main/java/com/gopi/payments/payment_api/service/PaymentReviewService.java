package com.gopi.payments.payment_api.service;

import com.gopi.payments.payment_api.domain.Payment;
import com.gopi.payments.payment_api.domain.PaymentStatus;
import com.gopi.payments.payment_api.domain.Timestamps;
import com.gopi.payments.payment_api.events.PaymentReviewed;
import com.gopi.payments.payment_api.outbox.OutboxRepository;
import com.gopi.payments.payment_api.repo.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * A person's decision on a payment fraud-detection sent to REVIEW. It is stored with the payment and handed to the
 * saga as {@code PaymentReviewed} through the outbox, in one transaction; the saga captures or releases the hold and
 * the outcome arrives on payment.events as usual. The same decision again is accepted and changes nothing.
 */
@Service
public class PaymentReviewService {

    public static final String TOPIC_PAYMENT_REVIEWS = "payment.reviews";
    public static final String EVENT_PAYMENT_REVIEWED = "PaymentReviewed";

    private final PaymentRepository payments;
    private final OutboxRepository outbox;

    public PaymentReviewService(PaymentRepository payments, OutboxRepository outbox) {
        this.payments = payments;
        this.outbox = outbox;
    }

    @Transactional
    public void review(UUID paymentId, boolean approve) {
        Payment payment = payments.lockById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
        String decision = approve ? "APPROVE" : "REJECT";
        if (payment.getReviewDecision() != null) {
            if (payment.getReviewDecision().equals(decision)) {
                return;   // a retry of the same review
            }
            throw new PaymentNotReviewableException("Payment " + paymentId + " was already reviewed: "
                    + payment.getReviewDecision());
        }
        if (payment.getStatus() != PaymentStatus.PENDING_REVIEW) {
            throw new PaymentNotReviewableException("Payment " + paymentId + " is " + payment.getStatus()
                    + ", not waiting for a review");
        }
        payment.review(approve, Timestamps.now());
        outbox.append(TOPIC_PAYMENT_REVIEWS, paymentId.toString(), EVENT_PAYMENT_REVIEWED,
                new PaymentReviewed(paymentId, approve));
    }
}
