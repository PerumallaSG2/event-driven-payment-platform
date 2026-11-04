package com.gopi.payments.ledger.reconciliation;

import com.gopi.payments.ledger.service.LedgerNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/** Reconciliation reports: run one now, or read a stored one. */
@RestController
@RequestMapping("/reconciliation/runs")
public class ReconciliationController {

    static class RunNotFoundException extends LedgerNotFoundException {
        RunNotFoundException(String what) {
            super(what + " not found");
        }
    }

    private final Reconciliation reconciliation;
    private final ReconciliationRunRepository runs;

    public ReconciliationController(Reconciliation reconciliation, ReconciliationRunRepository runs) {
        this.reconciliation = reconciliation;
        this.runs = runs;
    }

    @PostMapping
    public ResponseEntity<ReconciliationReport> run() {
        ReconciliationReport report = reconciliation.run();
        return ResponseEntity.created(URI.create("/reconciliation/runs/" + report.id())).body(report);
    }

    @GetMapping("/latest")
    public ReconciliationReport latest() {
        return runs.latest().orElseThrow(() -> new RunNotFoundException("reconciliation run"));
    }

    @GetMapping("/{id}")
    public ReconciliationReport get(@PathVariable UUID id) {
        return runs.find(id).orElseThrow(() -> new RunNotFoundException("reconciliation run " + id));
    }
}
