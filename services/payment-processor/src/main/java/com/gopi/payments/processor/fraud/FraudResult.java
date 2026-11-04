package com.gopi.payments.processor.fraud;

import java.util.List;
import java.util.UUID;

/** What a fraud check came to: a scored decision, or no usable answer (the saga fails closed). */
public sealed interface FraudResult {

    record Scored(UUID fraudTransactionId, FraudDecision decision, int riskScore, List<TriggeredRule> rules)
            implements FraudResult {}

    record TriggeredRule(String rule, int points, String reason) {}

    /**
     * @param cause  DOWN (not reachable), TIMEOUT (no decision within the budget), MALFORMED (an answer that can't be
     *               read), REJECTED (fraud-detection refused the request)
     * @param detail for the log
     */
    record Unavailable(String cause, String detail) implements FraudResult {
        public static final String DOWN = "DOWN";
        public static final String TIMEOUT = "TIMEOUT";
        public static final String MALFORMED = "MALFORMED";
        public static final String REJECTED = "REJECTED";
    }
}
