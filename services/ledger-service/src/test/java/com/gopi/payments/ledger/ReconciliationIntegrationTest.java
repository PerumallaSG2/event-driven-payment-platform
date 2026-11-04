package com.gopi.payments.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.gopi.payments.ledger.domain.AccountKind;
import com.gopi.payments.ledger.domain.Hold;
import com.gopi.payments.ledger.reconciliation.Mismatch;
import com.gopi.payments.ledger.reconciliation.MismatchKind;
import com.gopi.payments.ledger.reconciliation.Reconciliation;
import com.gopi.payments.ledger.reconciliation.ReconciliationReport;
import com.gopi.payments.ledger.reconciliation.ReconciliationReport.Status;
import com.gopi.payments.ledger.service.AccountService;
import com.gopi.payments.ledger.service.HoldService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The reconciliation over a real PostgreSQL, with {@link PeerStub} playing payment-api and payment-processor. Each
 * test creates one kind of mismatch on purpose and checks it is the one reported. This class has its own Spring
 * context (the peer URLs) and so its own database: it empties the ledger before each test (TRUNCATE, which the
 * append-only row triggers don't cover) and its corruption can't reach other test classes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "reconciliation.page-size=2", "reconciliation.max-stored-mismatches=5"})
@Import({PostgresTestcontainer.class, KafkaTestcontainer.class})
class ReconciliationIntegrationTest {

    private static final PeerStub PEERS = PeerStub.start();

    @DynamicPropertySource
    static void peers(DynamicPropertyRegistry registry) {
        registry.add("reconciliation.payment-api-url", () -> PEERS.baseUrl().toString());
        registry.add("reconciliation.processor-url", () -> PEERS.baseUrl().toString());
    }

    @AfterAll
    static void stopPeers() {
        PEERS.close();
    }

    @LocalServerPort int port;
    @Autowired Reconciliation reconciliation;
    @Autowired AccountService accounts;
    @Autowired HoldService holds;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper objectMapper;

    private final String currency = TestCurrencies.next();
    private UUID payer;
    private UUID merchant;

    @BeforeEach
    void emptyLedger() {
        jdbc.execute("TRUNCATE reconciliation_mismatches, reconciliation_runs, postings, holds, journal_entries, "
                + "accounts, voided_payments, inbox, outbox CASCADE");
        PEERS.reset();
        payer = accounts.create("alice", AccountKind.CUSTOMER, currency, false).getId();
        merchant = accounts.create("shop", AccountKind.MERCHANT, currency, false).getId();
        accounts.deposit(payer, new BigDecimal("1000.00"));
    }

    @Test
    void aConsistentLedgerAndPeersReportNothing() {
        UUID completed = held("10.00");
        holds.captureForPayment(completed);
        UUID released = held("20.00");
        holds.releaseForPayment(released);
        UUID neverHeld = UUID.randomUUID();
        holds.releaseForPayment(neverHeld);          // voided: the hold was rejected or timed out
        held("30.00");                               // a payment still in progress: open, not final in payment-api
        holds.hold(payer, merchant, new BigDecimal("40.00"), currency);   // a REST hold, left open
        PEERS.payment(completed, "COMPLETED", "10.00", currency, null);
        PEERS.payment(released, "FAILED", "20.00", currency, "FRAUD_BLOCKED");
        PEERS.payment(neverHeld, "FAILED", "20.00", currency, "HOLD_TIMEOUT");

        ReconciliationReport report = reconciliation.run();

        assertThat(report.status()).isEqualTo(Status.CLEAN);
        assertThat(report.mismatchCount()).isZero();
        assertThat(report.mismatches()).isEmpty();
        assertThat(report.paymentsChecked()).isEqualTo(3);
        assertThat(PEERS.paymentPages()).isEqualTo(2);   // page size 2
        // the HOLDS account nets to exactly the open holds: 30.00 + 40.00
        assertThat(jdbc.queryForObject("SELECT sum(p.amount) FROM postings p JOIN accounts a ON a.id = p.account_id "
                + "WHERE a.kind = 'HOLDS' AND a.currency = ?", BigDecimal.class, currency)).isEqualByComparingTo("70.00");
    }

