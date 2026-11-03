package com.gopi.payments.ledger.api;

import com.gopi.payments.ledger.domain.Account;
import com.gopi.payments.ledger.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        Account account = accountService.create(request.name(), request.kind(), request.currency(),
                request.allowNegativeOrDefault());
        return ResponseEntity.created(URI.create("/accounts/" + account.getId())).body(AccountResponse.from(account));
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable UUID id) {
        return AccountResponse.from(accountService.get(id));
    }

    @PostMapping("/{id}/deposits")
    @ResponseStatus(HttpStatus.CREATED)
    public DepositResponse deposit(@PathVariable UUID id, @Valid @RequestBody DepositRequest request) {
        return new DepositResponse(accountService.deposit(id, request.amount()).getId());
    }
}
