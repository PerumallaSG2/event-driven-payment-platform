package com.gopi.payments.payment_api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.context.annotation.Import;

/** The app starts against real PostgreSQL and Kafka: Flyway migrates and Hibernate validates the schema. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestcontainer.class, KafkaTestcontainer.class})
class PaymentApiApplicationTests {

	@Autowired
	KafkaAdmin admin;

	@Test
	void contextLoads() {
	}

	/** The topics it consumes and produces exist with 3 partitions, whichever service started first. */
	@Test
	void topicsHaveThreePartitions() {
		assertThat(admin.describeTopics("payment.events", "payment.initiated", "payment.events.DLT").values())
				.allSatisfy(t -> assertThat(t.partitions()).as(t.name()).hasSize(3));
	}

}
