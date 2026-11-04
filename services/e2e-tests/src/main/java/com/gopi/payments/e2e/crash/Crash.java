package com.gopi.payments.e2e.crash;

import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

import java.nio.charset.StandardCharsets;

/** Ends the process at once, like kill -9: no shutdown hooks, no Spring close, nothing flushed or committed. */
final class Crash {

    static final int EXIT_CODE = 137;

    private Crash() {
    }

    static String eventType(Headers headers) {
        Header header = headers.lastHeader("event-type");
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    static void now(String where) {
        System.err.println("E2E CRASH " + where);
        System.err.flush();
        Runtime.getRuntime().halt(EXIT_CODE);
    }
}
