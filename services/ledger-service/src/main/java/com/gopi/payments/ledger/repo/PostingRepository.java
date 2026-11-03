package com.gopi.payments.ledger.repo;

import com.gopi.payments.ledger.domain.Posting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostingRepository extends JpaRepository<Posting, Long> {
}
