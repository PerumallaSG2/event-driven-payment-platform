package com.gopi.payments.e2e.crash;

import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

import java.util.Map;

/**
 * Halts the service when it is about to send the first record whose {@code event-type} is the system property
 * {@code e2e.crash.before-send}. The outbox row was committed by the business transaction; Kafka never gets the
 * record. Put on a service's classpath only by the e2e tests, through
 * {@code spring.kafka.producer.properties.interceptor.classes}.
 */
public class HaltBeforeSend implements ProducerInterceptor<Object, Object> {

    private final String eventType = System.getProperty("e2e.crash.before-send");

    @Override
    public ProducerRecord<Object, Object> onSend(ProducerRecord<Object, Object> record) {
        if (eventType != null && eventType.equals(Crash.eventType(record.headers()))) {
            Crash.now("before sending " + eventType + " key " + record.key());
        }
        return record;
    }

    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
    }

    @Override
    public void close() {
    }

    @Override
    public void configure(Map<String, ?> configs) {
    }
}
