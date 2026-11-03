package com.gopi.payments.ledger.api;

import com.gopi.payments.ledger.domain.AccountKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * kind must be CUSTOMER or MERCHANT; HOLDS and EXTERNAL are system accounts. allowNegative is optional (default
 * false); it's a {@code Boolean} because Jackson 3 fails on a missing primitive (FAIL_ON_NULL_FOR_PRIMITIVES).
 */
public record CreateAccountRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull AccountKind kind,
        @NotNull @Pattern(regexp = "[A-Z]{3}", message = "must be a three-letter ISO currency code") String currency,
        Boolean allowNegative) {

    public boolean allowNegativeOrDefault() {
        return Boolean.TRUE.equals(allowNegative);
    }
}
