package com.gopi.payments.ledger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plays payment-api and payment-processor for the reconciliation tests, on a JDK {@link HttpServer} (no extra
 * library): {@code /internal/reconciliation/payments} pages through {@link #payments} the way payment-api does (by
 * {@code after} and {@code limit}, {@code next} when the page is full), and {@code /internal/reconciliation/stuck-sagas}
 * returns {@link #stuckSagas}. {@link #down} makes both answer 503.
 */
final class PeerStub implements AutoCloseable {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final HttpServer server;
    private final List<Map<String, Object>> payments = new ArrayList<>();
    private final List<Map<String, Object>> stuckSagas = new ArrayList<>();
    private boolean down;
    private int paymentPages;

    private PeerStub(HttpServer server) {
        this.server = server;
    }

    static PeerStub start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            PeerStub stub = new PeerStub(server);
            server.createContext("/internal/reconciliation/payments", stub::payments);
            server.createContext("/internal/reconciliation/stuck-sagas", stub::stuckSagas);
            server.start();
            return stub;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    synchronized void reset() {
        payments.clear();
        stuckSagas.clear();
        down = false;
        paymentPages = 0;
    }

    synchronized void payment(UUID id, String status, String amount, String currency, String failureReason) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", id.toString());
        p.put("status", status);
        p.put("amount", new BigDecimal(amount));
        p.put("currency", currency);
        p.put("failureReason", failureReason);
        payments.add(p);
        payments.sort((a, b) -> ((String) a.get("id")).compareTo((String) b.get("id")));   // payment-api's order
    }

    synchronized void stuckSaga(UUID paymentId, String state, String reason) {
        stuckSagas.add(Map.of("paymentId", paymentId.toString(), "state", state, "attempts", 1,
                "updatedAt", Instant.now().minusSeconds(3600).toString(),
                "deadlineAt", Instant.now().minusSeconds(600).toString(), "reason", reason));
    }

    synchronized void down(boolean down) {
        this.down = down;
    }

    synchronized int paymentPages() {
        return paymentPages;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void payments(HttpExchange exchange) throws IOException {
        Object body;
        synchronized (this) {
            if (down) {
                reply(exchange, 503, Map.of("status", 503));
                return;
            }
            paymentPages++;
            Map<String, String> query = query(exchange.getRequestURI());
            int limit = Integer.parseInt(query.getOrDefault("limit", "500"));
            String after = query.get("after");
            List<Map<String, Object>> page = payments.stream()
                    .filter(p -> after == null || ((String) p.get("id")).compareTo(after) > 0)
                    .limit(limit).toList();
            Map<String, Object> result = new HashMap<>();
            result.put("payments", page);
            result.put("next", page.size() == limit ? page.get(page.size() - 1).get("id") : null);
            body = result;
        }
        reply(exchange, 200, body);
    }

    private void stuckSagas(HttpExchange exchange) throws IOException {
        Object body;
        synchronized (this) {
            if (down) {
                reply(exchange, 503, Map.of("status", 503));
                return;
            }
            body = Map.of("sagas", List.copyOf(stuckSagas), "truncated", false);
        }
        reply(exchange, 200, body);
    }

    private static void reply(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> params = new HashMap<>();
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                int eq = pair.indexOf('=');
                params.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return params;
    }
}
