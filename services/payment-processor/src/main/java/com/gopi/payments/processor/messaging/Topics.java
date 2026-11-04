package com.gopi.payments.processor.messaging;

public final class Topics {

    public static final String PAYMENT_INITIATED = "payment.initiated";
    public static final String PAYMENT_EVENTS = "payment.events";
    public static final String LEDGER_COMMANDS = "ledger.commands";
    public static final String LEDGER_EVENTS = "ledger.events";
    public static final String PAYMENT_REVIEWS = "payment.reviews";
    public static final String DLT_SUFFIX = ".DLT";

    private Topics() {}
}
