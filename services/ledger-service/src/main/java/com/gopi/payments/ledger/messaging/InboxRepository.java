package com.gopi.payments.ledger.messaging;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class InboxRepository {

    private final JdbcClient jdbc;

    public InboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records the event as handled, inside the handler's transaction. False if it already was: a redelivery, which
     * the caller skips. The row only commits together with the handler's own writes.
     */
    public boolean markHandled(UUID eventId, String topic) {
        return jdbc.sql("INSERT INTO inbox (event_id, topic) VALUES (:eventId, :topic) ON CONFLICT DO NOTHING")
                .param("eventId", eventId).param("topic", topic)
                .update() == 1;
    }
}
