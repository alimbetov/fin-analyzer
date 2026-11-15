package kz.fin.backend.analytics;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Precise, currency-labelled amount. No implicit FX conversion or rounding.
 * Prototype contract: NUMERIC(38,12) compatibility, including intermediate results.
 */
public record FinancialAmount(BigDecimal amount, String currency) {
    public FinancialAmount {
        Objects.requireNonNull(amount, "amount");
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("ISO-style currency code required");
        }
        if (amount.scale() > 12 || amount.precision() - amount.scale() > 26) {
            throw new IllegalArgumentException("AMOUNT_PRECISION_OVERFLOW");
        }
        amount = amount.stripTrailingZeros();
    }

    public static FinancialAmount of(String value, String currency) {
        return new FinancialAmount(new BigDecimal(value), currency);
    }

    public static FinancialAmount zero(String currency) {
        return new FinancialAmount(BigDecimal.ZERO, currency);
    }

    public FinancialAmount plus(FinancialAmount other) {
        sameCurrency(other);
        return new FinancialAmount(amount.add(other.amount), currency);
    }

    public FinancialAmount minus(FinancialAmount other) {
        sameCurrency(other);
        return new FinancialAmount(amount.subtract(other.amount), currency);
    }

    public FinancialAmount times(BigDecimal multiplier) {
        Objects.requireNonNull(multiplier, "multiplier");
        return new FinancialAmount(amount.multiply(multiplier), currency);
    }

    public void sameCurrency(FinancialAmount other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("CURRENCY_MISMATCH: " + currency + " vs " + other.currency);
        }
    }

    public void requireNonNegative(String field) {
        if (amount.signum() < 0) throw new IllegalArgumentException("NEGATIVE_" + field);
    }
}
