package com.gopi.payments.payment_api.api;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadFeature;

@Configuration(proxyBeanMethods = false)
class JacksonConfig {

    /**
     * A JSON object with the same field twice is a parse error, so the client gets a 400 ProblemDetail. Without it,
     * a repeated record field fails inside Jackson's databind (a 500), and other repeated fields silently keep the
     * last value. No Boot property covers this feature: {@code spring.jackson.read} only takes JsonReadFeature.
     */
    @Bean
    JsonMapperBuilderCustomizer strictDuplicateDetection() {
        return builder -> builder.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
    }
}
