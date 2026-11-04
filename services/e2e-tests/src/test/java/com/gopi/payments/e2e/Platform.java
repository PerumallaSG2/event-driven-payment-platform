package com.gopi.payments.e2e;

import com.gopi.payments.e2e.ServiceProcess.CrashPoint;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * The whole system on real infra: one PostgreSQL (databases payments, processor, ledger) and one Kafka in
 * Testcontainers, the three services as their own processes, and the fraud stub. Started once for the suite.
 */
final class Platform implements AutoCloseable {

    final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");
    final KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.2")
            .withEnv("KAFKA_HEAP_OPTS", "-Xmx256m -Xms256m")
            .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0");
    final FraudStub fraud = new FraudStub(freePort());
    final ServiceProcess paymentApi;
    final ServiceProcess ledger;
    final ServiceProcess processor;

    Platform() {
        Stream.of(postgres, kafka).parallel().forEach(c -> c.start());
        for (String db : List.of("payments", "processor", "ledger")) {
            execute("test", "CREATE DATABASE " + db);
        }
        fraud.start();
        int apiPort = freePort();
        int ledgerPort = freePort();
        int processorPort = freePort();
        paymentApi = new ServiceProcess("payment-api", apiPort, "com.gopi.payments.payment_api.PaymentApiApplication",
                common("payments", List.of("--spring.jpa.show-sql=false")));
        ledger = new ServiceProcess("ledger-service", ledgerPort, "com.gopi.payments.ledger.LedgerServiceApplication",
                common("ledger", List.of(
                        "--reconciliation.enabled=false",
                        "--reconciliation.payment-api-url=http://127.0.0.1:" + apiPort,
                        "--reconciliation.processor-url=http://127.0.0.1:" + processorPort)));
        processor = new ServiceProcess("payment-processor", processorPort,
                "com.gopi.payments.processor.PaymentProcessorApplication",
                common("processor", List.of(
                        "--fraud.base-url=" + fraud.baseUrl(),
                        "--fraud.timeout=PT2S",
                        "--fraud.read-timeout=PT1S",
                        "--saga.fraud-check-stale-after=PT5S",
                        "--saga.resend-after=PT2S",
                        "--saga.sweep-interval=PT0.5S",
                        "--saga.hold-deadline=PT60S")));
        Runtime.getRuntime().addShutdownHook(new Thread(this::close));
        services().forEach(s -> s.start(null));
        services().parallel().forEach(s -> s.awaitReady(Duration.ofSeconds(120)));
    }

    Stream<ServiceProcess> services() {
        return Stream.of(paymentApi, ledger, processor);
    }

    /** Kills the service and starts it again, armed to halt at {@code crash} (or plain when null). */
    void restart(ServiceProcess service, CrashPoint crash) {
        service.kill();
        service.startAndAwait(crash);
    }

    private List<String> common(String database, List<String> extra) {
        List<String> args = new java.util.ArrayList<>(List.of(
                "--spring.datasource.url=jdbc:postgresql://" + postgres.getHost() + ":"
                        + postgres.getMappedPort(5432) + "/" + database,
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                // a killed consumer leaves its group after this, not the default 45 s
                "--spring.kafka.consumer.properties.session.timeout.ms=6000",
                "--spring.kafka.consumer.properties.heartbeat.interval.ms=1000",
                "--logging.level.org.apache.kafka=WARN"));
        args.addAll(extra);
        return args;
    }

    // ---- Kafka ----

    /** docker pause: the broker freezes, keeps its data and its mapped port. */
    void pauseKafka() {
        DockerClientFactory.instance().client().pauseContainerCmd(kafka.getContainerId()).exec();
    }

    void unpauseKafka() {
        DockerClientFactory.instance().client().unpauseContainerCmd(kafka.getContainerId()).exec();
    }

    /** Records on every {@code *.DLT} topic, all partitions. */
    long deadLetters() {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (Admin admin = Admin.create(props)) {
            List<String> dlts = admin.listTopics().names().get().stream().filter(t -> t.endsWith(".DLT")).toList();
            List<TopicPartition> partitions = admin.describeTopics(dlts).allTopicNames().get().values().stream()
                    .flatMap(d -> d.partitions().stream().map(p -> new TopicPartition(d.name(), p.partition())))
                    .toList();
            try (KafkaConsumer<String, String> consumer = consumer()) {
                Map<TopicPartition, Long> start = consumer.beginningOffsets(partitions);
                return consumer.endOffsets(partitions).entrySet().stream()
                        .mapToLong(e -> e.getValue() - start.get(e.getKey())).sum();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    KafkaConsumer<String, String> consumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return new KafkaConsumer<>(props);
    }

    // ---- databases (read-only, for assertions) ----

    long queryLong(String database, String sql, Object... params) {
        try (Connection c = connect(database); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    String queryString(String database, String sql, Object... params) {
        try (Connection c = connect(database); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void execute(String database, String sql) {
        try (Connection c = connect(database); Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private Connection connect(String database) throws SQLException {
        String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database;
        return DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
    }

    // ----

    @Override
    public synchronized void close() {
        if (paymentApi != null) {
            services().forEach(ServiceProcess::kill);
        }
        try {
            fraud.stop();
        } catch (RuntimeException ignored) {
            // already stopped
        }
        try {
            unpauseKafka();
        } catch (RuntimeException ignored) {
            // not paused
        }
        kafka.stop();
        postgres.stop();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
