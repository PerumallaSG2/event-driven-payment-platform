package com.gopi.payments.ledger.repo;

import com.gopi.payments.ledger.domain.Hold;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface HoldRepository extends JpaRepository<Hold, UUID> {

    /** Row-locks the hold, so a concurrent capture and release of the same hold can't both succeed. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Hold h where h.id = :id")
    Optional<Hold> lockById(@Param("id") UUID id);

    Optional<Hold> findByPaymentId(UUID paymentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Hold h where h.paymentId = :paymentId")
    Optional<Hold> lockByPaymentId(@Param("paymentId") UUID paymentId);
}
