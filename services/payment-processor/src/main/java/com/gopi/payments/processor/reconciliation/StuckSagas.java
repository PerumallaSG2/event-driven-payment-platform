package com.gopi.payments.processor.reconciliation;

import com.gopi.payments.processor.saga.SagaProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Sagas stuck past their deadline, i.e. ones the sweep should already have moved on (read-only):
 * <ul>
 *   <li>HOLD_REQUESTED or PENDING_REVIEW past {@code deadline_at} + grace (the sweep compensates those);</li>
 *   <li>FRAUD_CHECK unfinished for {@code saga.fraud-check-stale-after} + grace (the sweep fails those closed);</li>
 *   <li>CAPTURE_REQUESTED or RELEASE_REQUESTED sent {@code reconciliation.stuck-after-attempts} times. They have
 *       no deadline (a capture is re-sent until it lands) and each re-send refreshes {@code updated_at}, so the
 *       attempt count is the signal.</li>
 * </ul>
 */
@Repository
public class StuckSagas {

    public record StuckSaga(UUID paymentId, String state, int attempts, Instant updatedAt, Instant deadlineAt,
                            String reason) {}

    private final JdbcClient jdbc;
    private final SagaProperties saga;
    private final ReconciliationProperties properties;

    public StuckSagas(JdbcClient jdbc, SagaProperties saga, ReconciliationProperties properties) {
        this.jdbc = jdbc;
        this.saga = saga;
        this.properties = properties;
    }

    /** Up to {@code limit} stuck sagas, oldest update first. */
    public List<StuckSaga> find(int limit) {
        Instant now = Instant.now();
        Instant deadlineBefore = now.minus(properties.grace());
        Instant fraudBefore = deadlineBefore.minus(saga.fraudCheckStaleAfter());
        return jdbc.sql("""
                        SELECT payment_id, state, attempts, updated_at, deadline_at,
                               CASE WHEN state IN ('HOLD_REQUESTED', 'PENDING_REVIEW') THEN 'past its deadline'
                                    WHEN state = 'FRAUD_CHECK' THEN 'fraud check never finished'
                                    ELSE 'no answer from the ledger after ' || attempts || ' sends' END AS reason
                        FROM sagas
                        WHERE (state IN ('HOLD_REQUESTED', 'PENDING_REVIEW') AND deadline_at < :deadlineBefore)
                           OR (state = 'FRAUD_CHECK' AND updated_at < :fraudBefore)
                           OR (state IN ('CAPTURE_REQUESTED', 'RELEASE_REQUESTED') AND attempts >= :attempts)
                        ORDER BY updated_at LIMIT :limit
                        """)
                .param("deadlineBefore", Timestamp.from(deadlineBefore))
                .param("fraudBefore", Timestamp.from(fraudBefore))
                .param("attempts", properties.stuckAfterAttempts())
                .param("limit", limit)
                .query((rs, n) -> new StuckSaga(rs.getObject("payment_id", UUID.class), rs.getString("state"),
                        rs.getInt("attempts"), rs.getTimestamp("updated_at").toInstant(),
                        rs.getTimestamp("deadline_at").toInstant(), rs.getString("reason")))
                .list();
    }
}
