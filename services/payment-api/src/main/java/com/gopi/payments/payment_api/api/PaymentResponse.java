package com.gopi.payments.payment_api.api;

import com.gopi.payments.payment_api.domain.Payment;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * GET /payments/{id}. {@code timestamp} comes back in the offset the client sent. {@code fraud} is fraud-detection's
 * verdict (null until checked, and for good when the check failed closed); {@code review} is a person's decision on
 * a payment in PENDING_REVIEW (null until then).
 */
public record PaymentResponse(
        UUID paymentId,
        String status,
        String failureReason,
        UUID payerAccountId,
        UUID merchantAccountId,
        BigDecimal amount,
        String currency,
        String cardId,
        String merchant,
        String merchantCategory,
        String city,
        double latitude,
        double longitude,
        OffsetDateTime timestamp,
        Instant createdAt,
        Fraud fraud,
        Review review) {

    public record Fraud(UUID transactionId, String decision, int score, List<TriggeredRule> triggeredRules) {}

    public record TriggeredRule(String rule, int points, String reason) {}

    public record Review(String decision, Instant reviewedAt) {}

    private static final TypeReference<List<TriggeredRule>> RULES = new TypeReference<>() {};

    public static PaymentResponse from(Payment p, ObjectMapper mapper) {
        Fraud fraud = p.getFraudDecision() == null ? null : new Fraud(p.getFraudTransactionId(),
                p.getFraudDecision(), p.getFraudScore(), mapper.readValue(p.getFraudRules(), RULES));
        Review review = p.getReviewDecision() == null ? null : new Review(p.getReviewDecision(), p.getReviewedAt());
        return new PaymentResponse(p.getId(), p.getStatus().name(), p.getFailureReason(), p.getPayerAccountId(), p.getMerchantAccountId(),
                p.getAmount(), p.getCurrency(), p.getCardId(), p.getMerchant(), p.getMerchantCategory(), p.getCity(),
                p.getLatitude(), p.getLongitude(), p.getTransactionTime(), p.getCreatedAt(), fraud, review);
    }
}
