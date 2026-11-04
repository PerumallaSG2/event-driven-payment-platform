package com.gopi.payments.e2e;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * At-least-once delivery at its worst: every record that appears on the platform's topics is published once more,
 * right away, with the same key, partition and headers (so the same {@code event-id}); {@link #replayAll()} sends
 * them all again later. Copies carry an {@code e2e-duplicate} header so they aren't copied in turn.
 */
final class Duplicator implements AutoCloseable {

    static final List<String> TOPICS = List.of("payment.initiated", "ledger.commands", "ledger.events",
            "payment.events", "payment.reviews");
    private static final String MARK = "e2e-duplicate";

    private final KafkaConsumer<String, String> consumer;
    private final KafkaProducer<String, String> producer;
    private final List<ConsumerRecord<String, String>> originals = new CopyOnWriteArrayList<>();
    private final Thread thread;
    private volatile boolean running = true;

    Duplicator(Platform platform) {
        consumer = platform.consumer();
        List<TopicPartition> partitions = new ArrayList<>();
        for (String topic : TOPICS) {
            consumer.partitionsFor(topic).forEach(p -> partitions.add(new TopicPartition(topic, p.partition())));
        }
        consumer.assign(partitions);
        consumer.seekToEnd(partitions);
        partitions.forEach(consumer::position);   // resolve the end offsets now, before any scenario record
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, platform.kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        producer = new KafkaProducer<>(props);
        thread = new Thread(this::run, "e2e-duplicator");
        thread.start();
    }

    int originals(String topic) {
        return (int) originals.stream().filter(r -> r.topic().equals(topic)).count();
    }

    /** Sends every original record seen so far once more. */
    void replayAll() {
        for (ConsumerRecord<String, String> record : List.copyOf(originals)) {
            producer.send(copy(record));
        }
        producer.flush();
    }

    private void run() {
        while (running) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(100))) {
                if (record.headers().lastHeader(MARK) == null) {
                    originals.add(record);
                    producer.send(copy(record));
                }
            }
        }
    }

    private static ProducerRecord<String, String> copy(ConsumerRecord<String, String> record) {
        ProducerRecord<String, String> copy = new ProducerRecord<>(record.topic(), record.partition(), record.key(),
                record.value());
        for (Header header : record.headers()) {
            copy.headers().add(header.key(), header.value());
        }
        copy.headers().add(MARK, "1".getBytes(StandardCharsets.UTF_8));
        return copy;
    }

    @Override
    public void close() throws InterruptedException {
        running = false;
        thread.join(5000);
        consumer.close();
        producer.close();
    }
}
