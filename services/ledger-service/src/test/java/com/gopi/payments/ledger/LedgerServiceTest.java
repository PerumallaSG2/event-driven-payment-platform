package com.gopi.payments.ledger;

import com.gopi.payments.ledger.domain.AccountKind;
import com.gopi.payments.ledger.domain.Hold;
import com.gopi.payments.ledger.domain.HoldStatus;
import com.gopi.payments.ledger.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestcontainer.class)
class LedgerServiceTest {

    @Autowired AccountService accounts;
    @Autowired HoldService holds;
    @Autowired JdbcTemplate jdbc;

    private final String currency = TestCurrencies.next();

    @Test
    void holdThenCaptureMovesMoneyToTheMerchant() {
        UUID payer = customerWith("100.00");
        UUID merchant = merchant();

        Hold hold = holds.hold(payer, merchant, new BigDecimal("30.00"), currency);
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.OPEN);
        assertBalance(payer, "70.00");
        assertBalance(merchant, "0.00");
        assertBalance(holdsAccount(), "30.00");

        Hold captured = holds.capture(hold.getId());
        assertThat(captured.getStatus()).isEqualTo(HoldStatus.CAPTURED);
        assertThat(captured.getSettleEntryId()).isNotNull();
        assertBalance(payer, "70.00");
        assertBalance(merchant, "30.00");
        assertBalance(holdsAccount(), "0.00");
        assertLedgerConsistent();
    }

    @Test
    void holdThenReleaseGivesTheMoneyBack() {
        UUID payer = customerWith("100.00");
        UUID merchant = merchant();

        Hold hold = holds.hold(payer, merchant, new BigDecimal("30.00"), currency);
        Hold released = holds.release(hold.getId());

        assertThat(released.getStatus()).isEqualTo(HoldStatus.RELEASED);
        assertBalance(payer, "100.00");
        assertBalance(merchant, "0.00");
        assertBalance(holdsAccount(), "0.00");
        assertLedgerConsistent();
    }

    @Test
    void aSettledHoldCannotBeCapturedOrReleasedAgain() {
        UUID payer = customerWith("100.00");
        UUID merchant = merchant();
        Hold hold = holds.hold(payer, merchant, new BigDecimal("30.00"), currency);
        holds.capture(hold.getId());

        assertThatThrownBy(() -> holds.capture(hold.getId())).isInstanceOf(HoldNotOpenException.class);
        assertThatThrownBy(() -> holds.release(hold.getId())).isInstanceOf(HoldNotOpenException.class);
        assertBalance(payer, "70.00");
        assertBalance(merchant, "30.00");
        assertBalance(holdsAccount(), "0.00");
    }

    @Test
    void holdBeyondTheBalanceIsRejectedAndWritesNothing() {
        UUID payer = customerWith("50.00");
        UUID merchant = merchant();
        long entriesBefore = count("journal_entries");
        long postingsBefore = count("postings");
        long holdsBefore = count("holds");

        assertThatThrownBy(() -> holds.hold(payer, merchant, new BigDecimal("50.01"), currency))
                .isInstanceOf(InsufficientFundsException.class);

        assertBalance(payer, "50.00");
        assertThat(count("journal_entries")).isEqualTo(entriesBefore);
        assertThat(count("postings")).isEqualTo(postingsBefore);
        assertThat(count("holds")).isEqualTo(holdsBefore);
    }

    @Test
    void anAccountThatAllowsNegativeCanGoBelowZero() {
        UUID payer = accounts.create("overdraft", AccountKind.CUSTOMER, currency, true).getId();
        holds.hold(payer, merchant(), new BigDecimal("25.00"), currency);
        assertBalance(payer, "-25.00");
    }

    @Test
    void currencyMismatchIsRejected() {
        UUID payer = customerWith("100.00");
        UUID otherCurrencyMerchant = accounts.create("m", AccountKind.MERCHANT, TestCurrencies.next(), false).getId();

        assertThatThrownBy(() -> holds.hold(payer, otherCurrencyMerchant, new BigDecimal("1.00"), currency))
                .isInstanceOf(CurrencyMismatchException.class);
        // payer in currency X, hold requested in another currency with a merchant in that currency
        String other = TestCurrencies.next();
        UUID merchantInOther = accounts.create("m2", AccountKind.MERCHANT, other, false).getId();
        assertThatThrownBy(() -> holds.hold(payer, merchantInOther, new BigDecimal("1.00"), other))
                .isInstanceOf(CurrencyMismatchException.class);
        assertBalance(payer, "100.00");
    }

    @Test
    void unknownAccountsAndHoldsAreNotFound() {
        UUID merchant = merchant();
        assertThatThrownBy(() -> holds.hold(UUID.randomUUID(), merchant, new BigDecimal("1.00"), currency))
                .isInstanceOf(AccountNotFoundException.class);
        assertThatThrownBy(() -> holds.capture(UUID.randomUUID())).isInstanceOf(HoldNotFoundException.class);
        assertThatThrownBy(() -> accounts.deposit(UUID.randomUUID(), BigDecimal.ONE))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void systemAccountsCannotBeCreatedThroughTheService() {
        assertThatThrownBy(() -> accounts.create("x", AccountKind.HOLDS, currency, true))
                .isInstanceOf(InvalidEntryException.class);
    }

    private UUID customerWith(String amount) {
        UUID id = accounts.create("customer", AccountKind.CUSTOMER, currency, false).getId();
        accounts.deposit(id, new BigDecimal(amount));
        return id;
    }

    private UUID merchant() {
        return accounts.create("merchant", AccountKind.MERCHANT, currency, false).getId();
    }

    private UUID holdsAccount() {
        return accounts.systemAccountId(AccountKind.HOLDS, currency);
    }

    private void assertBalance(UUID account, String expected) {
        assertThat(accounts.get(account).getBalance()).isEqualByComparingTo(expected);
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    /** Every cached balance equals the sum of its postings, and this currency's postings sum to zero. */
    private void assertLedgerConsistent() {
        assertThat(jdbc.queryForList("""
                select a.id from accounts a left join postings p on p.account_id = a.id
                where a.currency = ? group by a.id, a.balance having a.balance <> coalesce(sum(p.amount), 0)
                """, currency)).isEmpty();
        assertThat(jdbc.queryForObject("select coalesce(sum(amount), 0) from postings where currency = ?",
                BigDecimal.class, currency)).isEqualByComparingTo("0");
    }
}
