package com.gopi.payments.processor.messaging;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The JSON contracts this service reads and writes. They mirror payment-api's {@code PaymentInitiatedEvent} and
 * {@code PaymentEvents}, and ledger-service's {@code LedgerCommands} and {@code LedgerEvents} (no shared module; the
 * event-type header is the record's simple name).
 */
public final class Messages {

    private Messages() {}

    /** In, on payment.initiated. */
    public record PaymentInitiated(UUID paymentId, BigDecimal amount, String currency, UUID payerAccountId,
                                   UUID merchantAccountId, String cardId, String merchant, String merchantCategory,
                                   String city, Double latitude, Double longitude, OffsetDateTime timestamp) {
        public PaymentInitiated {
            Objects.requireNonNull(paymentId, "paymentId");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(payerAccountId, "payerAccountId");
            Objects.requireNonNull(merchantAccountId, "merchantAccountId");
            Objects.requireNonNull(cardId, "cardId");
            Objects.requireNonNull(merchant, "merchant");
            Objects.requireNonNull(merchantCategory, "merchantCategory");
            Objects.requireNonNull(city, "city");
            Objects.requireNonNull(latitude, "latitude");
            Objects.requireNonNull(longitude, "longitude");
            Objects.requireNonNull(timestamp, "timestamp");
        }

        public static Class<? extends PaymentInitiated> typeOf(String eventType) {
            return "PaymentInitiated".equals(eventType) ? PaymentInitiated.class : null;
        }
    }

    // --- out, on ledger.commands ---

    public record HoldFunds(UUID paymentId, UUID payerAccountId, UUID merchantAccountId, BigDecimal amount,
                            String currency) {}

    public record CaptureHold(UUID paymentId) {}

    public record ReleaseHold(UUID paymentId) {}

    // --- in, on ledger.events ---

    public sealed interface LedgerEvent
            permits FundsHeld, HoldRejected, HoldCaptured, HoldReleased, SettlementRejected {
        UUID paymentId();

        static Class<? extends LedgerEvent> typeOf(String eventType) {
            return switch (eventType) {
                case "FundsHeld" -> FundsHeld.class;
                case "HoldRejected" -> HoldRejected.class;
                case "HoldCaptured" -> HoldCaptured.class;
                case "HoldReleased" -> HoldReleased.class;
                case "SettlementRejected" -> SettlementRejected.class;
                default -> null;
            };
        }
    }

    public record FundsHeld(UUID paymentId, UUID holdId) implements LedgerEvent {}

    public record HoldRejected(UUID paymentId, String reason, String detail) implements LedgerEvent {}

    public record HoldCaptured(UUID paymentId, UUID holdId) implements LedgerEvent {}

    public record HoldReleased(UUID paymentId, UUID holdId) implements LedgerEvent {}

    public record SettlementRejected(UUID paymentId, String command, String holdStatus) implements LedgerEvent {}

    // --- in, on payment.reviews (a person's decision on a payment fraud-detection sent to REVIEW) ---

    public record PaymentReviewed(UUID paymentId, Boolean approve) {
        public PaymentReviewed {
            Objects.requireNonNull(paymentId, "paymentId");
            Objects.requireNonNull(approve, "approve");
        }

        public static Class<? extends PaymentReviewed> typeOf(String eventType) {
            return "PaymentReviewed".equals(eventType) ? PaymentReviewed.class : null;
        }
    }

    // --- out, on payment.events ---

    /** fraud-detection's verdict; sent before the payment's outcome. Not sent when the check failed closed. */
    public record FraudChecked(UUID paymentId, UUID fraudTransactionId, String decision, int riskScore,
                               List<TriggeredRule> triggeredRules) {}

    public record TriggeredRule(String rule, int points, String reason) {}

    public record PaymentCompleted(UUID paymentId) {}

    public record PaymentFailed(UUID paymentId, String reason) {}
}
