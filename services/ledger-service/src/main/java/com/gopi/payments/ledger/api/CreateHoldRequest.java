package com.gopi.payments.ledger.api;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateHoldRequest(
        @NotNull UUID payerAccountId,
        @NotNull UUID merchantAccountId,
        @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @NotNull @Pattern(regexp = "[A-Z]{3}", message = "must be a three-letter ISO currency code") String currency) {
}
