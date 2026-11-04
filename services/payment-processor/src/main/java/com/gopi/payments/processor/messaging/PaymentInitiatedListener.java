package com.gopi.payments.processor.messaging;

import com.gopi.payments.processor.messaging.Messages.PaymentInitiated;
import com.gopi.payments.processor.saga.SagaService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class PaymentInitiatedListener {

    private final SagaService sagas;
    private final ObjectMapper objectMapper;

    public PaymentInitiatedListener(SagaService sagas, ObjectMapper objectMapper) {
        this.sagas = sagas;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = Topics.PAYMENT_INITIATED, groupId = "payment-processor")
    public void on(ConsumerRecord<String, String> record) {
        Envelope<PaymentInitiated> envelope = Envelope.parse(record, objectMapper, PaymentInitiated::typeOf);
        sagas.start(envelope.eventId(), envelope.payload());
    }
}
