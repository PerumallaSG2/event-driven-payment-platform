package com.gopi.payments.payment_api.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** {@code POST /payments/{id}/review}: {@code approve} captures the held money, {@code reject} releases it. */
public record ReviewRequest(@NotNull @Pattern(regexp = "approve|reject") String decision) {

    public boolean approve() {
        return "approve".equals(decision);
    }
}
