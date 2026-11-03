package com.gopi.payments.ledger.api;

import com.gopi.payments.ledger.domain.Account;
import com.gopi.payments.ledger.domain.AccountKind;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountResponse(UUID id, String name, AccountKind kind, String currency, BigDecimal balance,
                              boolean allowNegative) {

    static AccountResponse from(Account a) {
        return new AccountResponse(a.getId(), a.getName(), a.getKind(), a.getCurrency(), a.getBalance(),
                a.isAllowNegative());
    }
}
