package com.gopi.payments.payment_api.events;

import java.util.UUID;

/** The saga's outcome on payment.events (published by payment-processor), keyed by paymentId. */
public final class PaymentEvents {

    private PaymentEvents() {}

    public sealed interface Outcome permits PaymentCompleted, PaymentFailed {
        UUID paymentId();
    }

    public record PaymentCompleted(UUID paymentId) implements Outcome {}

    public record PaymentFailed(UUID paymentId, String reason) implements Outcome {}

    public static Class<? extends Outcome> typeOf(String eventType) {
        return switch (eventType) {
            case "PaymentCompleted" -> PaymentCompleted.class;
            case "PaymentFailed" -> PaymentFailed.class;
            default -> null;
        };
    }
}
