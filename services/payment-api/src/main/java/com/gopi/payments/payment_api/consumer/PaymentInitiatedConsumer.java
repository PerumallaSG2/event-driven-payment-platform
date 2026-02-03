package com.gopi.payments.payment_api.consumer;


import com.gopi.payments.payment_api.events.PaymentInitiatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class PaymentInitiatedConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(PaymentInitiatedConsumer.class);

    private final ObjectMapper objectMapper;

    public PaymentInitiatedConsumer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "payment.initiated",
            groupId = "payment-api-consumer"
    )
    public void handle(String message) {
        try {
            PaymentInitiatedEvent event =
                    objectMapper.readValue(message, PaymentInitiatedEvent.class);

            log.info(
                    "✅ Consumed typed event: paymentId={}, amount={}, currency={}",
                    event.paymentId(),
                    event.amount(),
                    event.currency()
            );

        } catch (Exception e) {
            log.error("❌ Failed to deserialize message: {}", message, e);
        }
    }
}
