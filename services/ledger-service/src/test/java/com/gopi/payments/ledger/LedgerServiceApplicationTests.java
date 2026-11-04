package com.gopi.payments.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.gopi.payments.ledger.reconciliation.Reconciliation;
import com.gopi.payments.ledger.reconciliation.ReconciliationSchedule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestcontainer.class, KafkaTestcontainer.class})
class LedgerServiceApplicationTests {

    @Autowired ApplicationContext context;

    @Test
    void contextLoads() {
    }

    /** src/test/resources/config/application.properties turns the schedule off; a run is still available on demand. */
    @Test
    void testsHaveNoScheduledReconciliation() {
        assertThat(context.getBeansOfType(ReconciliationSchedule.class)).isEmpty();
        assertThat(context.getBeansOfType(Reconciliation.class)).hasSize(1);
    }
}
