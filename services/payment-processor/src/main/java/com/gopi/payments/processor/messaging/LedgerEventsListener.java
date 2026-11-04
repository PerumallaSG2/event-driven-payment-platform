package com.gopi.payments.processor.messaging;

import com.gopi.payments.processor.messaging.Messages.LedgerEvent;
import com.gopi.payments.processor.saga.FraudCheck;
import com.gopi.payments.processor.saga.SagaService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class LedgerEventsListener {

    private final SagaService sagas;
    private final FraudCheck fraudCheck;
    private final ObjectMapper objectMapper;

    public LedgerEventsListener(SagaService sagas, FraudCheck fraudCheck, ObjectMapper objectMapper) {
        this.sagas = sagas;
        this.fraudCheck = fraudCheck;
        this.objectMapper = objectMapper;
    }

    /** FundsHeld commits first; only then is fraud-detection asked (on this thread, bounded by fraud.timeout). */

    @KafkaListener(topics = Topics.LEDGER_EVENTS, groupId = "payment-processor")
    public void on(ConsumerRecord<String, String> record) {
        Envelope<LedgerEvent> envelope = Envelope.parse(record, objectMapper, LedgerEvent::typeOf);
        if (envelope.payload().paymentId() == null) {
            throw new InvalidMessageException(envelope.eventType() + " without paymentId", null);
        }
        if (sagas.on(envelope.eventId(), envelope.payload())) {
            fraudCheck.run(envelope.payload().paymentId());
        }
    }
}
