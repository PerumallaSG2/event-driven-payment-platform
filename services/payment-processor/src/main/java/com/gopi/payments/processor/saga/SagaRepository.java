package com.gopi.payments.processor.saga;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SagaRepository extends JpaRepository<Saga, UUID> {

    /** Row-locks the saga, so a ledger event and the sweep never transition it at the same time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Saga s where s.paymentId = :id")
    Optional<Saga> lockById(@Param("id") UUID id);
}