    @Test
    void anEntryThatDoesNotSumToZeroIsReported() {
        UUID entry = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            // replica mode skips the deferred zero-sum trigger (the container user is a superuser)
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("INSERT INTO journal_entries (id, type, created_at) VALUES (?, 'DEPOSIT', now())", entry);
            jdbc.update("INSERT INTO postings (entry_id, account_id, currency, amount) VALUES (?, ?, ?, 5.00)",
                    entry, payer, currency);
            jdbc.update("UPDATE accounts SET balance = balance + 5.00 WHERE id = ?", payer);   // only the entry is off
        });

        assertOnly(reconciliation.run(), MismatchKind.ENTRY_UNBALANCED, entry.toString());
    }

    @Test
    void aBalanceThatDriftedFromItsPostingsIsReported() {
        jdbc.update("UPDATE accounts SET balance = balance + 1 WHERE id = ?", payer);

        ReconciliationReport report = reconciliation.run();

        assertOnly(report, MismatchKind.BALANCE_DRIFT, payer.toString());
        assertThat(report.mismatches().get(0).details()).isEqualTo("balance 1001.00 " + currency
                + ", postings sum to 1000.00");
    }

    @Test
    void aHoldsAccountThatDoesNotNetToTheOpenHoldsIsReported() {
        Hold hold = holds.hold(payer, merchant, new BigDecimal("25.00"), currency);
        holds.release(hold.getId());
        jdbc.update("UPDATE holds SET status = 'OPEN', settle_entry_id = NULL, settled_at = NULL WHERE id = ?",
                hold.getId());

        ReconciliationReport report = reconciliation.run();

        assertOnly(report, MismatchKind.HOLDS_ACCOUNT_DRIFT, currency);
        assertThat(report.mismatches().get(0).details()).isEqualTo("HOLDS account nets to 0.00, open holds total 25.00");
    }

    @Test
    void aCompletedPaymentWithoutACapturedHoldIsReported() {
        UUID released = held("10.00");
        holds.releaseForPayment(released);
        UUID noHold = UUID.randomUUID();
        PEERS.payment(released, "COMPLETED", "10.00", currency, null);
        PEERS.payment(noHold, "COMPLETED", "10.00", currency, null);

        ReconciliationReport report = reconciliation.run();

        assertThat(report.status()).isEqualTo(Status.MISMATCHES);
        assertThat(report.mismatches()).containsExactlyInAnyOrder(
                new Mismatch(MismatchKind.COMPLETED_NOT_CAPTURED, released.toString(),
                        "COMPLETED in payment-api, hold RELEASED"),
                new Mismatch(MismatchKind.COMPLETED_NOT_CAPTURED, noHold.toString(),
                        "COMPLETED in payment-api, hold none"));
    }

    @Test
    void aCaptureForADifferentAmountIsReported() {
        UUID payment = held("10.00");
        holds.captureForPayment(payment);
        PEERS.payment(payment, "COMPLETED", "12.00", currency, null);

        assertOnly(reconciliation.run(), MismatchKind.CAPTURE_AMOUNT_MISMATCH, payment.toString());
    }

    @Test
    void aFailedPaymentWhoseHoldWasCapturedIsReported() {
        UUID payment = held("10.00");
        holds.captureForPayment(payment);
        PEERS.payment(payment, "FAILED", "10.00", currency, "FRAUD_BLOCKED");

        assertOnly(reconciliation.run(), MismatchKind.FAILED_BUT_CAPTURED, payment.toString());
    }

    @Test
    void anOpenHoldForAFinalPaymentIsReported() {
        UUID completed = held("10.00");
        UUID failed = held("20.00");
        PEERS.payment(completed, "COMPLETED", "10.00", currency, null);
        PEERS.payment(failed, "FAILED", "20.00", currency, "REVIEW_TIMEOUT");

        ReconciliationReport report = reconciliation.run();

        assertThat(report.mismatches()).extracting(Mismatch::kind, Mismatch::subject).containsExactlyInAnyOrder(
                tuple(MismatchKind.OPEN_HOLD_FOR_FINAL_PAYMENT, completed.toString()),
                tuple(MismatchKind.OPEN_HOLD_FOR_FINAL_PAYMENT, failed.toString()));
    }

    @Test
    void aStuckSagaIsReported() {
        UUID payment = UUID.randomUUID();
        PEERS.stuckSaga(payment, "PENDING_REVIEW", "past its deadline");

        ReconciliationReport report = reconciliation.run();

        assertOnly(report, MismatchKind.SAGA_STUCK, payment.toString());
        assertThat(report.mismatches().get(0).details()).startsWith("PENDING_REVIEW: past its deadline");
    }

    @Test
    void unreachablePeersMakeTheRunIncompleteButTheLedgerIsStillChecked() {
        PEERS.down(true);
        jdbc.update("UPDATE accounts SET balance = balance + 1 WHERE id = ?", payer);

        ReconciliationReport report = reconciliation.run();

        assertThat(report.status()).isEqualTo(Status.INCOMPLETE);
        assertThat(report.mismatches()).extracting(Mismatch::kind, Mismatch::subject).containsExactlyInAnyOrder(
                tuple(MismatchKind.BALANCE_DRIFT, payer.toString()),
                tuple(MismatchKind.SOURCE_UNAVAILABLE, "payment-api"),
                tuple(MismatchKind.SOURCE_UNAVAILABLE, "payment-processor"));
    }

    @Test
    void storedMismatchesAreCappedButTheCountIsExact() {
        for (int i = 0; i < 7; i++) {
            UUID account = accounts.create("drift-" + i, AccountKind.CUSTOMER, currency, false).getId();
            jdbc.update("UPDATE accounts SET balance = 1 WHERE id = ?", account);
        }

        ReconciliationReport report = reconciliation.run();

        assertThat(report.mismatchCount()).isEqualTo(7);
        assertThat(report.mismatches()).hasSize(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_mismatches WHERE run_id = ?",
                Integer.class, report.id())).isEqualTo(5);
    }

    @Test
    void runsAreStartedAndReadOverHttp() {
        jdbc.update("UPDATE accounts SET balance = balance + 1 WHERE id = ?", payer);
        RestClient http = RestClient.create("http://localhost:" + port);

        Reply created = exchange(http.post().uri("/reconciliation/runs"));
        assertThat(created.status()).isEqualTo(HttpStatus.CREATED);
        JsonNode run = objectMapper.readTree(created.body());
        String id = run.get("id").asString();
        assertThat(created.location()).isEqualTo("/reconciliation/runs/" + id);
        assertThat(run.get("status").asString()).isEqualTo("MISMATCHES");
        assertThat(run.get("mismatchCount").intValue()).isEqualTo(1);
        assertThat(run.get("mismatches").get(0).get("kind").asString()).isEqualTo("BALANCE_DRIFT");

        Reply latest = exchange(http.get().uri("/reconciliation/runs/latest"));
        assertThat(latest.status()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(latest.body())).isEqualTo(run);
        Reply byId = exchange(http.get().uri("/reconciliation/runs/" + id));
        assertThat(objectMapper.readTree(byId.body())).isEqualTo(run);
        assertThat(exchange(http.get().uri("/reconciliation/runs/" + UUID.randomUUID())).status())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private record Reply(HttpStatusCode status, String location, String body) {}

    private static Reply exchange(RestClient.RequestHeadersSpec<?> request) {
        return request.exchange((req, res) -> new Reply(res.getStatusCode(), res.getHeaders().getFirst("Location"),
                new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    /** A payment's hold, as the saga's HoldFunds places it. */
    private UUID held(String amount) {
        UUID paymentId = UUID.randomUUID();
        holds.holdForPayment(paymentId, payer, merchant, new BigDecimal(amount), currency);
        return paymentId;
    }

    private static void assertOnly(ReconciliationReport report, MismatchKind kind, String subject) {
        assertThat(report.status()).isEqualTo(Status.MISMATCHES);
        assertThat(report.mismatchCount()).isEqualTo(1);
        assertThat(report.mismatches()).extracting(Mismatch::kind, Mismatch::subject)
                .containsExactly(tuple(kind, subject));
    }
}
