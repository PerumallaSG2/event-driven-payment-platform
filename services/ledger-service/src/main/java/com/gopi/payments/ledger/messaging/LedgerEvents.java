package com.gopi.payments.ledger.messaging;

import java.util.UUID;

/** The replies on {@code ledger.events}, keyed by paymentId; the {@code event-type} header is the record's name. */
public final class LedgerEvents {

    private LedgerEvents() {}

    public sealed interface Event permits FundsHeld, HoldRejected, HoldCaptured, HoldReleased, SettlementRejected {
        UUID paymentId();
    }

    public record FundsHeld(UUID paymentId, UUID holdId) implements Event {}

    /** reason: INSUFFICIENT_FUNDS, ACCOUNT_NOT_FOUND, CURRENCY_MISMATCH, INVALID or VOIDED. */
    public record HoldRejected(UUID paymentId, String reason, String detail) implements Event {}

    public record HoldCaptured(UUID paymentId, UUID holdId) implements Event {}

    /** holdId is null when no hold existed: the payment was voided instead. */
    public record HoldReleased(UUID paymentId, UUID holdId) implements Event {}

    /** A capture or release that can't happen; holdStatus is the hold's actual status, or NO_HOLD. */
    public record SettlementRejected(UUID paymentId, String command, String holdStatus) implements Event {}
}
