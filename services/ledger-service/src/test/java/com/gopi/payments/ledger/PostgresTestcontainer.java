package com.gopi.payments.ledger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A real PostgreSQL for tests that touch the database: Flyway runs the production migrations (including the
 * deferred triggers) on it and Hibernate validates against it. Import it with
 * {@code @Import(PostgresTestcontainer.class)}. One container per Spring test context; cached contexts reuse theirs.
 * Needs Docker: without it these tests fail rather than skip. Same as payment-api's copy.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainer {

    /** Same image as infra/docker-compose.yml. */
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:16-alpine");
    }
}
