package com.gopi.payments.payment_api.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record CreatePaymentRequest(
        @NotNull @Positive BigDecimal amount,
        @NotBlank String currency
) { }
