package com.gopi.payments.ledger.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The current time at the precision PostgreSQL's {@code timestamptz} keeps: microseconds. A value created here and
 * stored is the same when read back, so a POST response and a later GET show the identical timestamp. On Linux
 * {@code Instant.now()} has nanoseconds, which the database rounds; macOS's clock already stops at microseconds.
 */
public final class Timestamps {

    private Timestamps() {}

    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
