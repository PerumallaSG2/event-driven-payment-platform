package com.gopi.payments.ledger.service;

import com.gopi.payments.ledger.domain.AccountKind;
import com.gopi.payments.ledger.domain.EntryType;
import com.gopi.payments.ledger.domain.Hold;
import com.gopi.payments.ledger.domain.HoldStatus;
import com.gopi.payments.ledger.domain.JournalEntry;
import com.gopi.payments.ledger.repo.AccountRepository;
import com.gopi.payments.ledger.repo.HoldRepository;
import com.gopi.payments.ledger.repo.VoidedPaymentRepository;
import com.gopi.payments.ledger.service.LedgerService.Line;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reserve, capture, release. Each step is one journal entry through {@link LedgerService#post}:
 * <pre>
 *   hold     payer −a, HOLDS +a
 *   capture  HOLDS −a, merchant +a
 *   release  HOLDS −a, payer +a
 * </pre>
 * The REST API uses the hold-id methods; the Kafka command handlers use the payment-keyed ones, which are idempotent
 * by paymentId so a re-sent command never moves money twice.
 */
@Service
public class HoldService {

    private final HoldRepository holds;
    private final AccountRepository accounts;
    private final AccountService accountService;
    private final LedgerService ledger;
    private final EntityManager entityManager;
    private final VoidedPaymentRepository voided;

    public HoldService(HoldRepository holds, AccountRepository accounts, AccountService accountService,
                       LedgerService ledger, EntityManager entityManager, VoidedPaymentRepository voided) {
        this.holds = holds;
        this.accounts = accounts;
        this.accountService = accountService;
        this.ledger = ledger;
        this.entityManager = entityManager;
        this.voided = voided;
    }

    /** Moves the amount from the payer to HOLDS. Throws {@link InsufficientFundsException} if the payer can't cover it. */
    @Transactional
    public Hold hold(UUID payerAccountId, UUID merchantAccountId, BigDecimal amount, String currency) {
        return placeHold(null, payerAccountId, merchantAccountId, amount, currency);
    }

    /**
     * The payment's hold: the existing one if it was already placed (a re-sent command), otherwise a new one.
     * Throws {@link PaymentVoidedException} if the payment's release was processed first.
     */
    @Transactional
    public Hold holdForPayment(UUID paymentId, UUID payerAccountId, UUID merchantAccountId, BigDecimal amount,
                               String currency) {
        Optional<Hold> existing = holds.findByPaymentId(paymentId);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (voided.exists(paymentId)) {
            throw new PaymentVoidedException(paymentId);
        }
        return placeHold(paymentId, payerAccountId, merchantAccountId, amount, currency);
    }

    /** Captures the payment's hold; an already captured hold is returned as is. */
    @Transactional
    public Hold captureForPayment(UUID paymentId) {
        Hold hold = holds.lockByPaymentId(paymentId).orElseThrow(() -> new NoHoldForPaymentException(paymentId));
        if (hold.getStatus() == HoldStatus.CAPTURED) {
            return hold;
        }
        requireOpen(hold);
        settle(hold, EntryType.CAPTURE, HoldStatus.CAPTURED, hold.getMerchantAccountId());
        return hold;
    }

    /**
     * Releases the payment's hold; an already released hold is returned as is. With no hold yet, the payment is
     * voided (empty result), so a HoldFunds arriving later is rejected instead of stranding the money.
     */
    @Transactional
    public Optional<Hold> releaseForPayment(UUID paymentId) {
        Optional<Hold> found = holds.lockByPaymentId(paymentId);
        if (found.isEmpty()) {
            voided.insertIfAbsent(paymentId);
            return Optional.empty();
        }
        Hold hold = found.get();
        if (hold.getStatus() != HoldStatus.RELEASED) {
            requireOpen(hold);
            settle(hold, EntryType.RELEASE, HoldStatus.RELEASED, hold.getPayerAccountId());
        }
        return Optional.of(hold);
    }

    private Hold placeHold(UUID paymentId, UUID payerAccountId, UUID merchantAccountId, BigDecimal amount,
                           String currency) {
        AccountService.requirePositive(amount);
        if (payerAccountId.equals(merchantAccountId)) {
            throw new InvalidEntryException("payer and merchant must be different accounts");
        }
        String merchantCurrency = accounts.findCurrencyById(merchantAccountId)
                .orElseThrow(() -> new AccountNotFoundException(merchantAccountId));
        if (!merchantCurrency.equals(currency)) {
            throw new CurrencyMismatchException(merchantAccountId, merchantCurrency, currency);
        }
        UUID holdsAccount = accountService.systemAccountId(AccountKind.HOLDS, currency);

        JournalEntry entry = ledger.post(EntryType.HOLD, List.of(
                new Line(payerAccountId, currency, amount.negate()),
                new Line(holdsAccount, currency, amount)));

        Hold hold = new Hold(paymentId, payerAccountId, merchantAccountId, amount.setScale(2), currency, entry.getId());
        entityManager.persist(hold);
        return hold;
    }

    /** HOLDS → merchant, full amount. */
    @Transactional
    public Hold capture(UUID holdId) {
        Hold hold = lockOpen(holdId);
        settle(hold, EntryType.CAPTURE, HoldStatus.CAPTURED, hold.getMerchantAccountId());
        return hold;
    }

    /** HOLDS → payer (compensation). */
    @Transactional
    public Hold release(UUID holdId) {
        Hold hold = lockOpen(holdId);
        settle(hold, EntryType.RELEASE, HoldStatus.RELEASED, hold.getPayerAccountId());
        return hold;
    }

    @Transactional(readOnly = true)
    public Hold get(UUID holdId) {
        return holds.findById(holdId).orElseThrow(() -> new HoldNotFoundException(holdId));
    }

    private Hold lockOpen(UUID holdId) {
        Hold hold = holds.lockById(holdId).orElseThrow(() -> new HoldNotFoundException(holdId));
        requireOpen(hold);
        return hold;
    }

    private static void requireOpen(Hold hold) {
        if (hold.getStatus() != HoldStatus.OPEN) {
            throw new HoldNotOpenException(hold.getId(), hold.getStatus());
        }
    }

    private void settle(Hold hold, EntryType type, HoldStatus newStatus, UUID destination) {
        UUID holdsAccount = accountService.systemAccountId(AccountKind.HOLDS, hold.getCurrency());
        JournalEntry entry = ledger.post(type, List.of(
                new Line(holdsAccount, hold.getCurrency(), hold.getAmount().negate()),
                new Line(destination, hold.getCurrency(), hold.getAmount())));
        hold.settle(newStatus, entry.getId());
    }
}
