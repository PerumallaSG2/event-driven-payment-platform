package com.gopi.payments.processor.fraud;

import com.gopi.payments.processor.fraud.FraudResult.Scored;
import com.gopi.payments.processor.fraud.FraudResult.TriggeredRule;
import com.gopi.payments.processor.fraud.FraudResult.Unavailable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * fraud-detection over HTTP. It scores asynchronously: {@code POST /api/transactions} answers 202 with an id, and
 * {@code GET /api/transactions/{id}} is 404 until the transaction is scored. One {@link #check} submits once and
 * polls until a decision or the {@code fraud.timeout} budget is spent.
 * <p>
 * The POST is retried only when fraud-detection certainly didn't take it (connection refused or not established,
 * 503). A POST that timed out may have been accepted, and fraud-detection has no idempotency key: sending it again
 * could count the transaction twice in its velocity rules, so that is a TIMEOUT instead.
 */
@Component
public class HttpFraudClient implements FraudClient {

    private static final Logger log = LoggerFactory.getLogger(HttpFraudClient.class);
    private static final String TRANSACTIONS = "/api/transactions";

    private final FraudProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public HttpFraudClient(FraudProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public FraudResult check(FraudRequest request) {
        long deadline = System.nanoTime() + properties.timeout().toNanos();
        try {
            Submitted submitted = submit(mapper.writeValueAsString(request), deadline);
            if (submitted.failure() != null) {
                return submitted.failure();
            }
            return poll(submitted.id(), deadline);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Unavailable(Unavailable.TIMEOUT, "interrupted");
        } catch (RuntimeException e) {   // never throw: the saga treats every failure as fail closed
            return new Unavailable(Unavailable.MALFORMED, e.toString());
        }
    }

    private record Submitted(UUID id, Unavailable failure) {}

    private Submitted submit(String body, long deadline) throws InterruptedException {
        String last = "no attempt";
        for (int attempt = 1; attempt <= properties.submitAttempts(); attempt++) {
            Duration left = remaining(deadline);
            if (left.isZero()) {
                return new Submitted(null, new Unavailable(Unavailable.TIMEOUT, "budget spent submitting: " + last));
            }
            HttpRequest post = HttpRequest.newBuilder(uri(TRANSACTIONS))
                    .timeout(min(properties.readTimeout(), left))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response;
            try {
                response = http.send(post, HttpResponse.BodyHandlers.ofString());
            } catch (HttpConnectTimeoutException | ConnectException e) {
                last = "not reachable: " + e;   // the request never left: safe to try again
                pause(deadline);
                continue;
            } catch (HttpTimeoutException e) {
                return new Submitted(null, new Unavailable(Unavailable.TIMEOUT, "no answer to the POST: " + e));
            } catch (IOException e) {
                if (hasCause(e, ConnectException.class)) {
                    last = "not reachable: " + e;
                    pause(deadline);
                    continue;
                }
                return new Submitted(null, new Unavailable(Unavailable.DOWN, "POST failed: " + e));
            }
            int status = response.statusCode();
            if (status == 503) {   // fraud-detection could not take it; nothing was queued
                last = "503 " + response.body();
                pause(deadline);
                continue;
            }
            if (status != 202) {
                return new Submitted(null, new Unavailable(Unavailable.REJECTED, "POST answered " + status + " "
                        + response.body()));
            }
            try {
                JsonNode id = mapper.readTree(response.body()).get("id");
                return new Submitted(UUID.fromString(id.asString()), null);
            } catch (JacksonException | IllegalArgumentException | NullPointerException e) {
                return new Submitted(null, new Unavailable(Unavailable.REJECTED, "202 without an id: "
                        + response.body()));
            }
        }
        return new Submitted(null, new Unavailable(Unavailable.DOWN, properties.submitAttempts()
                + " attempts, last: " + last));
    }

    private FraudResult poll(UUID id, long deadline) throws InterruptedException {
        String last = "not polled";
        while (true) {
            Duration left = remaining(deadline);
            if (left.isZero()) {
                return new Unavailable(Unavailable.TIMEOUT, "no decision for " + id + " in "
                        + properties.timeout() + ", last: " + last);
            }
            HttpRequest get = HttpRequest.newBuilder(uri(TRANSACTIONS + "/" + id))
                    .timeout(min(properties.readTimeout(), left))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            try {
                HttpResponse<String> response = http.send(get, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return parse(id, response.body());
                }
                last = String.valueOf(response.statusCode());   // 404: not scored yet; 5xx: try again
            } catch (IOException e) {   // includes a timed-out GET: polling is safe to repeat
                last = e.toString();
            }
            pause(deadline);
        }
    }

    /** A 200 with anything but a complete decision for this id is MALFORMED: no decision, so fail closed. */
    private FraudResult parse(UUID id, String body) {
        try {
            JsonNode node = mapper.readTree(body);
            JsonNode decision = node.get("decision");
            JsonNode score = node.get("riskScore");
            JsonNode echoed = node.get("id");
            if (decision == null || !decision.isString() || score == null || !score.isInt()
                    || echoed == null || !id.toString().equals(echoed.asString())) {
                return malformed(id, body);
            }
            List<TriggeredRule> rules = new ArrayList<>();
            JsonNode triggered = node.get("triggeredRules");
            if (triggered != null && !triggered.isNull()) {
                if (!triggered.isArray()) {
                    return malformed(id, body);
                }
                for (JsonNode rule : triggered) {
                    JsonNode name = rule.get("rule");
                    JsonNode points = rule.get("points");
                    JsonNode reason = rule.get("reason");
                    if (name == null || !name.isString() || points == null || !points.isInt()) {
                        return malformed(id, body);
                    }
                    rules.add(new TriggeredRule(name.asString(), points.intValue(),
                            reason == null || reason.isNull() ? null : reason.asString()));
                }
            }
            return new Scored(id, FraudDecision.valueOf(decision.asString()), score.intValue(), List.copyOf(rules));
        } catch (JacksonException | IllegalArgumentException e) {   // not JSON, or an unknown decision
            return malformed(id, body);
        }
    }

    private Unavailable malformed(UUID id, String body) {
        log.error("fraud-detection answered an unreadable decision for {}: {}", id, body);
        return new Unavailable(Unavailable.MALFORMED, "unreadable decision for " + id + ": " + body);
    }

    private URI uri(String path) {
        return properties.baseUrl().resolve(path);
    }

    private void pause(long deadline) throws InterruptedException {
        long sleep = Math.min(properties.pollInterval().toMillis(), remaining(deadline).toMillis());
        if (sleep > 0) {
            Thread.sleep(sleep);
        }
    }

    private static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        return nanos <= 0 ? Duration.ZERO : Duration.ofNanos(nanos);
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
