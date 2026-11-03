package com.gopi.payments.ledger;

import com.gopi.payments.ledger.service.InvalidEntryException;
import com.gopi.payments.ledger.service.LedgerService;
import com.gopi.payments.ledger.service.LedgerService.Line;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The in-code half of the zero-sum rule: no Spring, no database. */
class PostingValidationTest {

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @Test
    void balancedEntryPassesAndIsNormalisedToScaleTwo() {
        List<Line> checked = LedgerService.validate(List.of(
                new Line(a, "USD", new BigDecimal("-10.5")),
                new Line(b, "USD", new BigDecimal("10.50"))));

        assertThat(checked).extracting(l -> l.amount().scale()).containsOnly(2);
        assertThat(checked.get(0).amount()).isEqualByComparingTo("-10.50");
    }

    @Test
    void unbalancedEntryIsRejected() {
        assertThatThrownBy(() -> LedgerService.validate(List.of(
                new Line(a, "USD", new BigDecimal("-10.00")),
                new Line(b, "USD", new BigDecimal("9.99")))))
                .isInstanceOf(InvalidEntryException.class)
                .hasMessageContaining("do not sum to zero");
    }

    @Test
    void entryMustBalanceInEachCurrencySeparately() {
        // Sums to zero overall, but not per currency.
        assertThatThrownBy(() -> LedgerService.validate(List.of(
                new Line(a, "USD", new BigDecimal("-10.00")),
                new Line(b, "EUR", new BigDecimal("10.00")))))
                .isInstanceOf(InvalidEntryException.class)
                .hasMessageContaining("do not sum to zero");
    }

    @Test
    void singlePostingIsRejected() {
        assertThatThrownBy(() -> LedgerService.validate(List.of(new Line(a, "USD", new BigDecimal("1.00")))))
                .isInstanceOf(InvalidEntryException.class)
                .hasMessageContaining("at least two");
    }

    @Test
    void zeroAmountIsRejected() {
        assertThatThrownBy(() -> LedgerService.validate(List.of(
                new Line(a, "USD", BigDecimal.ZERO),
                new Line(b, "USD", BigDecimal.ZERO))))
                .isInstanceOf(InvalidEntryException.class)
                .hasMessageContaining("zero");
    }

    @Test
    void moreThanTwoDecimalsIsRejectedNotRounded() {
        assertThatThrownBy(() -> LedgerService.validate(List.of(
                new Line(a, "USD", new BigDecimal("-10.005")),
                new Line(b, "USD", new BigDecimal("10.005")))))
                .isInstanceOf(InvalidEntryException.class)
                .hasMessageContaining("more than two decimals");
    }
}
