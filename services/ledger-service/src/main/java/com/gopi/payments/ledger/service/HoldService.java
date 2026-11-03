package com.gopi.payments.ledger.service;

import com.gopi.payments.ledger.domain.AccountKind;
import com.gopi.payments.ledger.domain.EntryType;
import com.gopi.payments.ledger.domain.Hold;
import com.gopi.payments.ledger.domain.HoldStatus;
import com.gopi.payments.ledger.domain.JournalEntry;
import com.gopi.payments.ledger.repo.AccountRepository;
import com.gopi.payments.ledger.repo.HoldRepository;
import com.gopi.payments.ledger.service.LedgerService.Line;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Reserve, capture, release. Each step is one journal entry through {@link LedgerService#post}:
 * <pre>
 *   hold     payer −a, HOLDS +a
 *   capture  HOLDS −a, merchant +a
 *   release  HOLDS −a, payer +a
 * </pre>
 * Later the saga's Kafka command handlers (item 4) call these same methods.
 */
@Service
public class HoldService {

    private final HoldRepository holds;
    private final AccountRepository accounts;
    private final AccountService accountService;
    private final LedgerService ledger;
    private final EntityManager entityManager;

    public HoldService(HoldRepository holds, AccountRepository accounts, AccountService accountService,
                       LedgerService ledger, EntityManager entityManager) {
        this.holds = holds;
        this.accounts = accounts;
        this.accountService = accountService;
        this.ledger = ledger;
        this.entityManager = entityManager;
    }

    /** Moves the amount from the payer to HOLDS. Throws {@link InsufficientFundsException} if the payer can't cover it. */
    @Transactional
    public Hold hold(UUID payerAccountId, UUID merchantAccountId, BigDecimal amount, String currency) {
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

        Hold hold = new Hold(payerAccountId, merchantAccountId, amount.setScale(2), currency, entry.getId());
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
        if (hold.getStatus() != HoldStatus.OPEN) {
            throw new HoldNotOpenException(holdId, hold.getStatus());
        }
        return hold;
    }

    private void settle(Hold hold, EntryType type, HoldStatus newStatus, UUID destination) {
        UUID holdsAccount = accountService.systemAccountId(AccountKind.HOLDS, hold.getCurrency());
        JournalEntry entry = ledger.post(type, List.of(
                new Line(holdsAccount, hold.getCurrency(), hold.getAmount().negate()),
                new Line(destination, hold.getCurrency(), hold.getAmount())));
        hold.settle(newStatus, entry.getId());
    }
}
