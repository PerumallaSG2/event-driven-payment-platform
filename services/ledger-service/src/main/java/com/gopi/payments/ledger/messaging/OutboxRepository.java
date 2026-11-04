package com.gopi.payments.ledger.messaging;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Repository
public class OutboxRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public OutboxRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Queues an event for publishing. Must run inside the caller's transaction, so the event exists if and only if
     * the caller's writes commit. Returns the event id (the {@code event-id} header consumers de-duplicate on).
     */
    public UUID append(String topic, String key, String eventType, Object event) {
        UUID eventId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO outbox (event_id, topic, msg_key, event_type, payload)
                        VALUES (:eventId, :topic, :key, :eventType, :payload)
                        """)
                .param("eventId", eventId).param("topic", topic).param("key", key)
                .param("eventType", eventType).param("payload", objectMapper.writeValueAsString(event))
                .update();
        return eventId;
    }

    /** The oldest unpublished rows, row-locked; rows another relay already holds are skipped, not waited for. */
    List<OutboxMessage> lockUnpublished(int limit) {
        return jdbc.sql("""
                        SELECT id, event_id, topic, msg_key, event_type, payload FROM outbox
                        WHERE published_at IS NULL ORDER BY id LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("limit", limit)
                .query((rs, n) -> new OutboxMessage(rs.getLong("id"), rs.getObject("event_id", UUID.class),
                        rs.getString("topic"), rs.getString("msg_key"), rs.getString("event_type"),
                        rs.getString("payload")))
                .list();
    }

    void markPublished(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE outbox SET published_at = now() WHERE id IN (:ids)").param("ids", ids).update();
    }

    int deletePublishedOlderThan(Duration retention) {
        return jdbc.sql("DELETE FROM outbox WHERE published_at < now() - make_interval(secs => :secs)")
                .param("secs", retention.toSeconds())
                .update();
    }
}
