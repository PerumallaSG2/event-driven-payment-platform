package com.gopi.payments.e2e;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * fraud-detection's {@code /api/transactions} on a JDK {@link HttpServer}, scripted per {@code cardId} (unscripted
 * cards are approved). A slimmed copy of payment-processor's test {@code FraudStub}: test sources aren't shared
 * between modules. Always the same port, so it can be stopped (fraud-detection down) and started again.
 */
final class FraudStub {

    enum Behavior {
        APPROVE, BLOCK, REVIEW,
        /** decides APPROVE, but every GET answers after 3 s: no decision within the processor's 2 s budget */
        SLOW,
        /** the POST is held until {@link #releaseHeld()}: the processor is mid-check */
        HANG
    }

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    final int port;
    private final Map<String, Behavior> behaviors = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> posts = new ConcurrentHashMap<>();
    private final Map<UUID, Behavior> transactions = new ConcurrentHashMap<>();
    private volatile CountDownLatch held = new CountDownLatch(1);
    private volatile CountDownLatch release = new CountDownLatch(1);
    private HttpServer server;
    private ExecutorService pool;

    FraudStub(int port) {
        this.port = port;
    }

    URI baseUrl() {
        return URI.create("http://127.0.0.1:" + port);
    }

    void start() {
        try {
            pool = Executors.newCachedThreadPool();
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 50);
            server.createContext("/api/transactions", this::handle);
            server.setExecutor(pool);
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** fraud-detection down: connections to its port are refused. */
    void stop() {
        releaseHeld();
        server.stop(0);
        pool.shutdownNow();
    }

    void script(String cardId, Behavior behavior) {
        behaviors.put(cardId, behavior);
        if (behavior == Behavior.HANG) {
            held = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }
    }

    int posts(String cardId) {
        AtomicInteger n = posts.get(cardId);
        return n == null ? 0 : n.get();
    }

    /** Waits until a POST for a HANG card arrived and is being held. */
    void awaitHeld(Duration timeout) throws InterruptedException {
        if (!held.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new AssertionError("no fraud check arrived within " + timeout);
        }
    }

    void releaseHeld() {
        release.countDown();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if ("POST".equals(exchange.getRequestMethod())) {
                String cardId = MAPPER.readTree(exchange.getRequestBody().readAllBytes()).get("cardId").asString();
                posts.computeIfAbsent(cardId, c -> new AtomicInteger()).incrementAndGet();
                Behavior behavior = behaviors.getOrDefault(cardId, Behavior.APPROVE);
                if (behavior == Behavior.HANG) {
                    held.countDown();
                    release.await(60, TimeUnit.SECONDS);
                }
                UUID id = UUID.randomUUID();
                transactions.put(id, behavior);
                reply(exchange, 202, "{\"id\":\"" + id + "\",\"status\":\"PENDING\"}");
            } else {
                String path = exchange.getRequestURI().getPath();
                UUID id = UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
                Behavior behavior = transactions.get(id);
                if (behavior == null) {
                    reply(exchange, 404, "{\"status\":404,\"error\":\"Not Found\"}");
                    return;
                }
                if (behavior == Behavior.SLOW) {
                    Thread.sleep(3000);
                }
                String decision = switch (behavior) {
                    case BLOCK -> "BLOCK";
                    case REVIEW -> "REVIEW";
                    default -> "APPROVE";
                };
                reply(exchange, 200, "{\"id\":\"" + id + "\",\"riskScore\":40,\"decision\":\"" + decision
                        + "\",\"triggeredRules\":[{\"rule\":\"E2E\",\"points\":40,\"reason\":\"scripted\"}]}");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            // the client gave up or died (timeout, killed processor)
        } finally {
            exchange.close();
        }
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
