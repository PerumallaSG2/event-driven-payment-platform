package com.gopi.payments.processor.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Function;

/**
 * Reads the {@code event-id} and {@code event-type} headers and parses the JSON value into the record the type names.
 * Anything that can't be read is an {@link InvalidMessageException}: not retried, sent to the DLT.
 */
public record Envelope<T>(UUID eventId, String eventType, T payload) {

    public static <T> Envelope<T> parse(ConsumerRecord<String, String> record, ObjectMapper mapper,
                                        Function<String, Class<? extends T>> typeOf) {
        String id = header(record, OutboxRelay.EVENT_ID_HEADER);
        UUID eventId;
        try {
            eventId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new InvalidMessageException("event-id is not a UUID: " + id, e);
        }
        String type = header(record, OutboxRelay.EVENT_TYPE_HEADER);
        Class<? extends T> target = typeOf.apply(type);
        if (target == null) {
            throw new InvalidMessageException("unknown event-type " + type, null);
        }
        try {
            return new Envelope<>(eventId, type, mapper.readValue(record.value(), target));
        } catch (JacksonException e) {
            throw new InvalidMessageException("unparseable " + type + ": " + e.getOriginalMessage(), e);
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value() == null) {
            throw new InvalidMessageException("missing header " + name, null);
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
