package com.gopi.payments.ledger.service;

import com.gopi.payments.ledger.domain.Account;
import com.gopi.payments.ledger.domain.AccountKind;
import com.gopi.payments.ledger.domain.EntryType;
import com.gopi.payments.ledger.domain.JournalEntry;
import com.gopi.payments.ledger.repo.AccountRepository;
import com.gopi.payments.ledger.service.LedgerService.Line;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final LedgerService ledger;
    private final EntityManager entityManager;

    public AccountService(AccountRepository accounts, LedgerService ledger, EntityManager entityManager) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.entityManager = entityManager;
    }

    /** Opens a customer or merchant account at balance 0. System accounts are created by the ledger itself. */
    @Transactional
    public Account create(String name, AccountKind kind, String currency, boolean allowNegative) {
        if (kind.isSystem()) {
            throw new InvalidEntryException(kind + " accounts are created by the ledger, not via the API");
        }
        Account account = new Account(name, kind, currency, allowNegative);
        entityManager.persist(account);
        return account;
    }

    @Transactional(readOnly = true)
    public Account get(UUID id) {
        return accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

    /** Money from outside the ledger: EXTERNAL −amount, account +amount. */
    @Transactional
    public JournalEntry deposit(UUID accountId, BigDecimal amount) {
        requirePositive(amount);
        String currency = accounts.findCurrencyById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
        UUID external = systemAccountId(AccountKind.EXTERNAL, currency);
        return ledger.post(EntryType.DEPOSIT, List.of(
                new Line(external, currency, amount.negate()),
                new Line(accountId, currency, amount)));
    }

    /**
     * Id of the HOLDS or EXTERNAL account for a currency, created on first use. Returns only the id, so the account
     * isn't loaded into the persistence context before {@link LedgerService#post} locks it.
     */
    @Transactional
    public UUID systemAccountId(AccountKind kind, String currency) {
        if (!kind.isSystem()) {
            throw new IllegalArgumentException(kind + " is not a system account");
        }
        return accounts.findSystemAccountId(kind.name(), currency).orElseGet(() -> {
            accounts.insertSystemAccountIfAbsent(UUID.randomUUID(), kind + " " + currency, kind.name(), currency);
            return accounts.findSystemAccountId(kind.name(), currency).orElseThrow();
        });
    }

    static void requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidEntryException("amount must be positive");
        }
    }
}
