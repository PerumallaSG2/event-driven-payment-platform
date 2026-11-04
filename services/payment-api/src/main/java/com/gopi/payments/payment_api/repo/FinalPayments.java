package com.gopi.payments.payment_api.repo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Payments in a final status (COMPLETED, FAILED), for ledger-service's reconciliation. Keyset-paged by id, so a
 * page is one index range scan however many payments there are.
 */
@Repository
public class FinalPayments {

    public record FinalPayment(UUID id, String status, BigDecimal amount, String currency, String failureReason) {}

    private final JdbcClient jdbc;

    public FinalPayments(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Up to {@code limit} final payments with an id greater than {@code after} (all, if null), by id. */
    public List<FinalPayment> page(UUID after, int limit) {
        return jdbc.sql("""
                        SELECT id, status, amount, currency, failure_reason FROM payments
                        WHERE status IN ('COMPLETED', 'FAILED') AND (CAST(:after AS uuid) IS NULL OR id > :after)
                        ORDER BY id LIMIT :limit
                        """)
                .param("after", after).param("limit", limit)
                .query((rs, n) -> new FinalPayment(rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("failure_reason")))
                .list();
    }
}
