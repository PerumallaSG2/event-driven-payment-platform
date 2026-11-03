package com.gopi.payments.ledger;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests share one database, and the HOLDS/EXTERNAL system accounts are per currency. Giving each test its own
 * made-up currency code keeps their balances independent, so assertions on them are exact.
 */
final class TestCurrencies {

    private static final AtomicInteger NEXT = new AtomicInteger(ThreadLocalRandom.current().nextInt(26 * 26 * 26));

    private TestCurrencies() {}

    static String next() {
        int n = NEXT.getAndIncrement() % (26 * 26 * 26);
        return "" + (char) ('A' + n / 676) + (char) ('A' + n / 26 % 26) + (char) ('A' + n % 26);
    }
}
