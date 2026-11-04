package com.gopi.payments.ledger.messaging;

import com.gopi.payments.ledger.domain.Hold;
import com.gopi.payments.ledger.messaging.LedgerCommands.CaptureHold;
import com.gopi.payments.ledger.messaging.LedgerCommands.Command;
import com.gopi.payments.ledger.messaging.LedgerCommands.HoldFunds;
import com.gopi.payments.ledger.messaging.LedgerCommands.ReleaseHold;
import com.gopi.payments.ledger.messaging.LedgerEvents.Event;
import com.gopi.payments.ledger.messaging.LedgerEvents.FundsHeld;
import com.gopi.payments.ledger.messaging.LedgerEvents.HoldCaptured;
import com.gopi.payments.ledger.messaging.LedgerEvents.HoldRejected;
import com.gopi.payments.ledger.messaging.LedgerEvents.HoldReleased;
import com.gopi.payments.ledger.messaging.LedgerEvents.SettlementRejected;
import com.gopi.payments.ledger.service.AccountNotFoundException;
import com.gopi.payments.ledger.service.CurrencyMismatchException;
import com.gopi.payments.ledger.service.HoldNotOpenException;
import com.gopi.payments.ledger.service.HoldService;
import com.gopi.payments.ledger.service.InsufficientFundsException;
import com.gopi.payments.ledger.service.InvalidEntryException;
import com.gopi.payments.ledger.service.NoHoldForPaymentException;
import com.gopi.payments.ledger.service.PaymentVoidedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Handles one command effectively once: the inbox row, the postings and the reply (an outbox row) commit together.
 * A redelivered command finds its inbox row and is skipped.
 * <p>A business refusal (no funds, unknown account, settled hold) is an answer, not an error. The service throws it
 * from inside the transaction, which marks the transaction rollback-only, so the refusal is written by a second
 * transaction: inbox row + rejection reply, with no postings.
 */
@Service
public class LedgerCommandHandler {

    public static final String TOPIC_COMMANDS = "ledger.commands";
    public static final String TOPIC_EVENTS = "ledger.events";

    private final HoldService holds;
    private final InboxRepository inbox;
    private final OutboxRepository outbox;
    private final TransactionTemplate tx;

    public LedgerCommandHandler(HoldService holds, InboxRepository inbox, OutboxRepository outbox,
                                TransactionTemplate tx) {
        this.holds = holds;
        this.inbox = inbox;
        this.outbox = outbox;
        this.tx = tx;
    }

    public void handle(UUID eventId, Command command) {
        Event refusal;
        try {
            tx.executeWithoutResult(status -> {
                if (inbox.markHandled(eventId, TOPIC_COMMANDS)) {
                    reply(apply(command));
                }
            });
            return;
        } catch (RuntimeException e) {
            refusal = refusal(command, e);
            if (refusal == null) {
                throw e;   // technical failure: rolled back, retried by the error handler, then DLT
            }
        }
        Event answer = refusal;
        tx.executeWithoutResult(status -> {
            if (inbox.markHandled(eventId, TOPIC_COMMANDS)) {
                reply(answer);
            }
        });
    }

    private Event apply(Command command) {
        return switch (command) {
            case HoldFunds c -> {
                Hold hold = holds.holdForPayment(c.paymentId(), c.payerAccountId(), c.merchantAccountId(),
                        c.amount(), c.currency());
                yield new FundsHeld(c.paymentId(), hold.getId());
            }
            case CaptureHold c -> new HoldCaptured(c.paymentId(), holds.captureForPayment(c.paymentId()).getId());
            case ReleaseHold c -> new HoldReleased(c.paymentId(),
                    holds.releaseForPayment(c.paymentId()).map(Hold::getId).orElse(null));
        };
    }

    /** The reply for a business refusal, or null if the exception is a technical failure. */
    private static Event refusal(Command command, RuntimeException e) {
        if (command instanceof HoldFunds c) {
            String reason = switch (e) {
                case InsufficientFundsException x -> "INSUFFICIENT_FUNDS";
                case AccountNotFoundException x -> "ACCOUNT_NOT_FOUND";
                case CurrencyMismatchException x -> "CURRENCY_MISMATCH";
                case InvalidEntryException x -> "INVALID";
                case PaymentVoidedException x -> "VOIDED";
                default -> null;
            };
            return reason == null ? null : new HoldRejected(c.paymentId(), reason, e.getMessage());
        }
        String name = command.getClass().getSimpleName();
        return switch (e) {
            case HoldNotOpenException x -> new SettlementRejected(command.paymentId(), name, x.status().name());
            case NoHoldForPaymentException x -> new SettlementRejected(command.paymentId(), name, "NO_HOLD");
            default -> null;
        };
    }

    private void reply(Event event) {
        outbox.append(TOPIC_EVENTS, event.paymentId().toString(), event.getClass().getSimpleName(), event);
    }
}
