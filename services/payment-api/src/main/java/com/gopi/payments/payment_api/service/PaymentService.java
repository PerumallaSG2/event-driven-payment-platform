package com.gopi.payments.payment_api.service;


import com.gopi.payments.payment_api.domain.Payment;
import com.gopi.payments.payment_api.events.PaymentInitiatedEvent;
import com.gopi.payments.payment_api.repo.PaymentRepository;
import jakarta.transaction.Transactional;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class PaymentService {

    public static final String TOPIC_PAYMENT_INITIATED = "payment.initiated";

    private final PaymentRepository paymentRepository;
    private final KafkaTemplate<String, PaymentInitiatedEvent> kafkaTemplate;

    public PaymentService(PaymentRepository paymentRepository, KafkaTemplate<String, PaymentInitiatedEvent> kafkaTemplate) {
        this.paymentRepository = paymentRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Transactional
    public UUID createPayment(BigDecimal amount, String currency) {
        Payment payment = new Payment(amount, currency);
        paymentRepository.save(payment);

        UUID id = payment.getId();   // now it exists after save

        PaymentInitiatedEvent event = new PaymentInitiatedEvent(id, amount, currency);
        kafkaTemplate.send(TOPIC_PAYMENT_INITIATED, id.toString(), event);

        return id;
    }
}
