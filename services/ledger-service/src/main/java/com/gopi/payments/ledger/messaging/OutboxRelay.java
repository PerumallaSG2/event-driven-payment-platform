package com.gopi.payments.ledger.messaging;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Publishes outbox rows to Kafka. Each run, in one transaction: lock a batch ({@code FOR UPDATE SKIP LOCKED}, so
 * several instances never publish the same row concurrently), send it in order, wait for the broker's acks, and
 * mark the acknowledged prefix published. A row is never marked published before Kafka has it, so nothing is lost;
 * a crash between ack and commit sends it again, which consumers de-duplicate by event id.
 */
@Component
public class OutboxRelay {

    public static final String EVENT_ID_HEADER = "event-id";
    public static final String EVENT_TYPE_HEADER = "event-type";

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final OutboxProperties properties;

    public OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                       OutboxProperties properties) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.tx = tx;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${messaging.outbox.poll-interval:PT0.2S}")
    public void scheduledRun() {
        try {
            publishBatch();
        } catch (RuntimeException e) {
            log.warn("Outbox relay run failed; unpublished rows stay queued: {}", e.toString());
        }
    }

    /** Publishes one batch; returns how many rows were published. */
    public int publishBatch() {
        Integer published = tx.execute(status -> {
            List<OutboxMessage> batch = outbox.lockUnpublished(properties.batchSize());
            List<CompletableFuture<SendResult<String, String>>> sends = new ArrayList<>(batch.size());
            for (OutboxMessage m : batch) {
                sends.add(kafka.send(record(m)));
            }
            List<Long> acked = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                try {
                    sends.get(i).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                    acked.add(batch.get(i).id());
                } catch (Exception e) {
                    // Keep order: rows after the first failure stay unpublished, even if their send succeeded.
                    log.warn("Outbox row {} not acknowledged, retrying later: {}", batch.get(i).id(), e.toString());
                    break;
                }
            }
            outbox.markPublished(acked);
            return acked.size();
        });
        return published == null ? 0 : published;
    }

    private static ProducerRecord<String, String> record(OutboxMessage m) {
        ProducerRecord<String, String> record = new ProducerRecord<>(m.topic(), m.key(), m.payload());
        record.headers().add(EVENT_ID_HEADER, m.eventId().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EVENT_TYPE_HEADER, m.eventType().getBytes(StandardCharsets.UTF_8));
        return record;
    }
}
