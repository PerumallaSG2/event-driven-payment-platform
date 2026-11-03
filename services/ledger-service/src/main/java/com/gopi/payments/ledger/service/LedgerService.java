package com.gopi.payments.ledger.service;

import com.gopi.payments.ledger.domain.Account;
import com.gopi.payments.ledger.domain.EntryType;
import com.gopi.payments.ledger.domain.JournalEntry;
import com.gopi.payments.ledger.domain.Posting;
import com.gopi.payments.ledger.repo.AccountRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The only code that writes postings or changes balances. Every entry is checked here (sums to zero per currency,
 * at least two lines, scale 2) and again by the deferred {@code postings_zero_sum} trigger at commit.
 */
@Service
public class LedgerService {

    /** One posting to be written. Signed: positive = money into the account. */
    public record Line(UUID accountId, String currency, BigDecimal amount) {
        public Line {
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(amount, "amount");
        }
    }

    private final AccountRepository accounts;
    private final EntityManager entityManager;

    public LedgerService(AccountRepository accounts, EntityManager entityManager) {
        this.accounts = accounts;
        this.entityManager = entityManager;
    }

    /**
     * Writes a balanced journal entry: locks the accounts (in id order), applies each line to its cached balance
     * and inserts the entry and its postings. Throws, and writes nothing, if an account is missing, in another
     * currency, or would go below zero without being allowed to.
     */
    @Transactional
    public JournalEntry post(EntryType type, List<Line> lines) {
        List<Line> checked = validate(lines);

        Set<UUID> ids = checked.stream().map(Line::accountId).collect(Collectors.toSet());
        Map<UUID, Account> locked = accounts.lockAllByIdOrdered(ids).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));

        for (Line line : checked) {
            Account account = locked.get(line.accountId());
            if (account == null) {
                throw new AccountNotFoundException(line.accountId());
            }
            if (!account.getCurrency().equals(line.currency())) {
                throw new CurrencyMismatchException(account.getId(), account.getCurrency(), line.currency());
            }
            if (account.wouldOverdraw(line.amount())) {
                throw new InsufficientFundsException(account.getId(), account.getBalance(), line.amount());
            }
            account.apply(line.amount());
        }

        JournalEntry entry = new JournalEntry(type);
        entityManager.persist(entry);
        entityManager.flush(); // the entry row must exist before the postings that reference it
        for (Line line : checked) {
            entityManager.persist(new Posting(entry.getId(), line.accountId(), line.currency(), line.amount()));
        }
        return entry;
    }

    /**
     * Rejects an entry that can't be valid, before any SQL: fewer than two lines, a zero amount, more than two
     * decimals (never rounded silently), or lines that don't sum to zero in every currency. Returns the lines with
     * amounts at scale 2.
     */
    public static List<Line> validate(List<Line> lines) {
        if (lines == null || lines.size() < 2) {
            throw new InvalidEntryException("a journal entry needs at least two postings");
        }
        Map<String, BigDecimal> totals = new TreeMap<>();
        List<Line> checked = new ArrayList<>(lines.size());
        for (Line line : lines) {
            if (line.amount().signum() == 0) {
                throw new InvalidEntryException("posting amounts must not be zero");
            }
            if (line.amount().stripTrailingZeros().scale() > 2) {
                throw new InvalidEntryException("amount " + line.amount() + " has more than two decimals");
            }
            BigDecimal amount = line.amount().setScale(2);
            totals.merge(line.currency(), amount, BigDecimal::add);
            checked.add(new Line(line.accountId(), line.currency(), amount));
        }
        totals.forEach((currency, total) -> {
            if (total.signum() != 0) {
                throw new InvalidEntryException("postings do not sum to zero: " + total + " " + currency + " left over");
            }
        });
        return checked;
    }
}
