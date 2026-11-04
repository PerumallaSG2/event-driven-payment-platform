package com.gopi.payments.payment_api.service;

import com.gopi.payments.payment_api.api.CreatePaymentRequest;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;

/**
 * SHA-256 (hex) of the <em>parsed</em> request, so whitespace, JSON key order, unknown fields and {@code 10.5} vs
 * {@code 10.50} don't change it, while any real field change does. The offset of {@code timestamp} is part of the
 * hash: the payment keeps it, so a different offset is a different request.
 */
public final class RequestHash {

    /** Its own mapper, so a change to the application's Jackson settings can't change stored hashes. */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private record Canonical(UUID payerAccountId, UUID merchantAccountId, String amount, String currency,
                             String cardId, String merchant, String merchantCategory, String city,
                             double latitude, double longitude, String timestamp) {}

    private RequestHash() {}

    public static String of(CreatePaymentRequest r) {
        Canonical canonical = new Canonical(r.payerAccountId(), r.merchantAccountId(),
                r.amount().stripTrailingZeros().toPlainString(), r.currency(), r.cardId(), r.merchant(),
                r.merchantCategory(), r.city(), r.latitude(), r.longitude(),
                r.timestamp().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        return sha256Hex(MAPPER.writeValueAsBytes(canonical));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
