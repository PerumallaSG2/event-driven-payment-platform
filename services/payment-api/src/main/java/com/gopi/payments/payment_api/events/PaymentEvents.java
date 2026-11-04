package com.gopi.payments.payment_api.events;

import java.util.List;
import java.util.UUID;

/**
 * What payment-processor reports on payment.events, keyed by paymentId: fraud-detection's verdict (before the
 * outcome, when there is one) and the saga's outcome.
 */
public final class PaymentEvents {

    private PaymentEvents() {}

    public sealed interface Outcome permits FraudChecked, PaymentCompleted, PaymentFailed {
        UUID paymentId();
    }

    public record FraudChecked(UUID paymentId, UUID fraudTransactionId, String decision, Integer riskScore,
                               List<TriggeredRule> triggeredRules) implements Outcome {}

    public record TriggeredRule(String rule, int points, String reason) {}

    public record PaymentCompleted(UUID paymentId) implements Outcome {}

    public record PaymentFailed(UUID paymentId, String reason) implements Outcome {}

    public static Class<? extends Outcome> typeOf(String eventType) {
        return switch (eventType) {
            case "FraudChecked" -> FraudChecked.class;
            case "PaymentCompleted" -> PaymentCompleted.class;
            case "PaymentFailed" -> PaymentFailed.class;
            default -> null;
        };
    }
}
