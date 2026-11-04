package com.gopi.payments.ledger;

import com.gopi.payments.ledger.messaging.OutboxProperties;
import com.gopi.payments.ledger.reconciliation.ReconciliationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({OutboxProperties.class, ReconciliationProperties.class})
public class LedgerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerServiceApplication.class, args);
    }
}
