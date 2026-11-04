package com.gopi.payments.payment_api.repo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain SQL because every write is conditional and the caller needs to know whether it matched a row. All times
 * come from Postgres {@code now()}.
 */
@Repository
public class IdempotencyKeyRepository {

    private final JdbcClient jdbc;

    public IdempotencyKeyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts an IN_PROGRESS claim; false if the key already exists. */
    public boolean tryClaim(String key, String hash, UUID token, Duration lockFor, Duration ttl) {
        return jdbc.sql("""
                        INSERT INTO idempotency_keys
                            (idempotency_key, request_hash, status, claim_token, locked_until, created_at, expires_at)
                        VALUES (:key, :hash, 'IN_PROGRESS', :token,
                                now() + make_interval(secs => :lockSecs), now(), now() + make_interval(secs => :ttlSecs))
                        ON CONFLICT (idempotency_key) DO NOTHING
                        """)
                .param("key", key).param("hash", hash).param("token", token)
                .param("lockSecs", seconds(lockFor)).param("ttlSecs", seconds(ttl))
                .update() == 1;
    }

    public Optional<IdempotencyKey> find(String key) {
        return jdbc.sql("""
                        SELECT idempotency_key, request_hash, status, claim_token, payment_id, response_status,
                               response_body, expires_at < now() AS expired, locked_until < now() AS lock_expired
                        FROM idempotency_keys WHERE idempotency_key = :key
                        """)
                .param("key", key)
                .query((rs, n) -> new IdempotencyKey(rs.getString("idempotency_key"), rs.getString("request_hash"),
                        rs.getString("status"), rs.getObject("claim_token", UUID.class),
                        rs.getObject("payment_id", UUID.class), rs.getObject("response_status", Integer.class),
                        rs.getString("response_body"), rs.getBoolean("expired"), rs.getBoolean("lock_expired")))
                .optional();
    }

    /**
     * Takes over a key that is expired (any request), or a stale IN_PROGRESS claim of the same request. False if the
     * row no longer qualifies, i.e. another request got there first.
     */
    public boolean reclaim(String key, String hash, UUID token, Duration lockFor, Duration ttl) {
        return jdbc.sql("""
                        UPDATE idempotency_keys
                        SET request_hash = :hash, status = 'IN_PROGRESS', claim_token = :token,
                            locked_until = now() + make_interval(secs => :lockSecs),
                            payment_id = NULL, response_status = NULL, response_body = NULL,
                            created_at = now(), expires_at = now() + make_interval(secs => :ttlSecs)
                        WHERE idempotency_key = :key
                          AND (expires_at < now()
                               OR (status = 'IN_PROGRESS' AND locked_until < now() AND request_hash = :hash))
                        """)
                .param("key", key).param("hash", hash).param("token", token)
                .param("lockSecs", seconds(lockFor)).param("ttlSecs", seconds(ttl))
                .update() == 1;
    }

    /** Marks our claim COMPLETED with the response to replay; false if the claim was taken over meanwhile. */
    public boolean complete(String key, UUID token, UUID paymentId, int responseStatus, String responseBody) {
        return jdbc.sql("""
                        UPDATE idempotency_keys
                        SET status = 'COMPLETED', payment_id = :paymentId,
                            response_status = :responseStatus, response_body = :responseBody
                        WHERE idempotency_key = :key AND claim_token = :token AND status = 'IN_PROGRESS'
                        """)
                .param("key", key).param("token", token).param("paymentId", paymentId)
                .param("responseStatus", responseStatus).param("responseBody", responseBody)
                .update() == 1;
    }

    /** Gives up our claim so the client can retry the same key. */
    public void release(String key, UUID token) {
        jdbc.sql("DELETE FROM idempotency_keys WHERE idempotency_key = :key AND claim_token = :token")
                .param("key", key).param("token", token)
                .update();
    }

    public int deleteExpired() {
        return jdbc.sql("DELETE FROM idempotency_keys WHERE expires_at < now()").update();
    }

    private static double seconds(Duration d) {
        return d.toMillis() / 1000.0;
    }
}
