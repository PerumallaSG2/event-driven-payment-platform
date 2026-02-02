package com.gopi.payments.payment_api.api;

import java.util.UUID;

public record CreatePaymentResponse(
        UUID paymentId,
        String status
) { }
