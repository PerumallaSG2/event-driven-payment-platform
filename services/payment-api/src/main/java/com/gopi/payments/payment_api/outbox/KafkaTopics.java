package com.gopi.payments.payment_api.outbox;

import com.gopi.payments.payment_api.service.PaymentService;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Topics this service produces. Partitions are keyed by paymentId, so one payment's events stay in order. */
@Configuration(proxyBeanMethods = false)
class KafkaTopics {

    @Bean
    NewTopic paymentInitiated() {
        return TopicBuilder.name(PaymentService.TOPIC_PAYMENT_INITIATED).partitions(3).replicas(1).build();
    }
}
