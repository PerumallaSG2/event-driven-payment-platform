package com.gopi.payments.payment_api.api;

import com.gopi.payments.payment_api.events.PaymentInitiatedEvent;
import com.gopi.payments.payment_api.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/payments")
public class PaymentController {
private  final PaymentService paymentService;
	public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CreatePaymentResponse create(@Valid  @RequestBody CreatePaymentRequest request) {
        var id = paymentService.createPayment(request.amount(),request.currency());
        return new CreatePaymentResponse(id, "CREATED");
    }
}
