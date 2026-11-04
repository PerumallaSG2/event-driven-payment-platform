package com.gopi.payments.e2e;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The services' public HTTP APIs, as a client would call them. */
final class Clients {

    static final Set<String> FINAL = Set.of("COMPLETED", "FAILED");

    record Response(int status, JsonNode body, HttpResponse<String> raw) {}

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private final Platform platform;

    Clients(Platform platform) {
        this.platform = platform;
    }

    // ---- ledger ----

    UUID account(String kind, String name) {
        Response r = post(platform.ledger.baseUrl(), "/accounts", Map.of(), Map.of(
                "name", name, "kind", kind, "currency", "USD"));
        expect(r, 201);
        return UUID.fromString(r.body().get("id").asString());
    }

    void deposit(UUID account, String amount) {
        expect(post(platform.ledger.baseUrl(), "/accounts/" + account + "/deposits", Map.of(),
                Map.of("amount", new BigDecimal(amount))), 201);
    }

    BigDecimal balance(UUID account) {
        Response r = get(platform.ledger.baseUrl(), "/accounts/" + account);
        expect(r, 200);
        return r.body().get("balance").decimalValue();
    }

    JsonNode reconcile() {
        Response r = post(platform.ledger.baseUrl(), "/reconciliation/runs", Map.of(), Map.of());
        expect(r, 201);
        return r.body();
    }

    // ---- payment-api ----

    Map<String, Object> paymentBody(UUID payer, UUID merchant, String amount, String cardId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("payerAccountId", payer);
        body.put("merchantAccountId", merchant);
        body.put("amount", new BigDecimal(amount));
        body.put("currency", "USD");
        body.put("cardId", cardId);
        body.put("merchant", "Corner Coffee");
        body.put("merchantCategory", "RESTAURANT");
        body.put("city", "Austin");
        body.put("latitude", 30.2672);
        body.put("longitude", -97.7431);
        body.put("timestamp", "2026-03-14T14:30:00-05:00");
        return body;
    }

    /** POST /payments; throws {@link UncheckedIOException} when payment-api is unreachable or dies mid-request. */
    Response createPayment(String idempotencyKey, Map<String, Object> body) {
        return post(platform.paymentApi.baseUrl(), "/payments", Map.of("Idempotency-Key", idempotencyKey), body);
    }

    /** POST /payments that must succeed: the payment's id. */
    UUID pay(String idempotencyKey, Map<String, Object> body) {
        Response r = createPayment(idempotencyKey, body);
        expect(r, 201);
        return UUID.fromString(r.body().get("paymentId").asString());
    }

    JsonNode payment(UUID id) {
        Response r = get(platform.paymentApi.baseUrl(), "/payments/" + id);
        expect(r, 200);
        return r.body();
    }

    void review(UUID id, String decision) {
        expect(post(platform.paymentApi.baseUrl(), "/payments/" + id + "/review", Map.of(),
                Map.of("decision", decision)), 202);
    }

    /** Polls GET /payments/{id} until it has {@code status}; fails early if it reaches another final status. */
    JsonNode awaitStatus(UUID id, String status, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            try {
                last = payment(id);
                String current = last.get("status").asString();
                if (current.equals(status)) {
                    return last;
                }
                if (FINAL.contains(current)) {
                    throw new AssertionError("payment " + id + " ended " + current + " (wanted " + status + "): "
                            + last);
                }
            } catch (UncheckedIOException e) {
                // payment-api restarting
            }
            ServiceProcess.sleep(200);
        }
        throw new AssertionError("payment " + id + " not " + status + " within " + timeout + ", last: " + last);
    }

    // ----

    private static void expect(Response r, int status) {
        if (r.status() != status) {
            throw new AssertionError("expected " + status + " from " + r.raw().request().uri() + " but got "
                    + r.status() + ": " + r.raw().body());
        }
    }

    private static Response post(URI base, String path, Map<String, String> headers, Object body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        headers.forEach(request::header);
        return send(request.build());
    }

    private static Response get(URI base, String path) {
        return send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(20)).GET().build());
    }

    private static Response send(HttpRequest request) {
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            String text = response.body();
            JsonNode body = text == null || text.isBlank() ? null : readOrNull(text);
            return new Response(response.statusCode(), body, response);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode readOrNull(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
