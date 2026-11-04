package com.gopi.payments.ledger.reconciliation;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Read-only queries on the ledger's own tables for the reconciliation. */
@Repository
public class LedgerChecks {

    /** The ledger's hold for a payment. */
    public record PaymentHold(UUID paymentId, String status, BigDecimal amount, String currency) {}

    private final JdbcClient jdbc;

    public LedgerChecks(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Entries that don't sum to zero, cached balances that drifted from their postings, and HOLDS accounts that
     * don't net to the open holds. One REPEATABLE READ snapshot, so a posting committed mid-check can't make a
     * balance look wrong.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Mismatch> run() {
        List<Mismatch> found = new ArrayList<>();
        found.addAll(jdbc.sql("""
                        SELECT entry_id, currency, sum(amount) AS total FROM postings
                        GROUP BY entry_id, currency HAVING sum(amount) <> 0
                        ORDER BY entry_id, currency
                        """)
                .query((rs, n) -> new Mismatch(MismatchKind.ENTRY_UNBALANCED, rs.getString("entry_id"),
                        "postings in " + rs.getString("currency") + " sum to " + rs.getBigDecimal("total")
                                + ", not 0"))
                .list());
        found.addAll(jdbc.sql("""
                        SELECT a.id, a.currency, a.balance, coalesce(p.total, 0) AS total
                        FROM accounts a
                        LEFT JOIN (SELECT account_id, sum(amount) AS total FROM postings GROUP BY account_id) p
                            ON p.account_id = a.id
                        WHERE a.balance <> coalesce(p.total, 0)
                        ORDER BY a.id
                        """)
                .query((rs, n) -> new Mismatch(MismatchKind.BALANCE_DRIFT, rs.getString("id"),
                        "balance " + rs.getBigDecimal("balance") + " " + rs.getString("currency")
                                + ", postings sum to " + rs.getBigDecimal("total")))
                .list());
        // The HOLDS account's net is the sum of its postings (a drifted cached balance is BALANCE_DRIFT already).
        found.addAll(jdbc.sql("""
                        WITH holds_net AS (
                            SELECT a.currency, coalesce(sum(p.amount), 0) AS net
                            FROM accounts a LEFT JOIN postings p ON p.account_id = a.id
                            WHERE a.kind = 'HOLDS' GROUP BY a.currency),
                        open_holds AS (
                            SELECT currency, sum(amount) AS total FROM holds WHERE status = 'OPEN' GROUP BY currency)
                        SELECT coalesce(n.currency, o.currency) AS currency, coalesce(n.net, 0) AS net,
                               coalesce(o.total, 0) AS open_total
                        FROM holds_net n FULL JOIN open_holds o ON o.currency = n.currency
                        WHERE coalesce(n.net, 0) <> coalesce(o.total, 0)
                        ORDER BY 1
                        """)
                .query((rs, n) -> new Mismatch(MismatchKind.HOLDS_ACCOUNT_DRIFT, rs.getString("currency"),
                        "HOLDS account nets to " + rs.getBigDecimal("net") + ", open holds total "
                                + rs.getBigDecimal("open_total")))
                .list());
        return found;
    }

    /** The holds of these payments, by payment id; a payment with no hold is absent. */
    @Transactional(readOnly = true)
    public Map<UUID, PaymentHold> holdsFor(Collection<UUID> paymentIds) {
        if (paymentIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("SELECT payment_id, status, amount, currency FROM holds WHERE payment_id IN (:ids)")
                .param("ids", paymentIds)
                .query((rs, n) -> new PaymentHold(rs.getObject("payment_id", UUID.class), rs.getString("status"),
                        rs.getBigDecimal("amount"), rs.getString("currency")))
                .stream().collect(Collectors.toMap(PaymentHold::paymentId, h -> h));
    }
}
