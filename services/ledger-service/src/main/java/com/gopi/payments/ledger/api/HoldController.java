package com.gopi.payments.ledger.api;

import com.gopi.payments.ledger.domain.Hold;
import com.gopi.payments.ledger.service.HoldService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/holds")
public class HoldController {

    private final HoldService holdService;

    public HoldController(HoldService holdService) {
        this.holdService = holdService;
    }

    @PostMapping
    public ResponseEntity<HoldResponse> create(@Valid @RequestBody CreateHoldRequest request) {
        Hold hold = holdService.hold(request.payerAccountId(), request.merchantAccountId(), request.amount(),
                request.currency());
        return ResponseEntity.created(URI.create("/holds/" + hold.getId())).body(HoldResponse.from(hold));
    }

    @GetMapping("/{id}")
    public HoldResponse get(@PathVariable UUID id) {
        return HoldResponse.from(holdService.get(id));
    }

    @PostMapping("/{id}/capture")
    public HoldResponse capture(@PathVariable UUID id) {
        return HoldResponse.from(holdService.capture(id));
    }

    @PostMapping("/{id}/release")
    public HoldResponse release(@PathVariable UUID id) {
        return HoldResponse.from(holdService.release(id));
    }
}
