package com.gopi.payments.processor.fraud;

/** Asks fraud-detection for a decision. Never throws: any failure is a {@link FraudResult.Unavailable}. */
public interface FraudClient {

    FraudResult check(FraudRequest request);
}
