package com.gopi.payments.payment_api.api;

import com.gopi.payments.payment_api.service.PaymentResult;
import com.gopi.payments.payment_api.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /** 201 with the body as stored for the key, so a replay is byte for byte the first response. */
    @PostMapping
    public ResponseEntity<String> create(@RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
                                         @Valid @RequestBody CreatePaymentRequest request) {
        PaymentResult result = paymentService.create(idempotencyKey, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(result.httpStatus())
                .location(URI.create("/payments/" + result.paymentId()))
                .contentType(MediaType.APPLICATION_JSON);
        if (result.replayed()) {
            response.header(IDEMPOTENT_REPLAYED, "true");
        }
        return response.body(result.body());
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable UUID id) {
        return PaymentResponse.from(paymentService.get(id));
    }
}
