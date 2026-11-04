package com.gopi.payments.ledger.messaging;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * The commands on {@code ledger.commands}, keyed by paymentId. The {@code event-type} header names the record. A
 * missing field fails parsing, which makes the message invalid (DLT) rather than retryable.
 */
public final class LedgerCommands {

    private LedgerCommands() {}

    public sealed interface Command permits HoldFunds, CaptureHold, ReleaseHold {
        UUID paymentId();
    }

    public record HoldFunds(UUID paymentId, UUID payerAccountId, UUID merchantAccountId, BigDecimal amount,
                            String currency) implements Command {
        public HoldFunds {
            Objects.requireNonNull(paymentId, "paymentId");
            Objects.requireNonNull(payerAccountId, "payerAccountId");
            Objects.requireNonNull(merchantAccountId, "merchantAccountId");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(currency, "currency");
        }
    }

    public record CaptureHold(UUID paymentId) implements Command {
        public CaptureHold {
            Objects.requireNonNull(paymentId, "paymentId");
        }
    }

    public record ReleaseHold(UUID paymentId) implements Command {
        public ReleaseHold {
            Objects.requireNonNull(paymentId, "paymentId");
        }
    }

    static Class<? extends Command> typeOf(String eventType) {
        return switch (eventType) {
            case "HoldFunds" -> HoldFunds.class;
            case "CaptureHold" -> CaptureHold.class;
            case "ReleaseHold" -> ReleaseHold.class;
            default -> null;
        };
    }
}
