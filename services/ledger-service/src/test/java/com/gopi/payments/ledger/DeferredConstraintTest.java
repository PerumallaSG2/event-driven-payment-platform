package com.gopi.payments.ledger;

import com.gopi.payments.ledger.domain.AccountKind;
import com.gopi.payments.ledger.service.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The database half of the zero-sum rule. Writes raw SQL, bypassing LedgerService's checks, to show the deferred
 * constraint triggers catch what the code would have: an entry can be unbalanced mid-transaction, but not at commit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestcontainer.class)
class DeferredConstraintTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired AccountService accounts;

    private final String currency = TestCurrencies.next();
    private UUID from;
    private UUID to;

    @BeforeEach
    void accounts() {
        from = accounts.create("from", AccountKind.CUSTOMER, currency, true).getId();
        to = accounts.create("to", AccountKind.CUSTOMER, currency, true).getId();
    }

    @Test
    void unbalancedEntryFailsAtCommitNotAtInsert() {
        UUID entry = UUID.randomUUID();
        AtomicBoolean insertsSucceeded = new AtomicBoolean();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            insertEntry(entry);
            insertPosting(entry, from, "-5.00");
            insertPosting(entry, to, "4.00");
            insertsSucceeded.set(true);
        })).rootCause().hasMessageContaining("does not sum to zero");

        assertThat(insertsSucceeded).as("the check is deferred to commit").isTrue();
        assertThat(entryCount(entry)).isZero();
    }

    @Test
    void entryMayBeUnbalancedInsideTheTransactionIfItBalancesByCommit() {
        UUID entry = UUID.randomUUID();

        tx.executeWithoutResult(s -> {
            insertEntry(entry);
            insertPosting(entry, from, "-5.00");
            assertThat(jdbc.queryForObject("select sum(amount) from postings where entry_id = ?",
                    BigDecimal.class, entry)).isEqualByComparingTo("-5.00");
            insertPosting(entry, to, "5.00");
            // keep the cached balances equal to the postings, as LedgerService would
            jdbc.update("update accounts set balance = balance - 5 where id = ?", from);
            jdbc.update("update accounts set balance = balance + 5 where id = ?", to);
        });

        assertThat(entryCount(entry)).isOne();
    }

    @Test
    void entryWithoutPostingsFailsAtCommit() {
        UUID entry = UUID.randomUUID();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> insertEntry(entry)))
                .rootCause().hasMessageContaining("fewer than two postings");
        assertThat(entryCount(entry)).isZero();
    }

    @Test
    void entryWithOnePostingFailsAtCommit() {
        UUID entry = UUID.randomUUID();
        // Note: one posting is also unbalanced, so either deferred trigger may report it first.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            insertEntry(entry);
            insertPosting(entry, from, "-5.00");
        })).rootCause().hasMessageMatching("(?s).*(does not sum to zero|fewer than two postings).*");
        assertThat(entryCount(entry)).isZero();
    }

    @Test
    void postingsAndEntriesAreAppendOnly() {
        UUID entry = accounts.deposit(from, new BigDecimal("10.00")).getId();

        assertThatThrownBy(() -> jdbc.update("update postings set amount = amount * 2 where entry_id = ?", entry))
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from postings where entry_id = ?", entry))
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from journal_entries where id = ?", entry))
                .rootCause().hasMessageContaining("append-only");
        assertThat(jdbc.queryForObject("select count(*) from postings where entry_id = ?", Long.class, entry))
                .isEqualTo(2);
    }

    @Test
    void overdraftIsAlsoBlockedByTheDatabase() {
        UUID noOverdraft = accounts.create("strict", AccountKind.CUSTOMER, currency, false).getId();
        assertThatThrownBy(() -> jdbc.update("update accounts set balance = -0.01 where id = ?", noOverdraft))
                .rootCause().hasMessageContaining("accounts_no_overdraft");
    }

    @Test
    void postingInAnotherCurrencyThanItsAccountIsRejected() {
        UUID entry = UUID.randomUUID();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            insertEntry(entry);
            jdbc.update("insert into postings (entry_id, account_id, currency, amount) values (?, ?, ?, ?)",
                    entry, from, TestCurrencies.next(), new BigDecimal("-1.00"));
        })).rootCause().hasMessageContaining("postings_account_currency_fk");
    }

    private void insertEntry(UUID entry) {
        jdbc.update("insert into journal_entries (id, type, created_at) values (?, 'DEPOSIT', ?)",
                entry, OffsetDateTime.now());
    }

    private void insertPosting(UUID entry, UUID account, String amount) {
        jdbc.update("insert into postings (entry_id, account_id, currency, amount) values (?, ?, ?, ?)",
                entry, account, currency, new BigDecimal(amount));
    }

    private long entryCount(UUID entry) {
        return jdbc.queryForObject("select count(*) from journal_entries where id = ?", Long.class, entry);
    }
}
