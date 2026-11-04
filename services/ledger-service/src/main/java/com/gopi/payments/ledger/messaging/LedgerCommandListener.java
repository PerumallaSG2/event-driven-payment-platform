package com.gopi.payments.ledger.messaging;

import com.gopi.payments.ledger.messaging.LedgerCommands.Command;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Parses records from {@code ledger.commands}; anything unparseable is an {@link InvalidMessageException} (DLT). */
@Component
public class LedgerCommandListener {

    private final LedgerCommandHandler handler;
    private final ObjectMapper objectMapper;

    public LedgerCommandListener(LedgerCommandHandler handler, ObjectMapper objectMapper) {
        this.handler = handler;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = LedgerCommandHandler.TOPIC_COMMANDS, groupId = "ledger-service")
    public void on(ConsumerRecord<String, String> record) {
        UUID eventId = eventId(record);
        String type = header(record, OutboxRelay.EVENT_TYPE_HEADER);
        Class<? extends Command> commandType = LedgerCommands.typeOf(type);
        if (commandType == null) {
            throw new InvalidMessageException("unknown event-type " + type, null);
        }
        Command command;
        try {
            command = objectMapper.readValue(record.value(), commandType);
        } catch (JacksonException e) {
            throw new InvalidMessageException("unparseable " + type + ": " + e.getOriginalMessage(), e);
        }
        handler.handle(eventId, command);
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
