package com.gopi.payments.e2e.crash;

import org.apache.kafka.clients.consumer.ConsumerInterceptor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

import java.util.Map;

/**
 * Halts the service when a poll returns a record whose {@code event-type} is the system property
 * {@code e2e.crash.on-receive}: fetched, not handled, offset not committed, so Kafka redelivers it to the next
 * process. Put on a service's classpath only by the e2e tests, through
 * {@code spring.kafka.consumer.properties.interceptor.classes}.
 */
public class HaltOnReceive implements ConsumerInterceptor<Object, Object> {

    private final String eventType = System.getProperty("e2e.crash.on-receive");

    @Override
    public ConsumerRecords<Object, Object> onConsume(ConsumerRecords<Object, Object> records) {
        if (eventType != null) {
            for (ConsumerRecord<Object, Object> record : records) {
                if (eventType.equals(Crash.eventType(record.headers()))) {
                    Crash.now("on receiving " + eventType + " key " + record.key());
                }
            }
        }
        return records;
    }

    @Override
    public void onCommit(Map<TopicPartition, OffsetAndMetadata> offsets) {
    }

    @Override
    public void close() {
    }

    @Override
    public void configure(Map<String, ?> configs) {
    }
}
