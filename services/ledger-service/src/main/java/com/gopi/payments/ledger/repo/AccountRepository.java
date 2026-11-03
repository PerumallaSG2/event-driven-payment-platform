package com.gopi.payments.ledger.repo;

import com.gopi.payments.ledger.domain.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    /**
     * Row-locks the accounts ({@code SELECT ... FOR UPDATE}/{@code FOR NO KEY UPDATE}) in id order. Every writer
     * locks in the same order, so two entries touching the same accounts can't deadlock.
     * <p>The accounts must not already be loaded in the current persistence context: Hibernate would keep the
     * stale managed copy. That's why the lookups below return ids and currencies, not entities.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id in :ids order by a.id")
    List<Account> lockAllByIdOrdered(@Param("ids") Collection<UUID> ids);

    @Query(value = "select currency from accounts where id = :id", nativeQuery = true)
    Optional<String> findCurrencyById(@Param("id") UUID id);

    @Query(value = "select id from accounts where kind = :kind and currency = :currency", nativeQuery = true)
    Optional<UUID> findSystemAccountId(@Param("kind") String kind, @Param("currency") String currency);

    /** Creates the system account unless another transaction already has (waits for it if it's uncommitted). */
    @Modifying
    @Query(value = """
            insert into accounts (id, name, kind, currency, balance, allow_negative, created_at)
            values (:id, :name, :kind, :currency, 0, true, now())
            on conflict (kind, currency) where kind in ('HOLDS', 'EXTERNAL') do nothing
            """, nativeQuery = true)
    int insertSystemAccountIfAbsent(@Param("id") UUID id, @Param("name") String name,
                                    @Param("kind") String kind, @Param("currency") String currency);
}
