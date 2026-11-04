package com.gopi.payments.payment_api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** HTTP helper for the payment-api tests: raw JSON in, status + headers + raw body out (errors don't throw). */
final class PaymentApi {

    record Reply(HttpStatusCode status, HttpHeaders headers, String body) {
        JsonNode json(ObjectMapper mapper) {
            return mapper.readTree(body);
        }
    }

    private final RestClient http;
    private final ObjectMapper mapper;

    PaymentApi(int port, ObjectMapper mapper) {
        this.http = RestClient.create("http://localhost:" + port);
        this.mapper = mapper;
    }

    /** A valid request body with a fresh cardId (so tests can count their own payments) and fresh accounts. */
    static Map<String, Object> validBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("payerAccountId", UUID.randomUUID().toString());
        body.put("merchantAccountId", UUID.randomUUID().toString());
        body.put("amount", "42.50");
        body.put("currency", "USD");
        body.put("cardId", "card-" + UUID.randomUUID());
        body.put("merchant", "Corner Coffee");
        body.put("merchantCategory", "RESTAURANT");
        body.put("city", "Austin");
        body.put("latitude", 30.2672);
        body.put("longitude", -97.7431);
        body.put("timestamp", "2026-03-14T02:30:00-05:00");
        return body;
    }

    Reply post(String key, Object body) {
        return post(key, mapper.writeValueAsString(body));
    }

    Reply post(String key, String json) {
        RestClient.RequestBodySpec request = http.post().uri("/payments").contentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return request.body(json).exchange((req, res) -> new Reply(res.getStatusCode(), res.getHeaders(),
                new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    /** POST without an Idempotency-Key, e.g. a review. */
    Reply postTo(String path, String json) {
        return http.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(json)
                .exchange((req, res) -> new Reply(res.getStatusCode(), res.getHeaders(),
                        new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    Reply get(String path) {
        return http.get().uri(path).exchange((req, res) -> new Reply(res.getStatusCode(), res.getHeaders(),
                new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }
}
