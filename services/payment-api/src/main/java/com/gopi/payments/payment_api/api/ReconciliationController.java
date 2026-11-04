package com.gopi.payments.payment_api.api;

import com.gopi.payments.payment_api.repo.FinalPayments;
import com.gopi.payments.payment_api.repo.FinalPayments.FinalPayment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * payment-api's side of the reconciliation that ledger-service runs (item 6): this service's own record of which
 * payments ended and how, read from its own database. Read-only. {@code next} is the {@code after} of the next page,
 * null on the last one.
 */
@RestController
@RequestMapping("/internal/reconciliation")
public class ReconciliationController {

    static final int MAX_LIMIT = 1000;

    public record FinalPaymentsPage(List<FinalPayment> payments, UUID next) {}

    private final FinalPayments finalPayments;

    public ReconciliationController(FinalPayments finalPayments) {
        this.finalPayments = finalPayments;
    }

    @GetMapping("/payments")
    public FinalPaymentsPage payments(@RequestParam(required = false) UUID after,
                                      @RequestParam(defaultValue = "500") int limit) {
        int size = Math.clamp(limit, 1, MAX_LIMIT);
        List<FinalPayment> page = finalPayments.page(after, size);
        UUID next = page.size() == size ? page.get(page.size() - 1).id() : null;
        return new FinalPaymentsPage(page, next);
    }
}
