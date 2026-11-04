package com.gopi.payments.payment_api.messaging;

import com.gopi.payments.payment_api.events.PaymentEvents;
import com.gopi.payments.payment_api.events.PaymentEvents.Outcome;
import com.gopi.payments.payment_api.outbox.OutboxRelay;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Parses records from {@code payment.events}; anything unparseable is an {@link InvalidMessageException} (DLT). */
@Component
public class PaymentEventsListener {

    private final PaymentOutcomeService outcomes;
    private final ObjectMapper objectMapper;

    public PaymentEventsListener(PaymentOutcomeService outcomes, ObjectMapper objectMapper) {
        this.outcomes = outcomes;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = PaymentOutcomeService.TOPIC_PAYMENT_EVENTS, groupId = "payment-api")
    public void on(ConsumerRecord<String, String> record) {
        UUID eventId = eventId(record);
        String type = header(record, OutboxRelay.EVENT_TYPE_HEADER);
        Class<? extends Outcome> outcomeType = PaymentEvents.typeOf(type);
        if (outcomeType == null) {
            throw new InvalidMessageException("unknown event-type " + type, null);
        }
        Outcome outcome;
        try {
            outcome = objectMapper.readValue(record.value(), outcomeType);
        } catch (JacksonException e) {
            throw new InvalidMessageException("unparseable " + type + ": " + e.getOriginalMessage(), e);
        }
        if (outcome.paymentId() == null) {
            throw new InvalidMessageException(type + " without paymentId", null);
        }
        outcomes.apply(eventId, outcome);
    }

    private static UUID eventId(ConsumerRecord<String, String> record) {
        String value = header(record, OutboxRelay.EVENT_ID_HEADER);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidMessageException("event-id is not a UUID: " + value, e);
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
