package com.gopi.payments.processor.fraud;

/** fraud-detection's verdict on a transaction. */
public enum FraudDecision {
    APPROVE,
    REVIEW,
    BLOCK
}
