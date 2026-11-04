package com.gopi.payments.ledger.reconciliation;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The other services' side of the reconciliation, from their own read-only endpoints: the ledger never reads their
 * tables. Failures surface as {@link org.springframework.web.client.RestClientException}.
 */
@Component
public class PeerClient {

    public record FinalPayment(UUID id, String status, BigDecimal amount, String currency, String failureReason) {}

    public record FinalPaymentsPage(List<FinalPayment> payments, UUID next) {}

    public record StuckSaga(UUID paymentId, String state, int attempts, Instant updatedAt, Instant deadlineAt,
                            String reason) {}

    public record StuckSagasReport(List<StuckSaga> sagas, boolean truncated) {}

    private final RestClient paymentApi;
    private final RestClient processor;

    public PeerClient(ReconciliationProperties properties) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build());
        factory.setReadTimeout(properties.readTimeout());
        this.paymentApi = RestClient.builder().baseUrl(properties.paymentApiUrl().toString())
                .requestFactory(factory).build();
        this.processor = RestClient.builder().baseUrl(properties.processorUrl().toString())
                .requestFactory(factory).build();
    }

    /** payment-api's COMPLETED and FAILED payments with an id after {@code after} (from the start if null). */
    public FinalPaymentsPage finalPayments(UUID after, int limit) {
        return paymentApi.get()
                .uri(b -> {
                    b.path("/internal/reconciliation/payments").queryParam("limit", limit);
                    if (after != null) {
                        b.queryParam("after", after);
                    }
                    return b.build();
                })
                .retrieve().body(FinalPaymentsPage.class);
    }

    /** payment-processor's sagas stuck past their deadline. */
    public StuckSagasReport stuckSagas() {
        return processor.get().uri("/internal/reconciliation/stuck-sagas").retrieve().body(StuckSagasReport.class);
    }
}
