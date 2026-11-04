package com.gopi.payments.ledger.reconciliation;

import com.gopi.payments.ledger.reconciliation.ReconciliationReport.Status;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ReconciliationRunRepository {

    private final JdbcClient jdbc;

    public ReconciliationRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The run and its (already capped) mismatches, in one transaction. */
    @Transactional
    public void save(ReconciliationReport report) {
        jdbc.sql("""
                        INSERT INTO reconciliation_runs (id, started_at, finished_at, status, mismatch_count,
                            payments_checked)
                        VALUES (:id, :startedAt, :finishedAt, :status, :count, :checked)
                        """)
                .param("id", report.id()).param("startedAt", Timestamp.from(report.startedAt()))
                .param("finishedAt", Timestamp.from(report.finishedAt())).param("status", report.status().name())
                .param("count", report.mismatchCount()).param("checked", report.paymentsChecked())
                .update();
        for (Mismatch m : report.mismatches()) {
            jdbc.sql("INSERT INTO reconciliation_mismatches (run_id, kind, subject, details) "
                            + "VALUES (:run, :kind, :subject, :details)")
                    .param("run", report.id()).param("kind", m.kind().name()).param("subject", m.subject())
                    .param("details", m.details())
                    .update();
        }
    }

    @Transactional(readOnly = true)
    public Optional<ReconciliationReport> find(UUID id) {
        return load("WHERE id = :id", id);
    }

    @Transactional(readOnly = true)
    public Optional<ReconciliationReport> latest() {
        return load("ORDER BY started_at DESC LIMIT 1", null);
    }

    private Optional<ReconciliationReport> load(String where, UUID id) {
        JdbcClient.StatementSpec run = jdbc.sql("SELECT id, status, started_at, finished_at, payments_checked, "
                + "mismatch_count FROM reconciliation_runs " + where);
        if (id != null) {
            run = run.param("id", id);
        }
        return run.query((rs, n) -> new ReconciliationReport(rs.getObject("id", UUID.class),
                        Status.valueOf(rs.getString("status")), rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("finished_at").toInstant(), rs.getInt("payments_checked"),
                        rs.getInt("mismatch_count"), List.of()))
                .optional()
                .map(r -> new ReconciliationReport(r.id(), r.status(), r.startedAt(), r.finishedAt(),
                        r.paymentsChecked(), r.mismatchCount(), mismatches(r.id())));
    }

    private List<Mismatch> mismatches(UUID runId) {
        return jdbc.sql("SELECT kind, subject, details FROM reconciliation_mismatches WHERE run_id = :run ORDER BY id")
                .param("run", runId)
                .query((rs, n) -> new Mismatch(MismatchKind.valueOf(rs.getString("kind")), rs.getString("subject"),
                        rs.getString("details")))
                .list();
    }
}
