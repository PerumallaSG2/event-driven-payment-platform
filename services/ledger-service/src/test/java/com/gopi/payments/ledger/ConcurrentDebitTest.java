package com.gopi.payments.ledger;

import com.gopi.payments.ledger.domain.AccountKind;
import com.gopi.payments.ledger.domain.Hold;
import com.gopi.payments.ledger.service.AccountService;
import com.gopi.payments.ledger.service.HoldNotOpenException;
import com.gopi.payments.ledger.service.HoldService;
import com.gopi.payments.ledger.service.InsufficientFundsException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Many transactions debiting one account at the same moment. The row lock in LedgerService.post serialises them,
 * so exactly as many succeed as the balance covers and the account never goes below zero.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestcontainer.class, KafkaTestcontainer.class})
class ConcurrentDebitTest {

    private static final int THREADS = 16;

    @Autowired AccountService accounts;
    @Autowired HoldService holds;
    @Autowired JdbcTemplate jdbc;

    private final String currency = TestCurrencies.next();
    private final ExecutorService pool = Executors.newFixedThreadPool(THREADS);

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    @Test
    void concurrentHoldsNeverOverdrawTheAccount() throws Exception {
        UUID payer = accounts.create("payer", AccountKind.CUSTOMER, currency, false).getId();
        UUID merchant = accounts.create("merchant", AccountKind.MERCHANT, currency, false).getId();
        accounts.deposit(payer, new BigDecimal("100.00"));

        int attempts = 50;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    holds.hold(payer, merchant, new BigDecimal("10.00"), currency);
                    return true;
                } catch (InsufficientFundsException e) {
                    return false;
                }
            }));
        }
        start.countDown();

        int succeeded = 0;
        for (Future<Boolean> result : results) {
            if (result.get(60, TimeUnit.SECONDS)) { // any other exception (e.g. a deadlock) fails the test here
                succeeded++;
            }
        }

        assertThat(succeeded).isEqualTo(10);
        assertThat(accounts.get(payer).getBalance()).isEqualByComparingTo("0.00");
        assertThat(accounts.get(accounts.systemAccountId(AccountKind.HOLDS, currency)).getBalance())
                .isEqualByComparingTo("100.00");
        assertThat(jdbc.queryForObject("select count(*) from holds where payer_account_id = ?", Long.class, payer))
                .isEqualTo(10);

        // The payer's history, replayed in the order the postings were written, never dips below zero.
        assertThat(jdbc.queryForObject("""
                select min(running) from (
                    select sum(amount) over (order by id) as running from postings where account_id = ?
                ) h
                """, BigDecimal.class, payer)).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        // Cached balance = sum of postings, and the currency as a whole sums to zero.
        assertThat(jdbc.queryForObject("select sum(amount) from postings where account_id = ?",
                BigDecimal.class, payer)).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("select sum(amount) from postings where currency = ?",
                BigDecimal.class, currency)).isEqualByComparingTo("0");
    }

    @Test
    void concurrentCaptureAndReleaseOfOneHoldSettleItExactlyOnce() throws Exception {
        UUID payer = accounts.create("payer", AccountKind.CUSTOMER, currency, false).getId();
        UUID merchant = accounts.create("merchant", AccountKind.MERCHANT, currency, false).getId();
        accounts.deposit(payer, new BigDecimal("100.00"));

        int rounds = 10;
        int captured = 0;
        for (int i = 0; i < rounds; i++) {
            Hold hold = holds.hold(payer, merchant, new BigDecimal("5.00"), currency);
            CountDownLatch start = new CountDownLatch(1);
            Future<Boolean> capture = pool.submit(settleOnce(start, () -> holds.capture(hold.getId())));
            Future<Boolean> release = pool.submit(settleOnce(start, () -> holds.release(hold.getId())));
            start.countDown();

            boolean c = capture.get(30, TimeUnit.SECONDS);
            boolean r = release.get(30, TimeUnit.SECONDS);
            assertThat(c ^ r).as("exactly one of capture/release wins, round %d", i).isTrue();
            if (c) {
                captured++;
            }
        }

        BigDecimal capturedTotal = new BigDecimal("5.00").multiply(BigDecimal.valueOf(captured));
        assertThat(accounts.get(merchant).getBalance()).isEqualByComparingTo(capturedTotal);
        assertThat(accounts.get(payer).getBalance()).isEqualByComparingTo(new BigDecimal("100.00").subtract(capturedTotal));
        assertThat(accounts.get(accounts.systemAccountId(AccountKind.HOLDS, currency)).getBalance())
                .isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("select count(*) from holds where payer_account_id = ? and status = 'OPEN'",
                Long.class, payer)).isZero();
    }

    private static Callable<Boolean> settleOnce(CountDownLatch start, Runnable settle) {
        return () -> {
            start.await();
            try {
                settle.run();
                return true;
            } catch (HoldNotOpenException e) {
                return false;
            }
        };
    }
}
