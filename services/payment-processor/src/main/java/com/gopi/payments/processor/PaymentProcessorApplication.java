package com.gopi.payments.processor;

import com.gopi.payments.processor.fraud.FraudProperties;
import com.gopi.payments.processor.messaging.OutboxProperties;
import com.gopi.payments.processor.reconciliation.ReconciliationProperties;
import com.gopi.payments.processor.saga.SagaProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({OutboxProperties.class, SagaProperties.class, FraudProperties.class,
        ReconciliationProperties.class})
public class PaymentProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentProcessorApplication.class, args);
    }
}
