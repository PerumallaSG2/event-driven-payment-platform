package com.gopi.payments.ledger.repo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class VoidedPaymentRepository {

    private final JdbcClient jdbc;

    public VoidedPaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertIfAbsent(UUID paymentId) {
        jdbc.sql("INSERT INTO voided_payments (payment_id) VALUES (:id) ON CONFLICT DO NOTHING")
                .param("id", paymentId).update();
    }

    public boolean exists(UUID paymentId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM voided_payments WHERE payment_id = :id)")
                .param("id", paymentId).query(Boolean.class).single();
    }
}
