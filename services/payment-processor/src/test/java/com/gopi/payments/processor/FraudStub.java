package com.gopi.payments.processor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A scripted stand-in for fraud-detection's {@code /api/transactions} on a JDK {@link HttpServer} (no extra library,
 * no real fraud app). POSTs and GETs each take their replies from a queue; the last one repeats. In a body,
 * {@code {id}} becomes the transaction id: a new one per POST, the path's on a GET. It records the POST bodies.
 */
final class FraudStub implements AutoCloseable {

    record Reply(int status, String body, Duration delay) {}

    static final Reply ACCEPTED = new Reply(202, "{\"id\":\"{id}\",\"status\":\"PENDING\"}", Duration.ZERO);
    static final Reply NOT_SCORED = new Reply(404, "{\"status\":404,\"error\":\"Not Found\"}", Duration.ZERO);

    private final HttpServer server;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Deque<Reply> posts = new ArrayDeque<>();
    private final Deque<Reply> gets = new ArrayDeque<>();
    private final List<String> postBodies = new ArrayList<>();
    private int getCount;

    private FraudStub(HttpServer server) {
        this.server = server;
    }

    static FraudStub start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            FraudStub stub = new FraudStub(server);
            server.createContext("/api/transactions", stub::handle);
            server.setExecutor(stub.pool);
            server.start();
            return stub;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static Reply decided(String decision, int score) {
        return new Reply(200, """
                {"id":"{id}","cardId":"card-1","amount":42.50,"merchant":"Corner Coffee",
                 "merchantCategory":"RESTAURANT","city":"Austin","latitude":30.2672,"longitude":-97.7431,
                 "timestamp":"2026-03-14T02:30:00-05:00","riskScore":%d,"decision":"%s",
                 "triggeredRules":[{"rule":"ODD_HOURS","points":%d,"reason":"02:30 local time"}],
                 "createdAt":"2026-03-14T07:30:01Z"}""".formatted(score, decision, score), Duration.ZERO);
    }

    static Reply slow(Reply reply, Duration delay) {
        return new Reply(reply.status(), reply.body(), delay);
    }

    /** fraud-detection accepts the POST and answers this decision on the first GET. */
    synchronized void decides(String decision, int score) {
        script(List.of(ACCEPTED), List.of(decided(decision, score)));
    }

    synchronized void script(List<Reply> postReplies, List<Reply> getReplies) {
        posts.clear();
        posts.addAll(postReplies);
        gets.clear();
        gets.addAll(getReplies);
        postBodies.clear();
        getCount = 0;
    }

    URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    synchronized List<String> postBodies() {
        return List.copyOf(postBodies);
    }

    synchronized int postCount() {
        return postBodies.size();
    }

    synchronized int getCount() {
        return getCount;
    }

    @Override
    public void close() {
        server.stop(0);
        pool.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        Reply reply;
        String id;
        synchronized (this) {
            if ("POST".equals(exchange.getRequestMethod())) {
                postBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                reply = next(posts);
                id = UUID.randomUUID().toString();
            } else {
                getCount++;
                reply = next(gets);
                String path = exchange.getRequestURI().getPath();
                id = path.substring(path.lastIndexOf('/') + 1);
            }
        }
        try {
            if (!reply.delay().isZero()) {
                Thread.sleep(reply.delay().toMillis());
            }
            byte[] body = reply.body().replace("{id}", id).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // the client gave up (timeout) and closed the connection
        } finally {
            exchange.close();
        }
    }

    private static Reply next(Deque<Reply> replies) {
        if (replies.isEmpty()) {
            return new Reply(500, "{\"error\":\"not scripted\"}", Duration.ZERO);
        }
        return replies.size() == 1 ? replies.peekFirst() : replies.pollFirst();
    }
}
