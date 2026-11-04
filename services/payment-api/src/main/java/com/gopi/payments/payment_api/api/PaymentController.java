package com.gopi.payments.payment_api.api;

import com.gopi.payments.payment_api.service.PaymentResult;
import com.gopi.payments.payment_api.service.PaymentReviewService;
import com.gopi.payments.payment_api.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    private final PaymentService paymentService;
    private final PaymentReviewService reviewService;
    private final ObjectMapper objectMapper;

    public PaymentController(PaymentService paymentService, PaymentReviewService reviewService,
                             ObjectMapper objectMapper) {
        this.paymentService = paymentService;
        this.reviewService = reviewService;
        this.objectMapper = objectMapper;
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
        return PaymentResponse.from(paymentService.get(id), objectMapper);
    }

    /**
     * 202: the review is recorded and handed to the saga; the payment stays PENDING_REVIEW until the saga reports
     * COMPLETED (approve) or FAILED (reject). 409 when the payment isn't waiting for a review.
     */
    @PostMapping("/{id}/review")
    public ResponseEntity<Void> review(@PathVariable UUID id, @Valid @RequestBody ReviewRequest request) {
        reviewService.review(id, request.approve());
        return ResponseEntity.accepted().location(URI.create("/payments/" + id)).build();
    }
}
