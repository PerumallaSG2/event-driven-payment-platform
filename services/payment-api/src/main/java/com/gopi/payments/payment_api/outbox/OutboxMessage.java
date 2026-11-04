package com.gopi.payments.payment_api.outbox;

import java.util.UUID;

/** An unpublished outbox row. */
public record OutboxMessage(long id, UUID eventId, String topic, String key, String eventType, String payload) {
}
