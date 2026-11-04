package com.gopi.payments.payment_api.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A card payment to a merchant. The account fields use the names of ledger's {@code CreateHoldRequest}; the
 * card/merchant fields use the names and types of fraud-detection's {@code TransactionRequest}, which item 5 passes
 * them to unchanged. {@code timestamp} keeps its offset end to end.
 */
public record CreatePaymentRequest(
        @NotNull UUID payerAccountId,
        @NotNull UUID merchantAccountId,
        @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @NotNull @Pattern(regexp = "[A-Z]{3}", message = "must be a three-letter ISO currency code") String currency,
        @NotBlank @Size(max = 64) String cardId,
        @NotBlank @Size(max = 100) String merchant,
        @NotBlank @Size(max = 50) String merchantCategory,
        @NotBlank @Size(max = 100) String city,
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
        @NotNull OffsetDateTime timestamp) {
}
