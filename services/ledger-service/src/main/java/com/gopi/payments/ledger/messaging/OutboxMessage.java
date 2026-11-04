package com.gopi.payments.ledger.messaging;

import java.util.UUID;

/** An unpublished outbox row. */
public record OutboxMessage(long id, UUID eventId, String topic, String key, String eventType, String payload) {
}
