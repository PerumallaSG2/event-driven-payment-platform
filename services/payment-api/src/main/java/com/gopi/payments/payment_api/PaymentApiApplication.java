package com.gopi.payments.payment_api;

import com.gopi.payments.payment_api.outbox.OutboxProperties;
import com.gopi.payments.payment_api.service.IdempotencyProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({IdempotencyProperties.class, OutboxProperties.class})
public class PaymentApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(PaymentApiApplication.class, args);
	}

}
