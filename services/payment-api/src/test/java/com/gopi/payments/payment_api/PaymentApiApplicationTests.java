package com.gopi.payments.payment_api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** The app starts against real PostgreSQL and Kafka: Flyway migrates and Hibernate validates the schema. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestcontainer.class, KafkaTestcontainer.class})
class PaymentApiApplicationTests {

	@Test
	void contextLoads() {
	}

}
