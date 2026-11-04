package com.gopi.payments.payment_api;

import static org.assertj.core.api.Assertions.assertThat;

import com.gopi.payments.payment_api.api.CreatePaymentRequest;
import com.gopi.payments.payment_api.service.RequestHash;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestHashTest {

    private static final UUID PAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MERCHANT = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void sameRequestSameHash() {
        assertThat(RequestHash.of(request("10.50", "Austin", "2026-03-14T02:30:00-05:00")))
                .hasSize(64)
                .isEqualTo(RequestHash.of(request("10.50", "Austin", "2026-03-14T02:30:00-05:00")));
    }

    @Test
    void trailingZerosAndTimestampSpellingDoNotChangeTheHash() {
        assertThat(RequestHash.of(request("10.5", "Austin", "2026-03-14T02:30-05:00")))
                .isEqualTo(RequestHash.of(request("10.50", "Austin", "2026-03-14T02:30:00.000-05:00")));
    }

    @Test
    void anyRealChangeChangesTheHash() {
        String base = RequestHash.of(request("10.50", "Austin", "2026-03-14T02:30:00-05:00"));

        assertThat(RequestHash.of(request("10.51", "Austin", "2026-03-14T02:30:00-05:00"))).isNotEqualTo(base);
        assertThat(RequestHash.of(request("10.50", "Dallas", "2026-03-14T02:30:00-05:00"))).isNotEqualTo(base);
        // Same instant, different offset: the payment keeps the offset, so this is a different request.
        assertThat(RequestHash.of(request("10.50", "Austin", "2026-03-14T07:30:00Z"))).isNotEqualTo(base);
    }

    private static CreatePaymentRequest request(String amount, String city, String timestamp) {
        return new CreatePaymentRequest(PAYER, MERCHANT, new BigDecimal(amount), "USD", "card-1", "Corner Coffee",
                "RESTAURANT", city, 30.2672, -97.7431, OffsetDateTime.parse(timestamp));
    }
}
