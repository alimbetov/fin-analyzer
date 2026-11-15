package kz.fin.backend.analytics;

import kz.fin.backend.domain.EconomicKernel;
import java.util.Objects;
import java.util.UUID;

/**
 * Requested economic coordinates, not evidence of authorization/approval.
 * Actual production access must resolve tenant and approved revision server-side.
 */
public record AnalysisContext(
        UUID organizationId,
        UUID economicEntityId,
        UUID businessScopeId,
        UUID sourceRevisionId,
        EconomicKernel.TemporalReference period,
        EconomicKernel.ReportingBasis reportingBasis,
        DataSpace dataSpace,
        String currency
) {
    public enum DataSpace { ACTUAL, PLAN, FORECAST, SCENARIO, SYNTHETIC }

    public AnalysisContext {
        Objects.requireNonNull(organizationId);
        Objects.requireNonNull(economicEntityId);
        Objects.requireNonNull(businessScopeId);
        Objects.requireNonNull(sourceRevisionId);
        Objects.requireNonNull(period);
        Objects.requireNonNull(reportingBasis);
        Objects.requireNonNull(dataSpace);
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("INVALID_CONTEXT_CURRENCY");
        }
    }

    public void requireCurrency(FinancialAmount value) {
        Objects.requireNonNull(value, "amount");
        if (!currency.equals(value.currency())) throw new IllegalArgumentException("CURRENCY_MISMATCH");
    }

    public void requireFlowPeriod() {
        if (period.kind() != EconomicKernel.TimeKind.INTERVAL) {
            throw new IllegalArgumentException("PERIOD_MISMATCH: flow requires interval");
        }
    }
}
