package com.gopi.payments.e2e;

import static com.gopi.payments.e2e.FraudStub.Behavior.BLOCK;
import static com.gopi.payments.e2e.FraudStub.Behavior.HANG;
import static com.gopi.payments.e2e.FraudStub.Behavior.REVIEW;
import static com.gopi.payments.e2e.FraudStub.Behavior.SLOW;
import static com.gopi.payments.e2e.ServiceProcess.CrashPoint.beforeSend;
import static com.gopi.payments.e2e.ServiceProcess.CrashPoint.onReceive;
import static org.assertj.core.api.Assertions.assertThat;

import com.gopi.payments.e2e.Clients.Response;
import com.gopi.payments.e2e.ServiceProcess.CrashPoint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;

import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Item 7: the three services as real processes on real PostgreSQL and Kafka, a stubbed fraud API, and faults:
 * duplicate requests and messages, crashes between steps, an overdraft race, fraud-detection down/slow/REVIEW, and
 * Kafka unavailable. Every scenario ends with {@link #assertClean}: no money in open holds and a CLEAN
 * reconciliation (which covers every payment of the earlier scenarios too).
 * <p>
 * A crash is a real process death: SIGKILL from the test, or a Kafka interceptor that calls
 * {@code Runtime.halt} at an exact event (after the DB commit that queued it, before Kafka has it; or right after
 * a record is fetched). The service is then started again as a new process.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class FaultInjectionTest {

    private static final Duration FINAL = Duration.ofSeconds(45);
    private static final Duration CRASH = Duration.ofSeconds(30);

    private static Platform platform;
    private static Clients api;

    private UUID payer;
    private UUID merchant;
    private final List<UUID> payments = new ArrayList<>();

    @BeforeAll
    static void startPlatform() {
        platform = new Platform();
        api = new Clients(platform);
    }

    @AfterAll
    static void stopPlatform() {
        if (platform != null) {
            platform.close();
        }
    }

    /** A crashed service is always running again before the next scenario. */
    @AfterEach
    void servicesBackUp() {
        platform.services().filter(s -> !s.isAlive()).forEach(s -> s.startAndAwait(null));
    }

    // ---------------------------------------------------------------- 1. duplicate client requests

    @Test
    @Order(1)
    void duplicateClientRequestsMakeOnePayment() throws Exception {
        accounts("100.00");
        Map<String, Object> body = api.paymentBody(payer, merchant, "25.00", "card-idem");
        String key = "idem-" + UUID.randomUUID();

        Set<UUID> ids = ConcurrentHashMap.newKeySet();
        List<Integer> statuses = new java.util.concurrent.CopyOnWriteArrayList<>();
        concurrently(10, i -> {
            Response r = api.createPayment(key, body);
            statuses.add(r.status());
            if (r.status() == 201) {
                ids.add(UUID.fromString(r.body().get("paymentId").asString()));
            }
        });
        assertThat(statuses).allMatch(s -> s == 201 || s == 409).contains(201);
        for (int i = 0; i < 3; i++) {
            Response replay = api.createPayment(key, body);
            assertThat(replay.status()).isEqualTo(201);
            assertThat(replay.raw().headers().firstValue("Idempotent-Replayed")).hasValue("true");
            ids.add(UUID.fromString(replay.body().get("paymentId").asString()));
        }
        assertThat(ids).hasSize(1);
        assertThat(api.createPayment(key, api.paymentBody(payer, merchant, "26.00", "card-idem")).status())
                .isEqualTo(422);

        UUID id = ids.iterator().next();
        payments.add(id);
        api.awaitStatus(id, "COMPLETED", FINAL);
        assertThat(platform.queryLong("ledger", "select count(*) from holds where payer_account_id = ?", payer))
                .isEqualTo(1);
        assertThat(platform.fraud.posts("card-idem")).isEqualTo(1);
        assertClean("75.00", "25.00");
    }

    // ---------------------------------------------------------------- 2. duplicate Kafka messages

    @Test
    @Order(2)
    void duplicateMessagesOnEveryTopicMoveMoneyOnce() throws Exception {
        accounts("100.00");
        platform.fraud.script("card-dup-review", REVIEW);
        try (Duplicator duplicator = new Duplicator(platform)) {
            for (int i = 0; i < 4; i++) {
                payments.add(api.pay("dup-" + UUID.randomUUID(), api.paymentBody(payer, merchant, "10.00", "card-dup")));
            }
            UUID reviewed = api.pay("dup-" + UUID.randomUUID(),
                    api.paymentBody(payer, merchant, "10.00", "card-dup-review"));
            payments.add(reviewed);
            api.awaitStatus(reviewed, "PENDING_REVIEW", FINAL);
            api.review(reviewed, "approve");
            for (UUID id : payments) {
                api.awaitStatus(id, "COMPLETED", FINAL);
            }
            awaitQuiet();
            for (String topic : Duplicator.TOPICS) {
                assertThat(duplicator.originals(topic)).as(topic + " records duplicated").isPositive();
            }

            duplicator.replayAll();   // late duplicates, after everything is final
            awaitQuiet();
        }
        for (UUID id : payments) {
            assertThat(api.payment(id).get("status").asString()).isEqualTo("COMPLETED");
        }
        assertThat(platform.fraud.posts("card-dup")).isEqualTo(4);
        assertThat(platform.fraud.posts("card-dup-review")).isEqualTo(1);
        assertClean("50.00", "50.00");
    }

    // ---------------------------------------------------------------- 4. overdraft race

    @Test
    @Order(3)
    void concurrentPaymentsFromOneAccountNeverOverdraw() throws Exception {
        accounts("100.00");
        List<UUID> ids = new java.util.concurrent.CopyOnWriteArrayList<>();
        concurrently(20, i -> ids.add(api.pay("race-" + UUID.randomUUID(),
                api.paymentBody(payer, merchant, "10.00", "card-race"))));
        payments.addAll(ids);

        int completed = 0;
        int failed = 0;
        for (UUID id : ids) {
            JsonNode p = awaitFinal(id);
            if (p.get("status").asString().equals("COMPLETED")) {
                completed++;
            } else {
                failed++;
                assertThat(p.get("failureReason").asString()).isEqualTo("INSUFFICIENT_FUNDS");
            }
        }
        assertThat(completed).isEqualTo(10);
        assertThat(failed).isEqualTo(10);
        assertClean("0.00", "100.00");
    }

    // ---------------------------------------------------------------- 5. fraud API

    @Test
    @Order(4)
    void fraudApiDownFailsClosed() {
        accounts("100.00");
        platform.fraud.stop();
        try {
            UUID id = pay("20.00", "card-down");
            JsonNode p = api.awaitStatus(id, "FAILED", FINAL);
            assertThat(p.get("failureReason").asString()).isEqualTo("FRAUD_UNAVAILABLE");
        } finally {
            platform.fraud.start();
        }
        assertClean("100.00", "0.00");
    }

    @Test
    @Order(5)
    void fraudApiSlowFailsClosedWithoutAskingTwice() {
        accounts("100.00");
        platform.fraud.script("card-slow", SLOW);
        UUID id = pay("20.00", "card-slow");
        JsonNode p = api.awaitStatus(id, "FAILED", FINAL);
        assertThat(p.get("failureReason").asString()).isEqualTo("FRAUD_UNAVAILABLE");
        assertThat(platform.fraud.posts("card-slow")).isEqualTo(1);
        assertClean("100.00", "0.00");
    }

    @Test
    @Order(6)
    void reviewThenApproveCompletes() {
        accounts("100.00");
        platform.fraud.script("card-review-ok", REVIEW);
        UUID id = pay("30.00", "card-review-ok");
        api.awaitStatus(id, "PENDING_REVIEW", FINAL);
        assertThat(holdStatus(id)).isEqualTo("OPEN");   // the money waits in HOLDS during the review
        api.review(id, "approve");
        api.awaitStatus(id, "COMPLETED", FINAL);
        assertClean("70.00", "30.00");
    }

    @Test
    @Order(7)
    void reviewThenRejectFails() {
        accounts("100.00");
        platform.fraud.script("card-review-no", REVIEW);
        UUID id = pay("30.00", "card-review-no");
        api.awaitStatus(id, "PENDING_REVIEW", FINAL);
        api.review(id, "reject");
        JsonNode p = api.awaitStatus(id, "FAILED", FINAL);
        assertThat(p.get("failureReason").asString()).isEqualTo("FRAUD_REJECTED");
        assertClean("100.00", "0.00");
    }

    @Test
    @Order(8)
    void blockedFails() {
        accounts("100.00");
        platform.fraud.script("card-block", BLOCK);
        UUID id = pay("30.00", "card-block");
        JsonNode p = api.awaitStatus(id, "FAILED", FINAL);
        assertThat(p.get("failureReason").asString()).isEqualTo("FRAUD_BLOCKED");
        assertClean("100.00", "0.00");
    }

    // ---------------------------------------------------------------- 6. Kafka unavailable

    @Test
    @Order(9)
    void kafkaUnavailableForAWhileThenBack() {
        accounts("100.00");
        UUID first = pay("10.00", "card-kafka");
        awaitHold(first);                       // mid-flow: held, not yet settled... or already done
        platform.pauseKafka();
        try {
            for (int i = 0; i < 4; i++) {
                pay("10.00", "card-kafka");     // payment-api answers: the event waits in its outbox
            }
            ServiceProcess.sleep(15_000);
        } finally {
            platform.unpauseKafka();
        }
        for (UUID id : payments) {
            api.awaitStatus(id, "COMPLETED", Duration.ofSeconds(90));
        }
        assertClean("50.00", "50.00");
    }

    // ---------------------------------------------------------------- 3. crashes between steps

    @Test
    @Order(10)
    void paymentApiDiesBeforePublishingThePayment() {
        accounts("100.00");
        platform.restart(platform.paymentApi, beforeSend("PaymentInitiated"));
        String key = "crash-api-" + UUID.randomUUID();
        Map<String, Object> body = api.paymentBody(payer, merchant, "20.00", "card-crash-api");
        UUID id = null;
        try {
            id = api.pay(key, body);
        } catch (UncheckedIOException e) {
            // it may die before the response is written; the client retries with the same key below
        }
        assertThat(platform.paymentApi.awaitCrash(CRASH)).isEqualTo(137);
        platform.paymentApi.startAndAwait(null);
        UUID retried = api.pay(key, body);     // the client's retry: same payment
        if (id != null) {
            assertThat(retried).isEqualTo(id);
        }
        payments.add(retried);
        api.awaitStatus(retried, "COMPLETED", FINAL);
        assertClean("80.00", "20.00");
    }

    @Test
    @Order(11)
    void ledgerDiesAfterTheHoldBeforeTheFraudCheck() {
        crashAndRecover(platform.ledger, beforeSend("FundsHeld"), "card-crash-held", id -> {
            assertThat(holdStatus(id)).isEqualTo("OPEN");   // committed; FundsHeld never reached Kafka
            assertThat(platform.fraud.posts("card-crash-held")).isZero();
        });
        assertThat(platform.fraud.posts("card-crash-held")).isEqualTo(1);
        assertClean("80.00", "20.00");
    }

    @Test
    @Order(12)
    void processorDiesInTheMiddleOfTheFraudCheck() throws Exception {
        accounts("100.00");
        platform.fraud.script("card-crash-check", HANG);
        UUID id = pay("20.00", "card-crash-check");
        platform.fraud.awaitHeld(CRASH);                 // saga is in FRAUD_CHECK, the POST is in flight
        platform.processor.kill();
        platform.fraud.releaseHeld();
        assertThat(api.payment(id).get("status").asString()).isEqualTo("CREATED");
        platform.processor.startAndAwait(null);

        JsonNode p = api.awaitStatus(id, "FAILED", FINAL);   // the sweep fails the stale check closed
        assertThat(p.get("failureReason").asString()).isEqualTo("FRAUD_UNAVAILABLE");
        assertThat(platform.fraud.posts("card-crash-check")).isEqualTo(1);   // never asked again
        assertClean("100.00", "0.00");
    }

    @Test
    @Order(13)
    void processorDiesOnReceivingFundsHeld() {
        crashAndRecover(platform.processor, onReceive("FundsHeld"), "card-crash-recv", id ->
                assertThat(platform.fraud.posts("card-crash-recv")).isZero());
        assertThat(platform.fraud.posts("card-crash-recv")).isEqualTo(1);
        assertClean("80.00", "20.00");
    }

    @Test
    @Order(14)
    void ledgerDiesAfterTheCaptureBeforeTheOutcomeEvent() {
        crashAndRecover(platform.ledger, beforeSend("HoldCaptured"), "card-crash-capture", id ->
                assertThat(holdStatus(id)).isEqualTo("CAPTURED"));
        assertClean("80.00", "20.00");
    }

    @Test
    @Order(15)
    void processorDiesAfterCompletingBeforePublishingTheOutcome() {
        crashAndRecover(platform.processor, beforeSend("PaymentCompleted"), "card-crash-done", id -> {
            assertThat(platform.queryString("processor", "select state from sagas where payment_id = ?", id))
                    .isEqualTo("COMPLETED");
            assertThat(api.payment(id).get("status").asString()).isEqualTo("CREATED");
        });
        assertClean("80.00", "20.00");
    }

    // ---------------------------------------------------------------- helpers

    /** Arms {@code service} to halt at {@code crash}, pays 20.00, checks the state at the crash, restarts plain. */
    private void crashAndRecover(ServiceProcess service, CrashPoint crash, String cardId,
                                 java.util.function.Consumer<UUID> atCrash) {
        accounts("100.00");
        platform.restart(service, crash);
        UUID id = pay("20.00", cardId);
        assertThat(service.awaitCrash(CRASH)).isEqualTo(137);
        assertThat(service.logText()).contains("E2E CRASH");
        atCrash.accept(id);
        service.startAndAwait(null);
        api.awaitStatus(id, "COMPLETED", FINAL);
    }

    private void accounts(String deposit) {
        String run = UUID.randomUUID().toString().substring(0, 8);
        payer = api.account("CUSTOMER", "e2e-payer-" + run);
        merchant = api.account("MERCHANT", "e2e-merchant-" + run);
        api.deposit(payer, deposit);
    }

    private UUID pay(String amount, String cardId) {
        UUID id = api.pay("e2e-" + UUID.randomUUID(), api.paymentBody(payer, merchant, amount, cardId));
        payments.add(id);
        return id;
    }

    private JsonNode awaitFinal(UUID id) {
        long deadline = System.nanoTime() + FINAL.toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode p = api.payment(id);
            if (Clients.FINAL.contains(p.get("status").asString())) {
                return p;
            }
            ServiceProcess.sleep(200);
        }
        throw new AssertionError("payment " + id + " not final within " + FINAL);
    }

    private String holdStatus(UUID paymentId) {
        return platform.queryString("ledger", "select status from holds where payment_id = ?", paymentId);
    }

    private void awaitHold(UUID paymentId) {
        long deadline = System.nanoTime() + FINAL.toNanos();
        while (holdStatus(paymentId) == null) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("no hold for " + paymentId);
            }
            ServiceProcess.sleep(50);
        }
    }

    /** Every service's outbox drained and no record left to consume: duplicates had their chance to do harm. */
    private void awaitQuiet() {
        long deadline = System.nanoTime() + FINAL.toNanos();
        while (System.nanoTime() < deadline) {
            long queued = 0;
            for (String db : List.of("payments", "processor", "ledger")) {
                queued += platform.queryLong(db, "select count(*) from outbox where published_at is null");
            }
            if (queued == 0) {
                ServiceProcess.sleep(3000);   // consumers handle what was just published
                return;
            }
            ServiceProcess.sleep(200);
        }
        throw new AssertionError("outboxes not drained within " + FINAL);
    }

    /**
     * The end of every scenario: all its payments final, the expected balances, no money stuck in HOLDS, nothing
     * dead-lettered, and a CLEAN reconciliation across all three services.
     */
    private void assertClean(String payerBalance, String merchantBalance) {
        for (UUID id : payments) {
            awaitFinal(id);
        }
        assertThat(api.balance(payer)).isEqualByComparingTo(payerBalance);
        assertThat(api.balance(merchant)).isEqualByComparingTo(merchantBalance);
        assertThat(platform.queryLong("ledger", "select count(*) from holds where status = 'OPEN'")).isZero();
        assertThat(new BigDecimal(platform.queryString("ledger",
                "select balance from accounts where kind = 'HOLDS' and currency = 'USD'")))
                .isEqualByComparingTo("0.00");
        assertThat(platform.deadLetters()).as("records on *.DLT topics").isZero();

        JsonNode report = api.reconcile();
        assertThat(report.get("status").asString()).as(report.toString()).isEqualTo("CLEAN");
        assertThat(report.get("mismatchCount").asInt()).isZero();
    }

    private interface Task {
        void run(int i) throws Exception;
    }

    /** Runs {@code n} tasks released at the same moment; rethrows the first failure. */
    private static void concurrently(int n, Task task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    go.await();
                    task.run(index);
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
