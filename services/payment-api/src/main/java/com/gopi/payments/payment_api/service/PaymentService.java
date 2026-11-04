package com.gopi.payments.payment_api.service;

import com.gopi.payments.payment_api.api.CreatePaymentRequest;
import com.gopi.payments.payment_api.api.CreatePaymentResponse;
import com.gopi.payments.payment_api.domain.Payment;
import com.gopi.payments.payment_api.events.PaymentInitiatedEvent;
import com.gopi.payments.payment_api.outbox.OutboxRepository;
import com.gopi.payments.payment_api.repo.IdempotencyKey;
import com.gopi.payments.payment_api.repo.IdempotencyKeyRepository;
import com.gopi.payments.payment_api.repo.PaymentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

/**
 * Creates a payment at most once per {@code Idempotency-Key}:
 * <ol>
 *   <li>claim the key (its own, immediately committed statement), or replay / reject based on the existing row;</li>
 *   <li>in one transaction: save the payment, queue payment.initiated in the outbox, and mark the key COMPLETED
 *       with the response to replay;</li>
 *   <li>if that transaction fails, release the claim so the client can retry the same key.</li>
 * </ol>
 * A crash between 1 and 2 writes nothing; the claim goes stale after {@code lock-timeout} and can be taken over.
 */
@Service
public class PaymentService {

    public static final String TOPIC_PAYMENT_INITIATED = "payment.initiated";
    public static final String EVENT_PAYMENT_INITIATED = "PaymentInitiated";
    static final int MAX_KEY_LENGTH = 255;
    /** Bounds the claim loop when other requests keep winning races on the same key. */
    private static final int MAX_CLAIM_ATTEMPTS = 5;

    private final PaymentRepository paymentRepository;
    private final IdempotencyKeyRepository keys;
    private final OutboxRepository outbox;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    private final IdempotencyProperties properties;

    public PaymentService(PaymentRepository paymentRepository, IdempotencyKeyRepository keys,
                          OutboxRepository outbox, TransactionTemplate tx,
                          ObjectMapper objectMapper, IdempotencyProperties properties) {
        this.paymentRepository = paymentRepository;
        this.keys = keys;
        this.outbox = outbox;
        this.tx = tx;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public PaymentResult create(String key, CreatePaymentRequest request) {
        validate(key, request);
        String hash = RequestHash.of(request);
        UUID token = UUID.randomUUID();

        Optional<PaymentResult> replay = claim(key, hash, token);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> createClaimed(key, token, request));
        } catch (RuntimeException e) {
            keys.release(key, token);
            throw e;
        }
    }

    public Payment get(UUID id) {
        return paymentRepository.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
    }

    /** Empty when we hold the claim; otherwise the stored response to replay. Throws for 422 and 409. */
    private Optional<PaymentResult> claim(String key, String hash, UUID token) {
        for (int attempt = 0; attempt < MAX_CLAIM_ATTEMPTS; attempt++) {
            if (keys.tryClaim(key, hash, token, properties.lockTimeout(), properties.ttl())) {
                return Optional.empty();
            }
            Optional<IdempotencyKey> found = keys.find(key);
            if (found.isEmpty()) {
                continue;   // released or cleaned up since the insert conflicted: try to insert again
            }
            IdempotencyKey existing = found.get();
            if (existing.expired()) {
                if (reclaim(key, hash, token)) return Optional.empty();
                continue;
            }
            if (!existing.requestHash().equals(hash)) {
                throw new IdempotencyKeyReusedException();
            }
            if (existing.completed()) {
                return Optional.of(new PaymentResult(existing.paymentId(), existing.responseStatus(),
                        existing.responseBody(), true));
            }
            if (!existing.lockExpired()) {
                throw new IdempotencyKeyInFlightException();
            }
            if (reclaim(key, hash, token)) return Optional.empty();
        }
        throw new IdempotencyKeyInFlightException();
    }

    private boolean reclaim(String key, String hash, UUID token) {
        return keys.reclaim(key, hash, token, properties.lockTimeout(), properties.ttl());
    }

    private PaymentResult createClaimed(String key, UUID token, CreatePaymentRequest r) {
        Payment payment = new Payment(r.payerAccountId(), r.merchantAccountId(), r.amount(), r.currency(),
                r.cardId(), r.merchant(), r.merchantCategory(), r.city(), r.latitude(), r.longitude(), r.timestamp());
        paymentRepository.saveAndFlush(payment);   // flushed: the key row's payment_id references it
        UUID id = payment.getId();

        // Not sent here: the outbox row commits with the payment, and OutboxRelay publishes it.
        outbox.append(TOPIC_PAYMENT_INITIATED, id.toString(), EVENT_PAYMENT_INITIATED,
                new PaymentInitiatedEvent(id, payment.getAmount(), payment.getCurrency()));

        int status = HttpStatus.CREATED.value();
        String body = objectMapper.writeValueAsString(new CreatePaymentResponse(id, payment.getStatus().name()));
        if (!keys.complete(key, token, id, status, body)) {
            // Our claim went stale and another request took the key over: roll back so only one payment exists.
            throw new IdempotencyKeyInFlightException();
        }
        return new PaymentResult(id, status, body, false);
    }

    private static void validate(String key, CreatePaymentRequest request) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new InvalidPaymentRequestException(
                    "Idempotency-Key must be 1-" + MAX_KEY_LENGTH + " characters and not blank");
        }
        if (request.payerAccountId().equals(request.merchantAccountId())) {
            throw new InvalidPaymentRequestException("payerAccountId and merchantAccountId must differ");
        }
    }
}
