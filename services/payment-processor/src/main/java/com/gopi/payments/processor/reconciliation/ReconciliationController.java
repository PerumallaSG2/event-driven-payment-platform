package com.gopi.payments.processor.reconciliation;

import com.gopi.payments.processor.reconciliation.StuckSagas.StuckSaga;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The processor's side of the reconciliation that ledger-service runs (item 6): sagas stuck past their deadline,
 * from this service's own database. Read-only. {@code truncated} means there were more than {@link #LIMIT}.
 */
@RestController
@RequestMapping("/internal/reconciliation")
public class ReconciliationController {

    static final int LIMIT = 1000;

    public record StuckSagasReport(List<StuckSaga> sagas, boolean truncated) {}

    private final StuckSagas stuckSagas;

    public ReconciliationController(StuckSagas stuckSagas) {
        this.stuckSagas = stuckSagas;
    }

    @GetMapping("/stuck-sagas")
    public StuckSagasReport stuckSagas() {
        List<StuckSaga> found = stuckSagas.find(LIMIT + 1);
        return found.size() > LIMIT
                ? new StuckSagasReport(found.subList(0, LIMIT), true)
                : new StuckSagasReport(found, false);
    }
}
