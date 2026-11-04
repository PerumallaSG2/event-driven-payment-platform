package com.gopi.payments.processor.saga;

import com.gopi.payments.processor.fraud.FraudClient;
import com.gopi.payments.processor.fraud.FraudRequest;
import com.gopi.payments.processor.fraud.FraudResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Runs one saga's fraud check: read what to send (short transaction), ask fraud-detection (no transaction, no row
 * lock; at most {@code fraud.timeout}), record the answer (new transaction). Called on the ledger.events consumer
 * thread after {@code FundsHeld} committed.
 * <p>
 * Never throws: the record that triggered it is already in the inbox, so a redelivery would be skipped. If the check
 * dies anyway (the app stops), the saga stays in FRAUD_CHECK and the sweep fails it closed.
 */
@Component
public class FraudCheck {

    private static final Logger log = LoggerFactory.getLogger(FraudCheck.class);

    private final SagaService sagas;
    private final FraudClient fraud;

    public FraudCheck(SagaService sagas, FraudClient fraud) {
        this.sagas = sagas;
        this.fraud = fraud;
    }

    public void run(UUID paymentId) {
        try {
            Optional<FraudRequest> request = sagas.fraudRequestFor(paymentId);
            if (request.isEmpty()) {
                return;
            }
            FraudResult result = fraud.check(request.get());
            if (result instanceof FraudResult.Unavailable u) {
                log.warn("Fraud check for {} gave no decision: {} {}", paymentId, u.cause(), u.detail());
            }
            sagas.applyFraud(paymentId, result);
        } catch (RuntimeException e) {
            log.error("Fraud check for {} failed; the sweep fails it closed", paymentId, e);
        }
    }
}
