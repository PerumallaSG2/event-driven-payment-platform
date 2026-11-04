package com.gopi.payments.processor.messaging;

import com.gopi.payments.processor.messaging.Messages.PaymentReviewed;
import com.gopi.payments.processor.saga.SagaService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** payment-api's {@code POST /payments/{id}/review}, as {@code PaymentReviewed} on payment.reviews. */
@Component
public class PaymentReviewsListener {

    private final SagaService sagas;
    private final ObjectMapper objectMapper;

    public PaymentReviewsListener(SagaService sagas, ObjectMapper objectMapper) {
        this.sagas = sagas;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = Topics.PAYMENT_REVIEWS, groupId = "payment-processor")
    public void on(ConsumerRecord<String, String> record) {
        Envelope<PaymentReviewed> envelope = Envelope.parse(record, objectMapper, PaymentReviewed::typeOf);
        sagas.review(envelope.eventId(), envelope.payload());
    }
}
