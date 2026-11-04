package com.gopi.payments.processor.saga;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The sweep's query; plain SQL for {@code FOR UPDATE SKIP LOCKED}. */
@Repository
class DueSagas {

    private final JdbcClient jdbc;

    DueSagas(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Locks sagas that waited for the ledger since before {@code staleBefore}, whose hold or review deadline passed,
     * or whose fraud check started before {@code fraudStaleBefore} and never finished. Rows another sweep (or a
     * ledger event) holds are skipped, so each due saga is handled by exactly one sweep.
     */
    List<UUID> lock(Instant staleBefore, Instant fraudStaleBefore, Instant now, int limit) {
        return jdbc.sql("""
                        SELECT payment_id FROM sagas
                        WHERE (state IN ('HOLD_REQUESTED', 'CAPTURE_REQUESTED', 'RELEASE_REQUESTED')
                               AND updated_at < :staleBefore)
                           OR (state IN ('HOLD_REQUESTED', 'PENDING_REVIEW') AND deadline_at < :now)
                           OR (state = 'FRAUD_CHECK' AND updated_at < :fraudStaleBefore)
                        ORDER BY updated_at LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("staleBefore", Timestamp.from(staleBefore))
                .param("fraudStaleBefore", Timestamp.from(fraudStaleBefore)).param("now", Timestamp.from(now))
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }
}
