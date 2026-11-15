package kz.fin.backend.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

public final class EconomicKernel {
    private EconomicKernel() {}

    public enum ValueState { PRESENT, EXPLICIT_ZERO, MISSING, NOT_APPLICABLE, ESTIMATED }
    public enum TimeKind { AS_OF, INTERVAL }
    public enum ReportingBasis { SEPARATE, CONSOLIDATED, MANAGEMENT }
    public enum RevisionStatus { DRAFT, VALIDATED, APPROVED }

    public record EconomicValue(ValueState state, BigDecimal decimal, String estimateMethodRef) {
        public EconomicValue {
            Objects.requireNonNull(state, "state");
            if (state == ValueState.PRESENT || state == ValueState.EXPLICIT_ZERO || state == ValueState.ESTIMATED) {
                Objects.requireNonNull(decimal, "numeric state requires value");
                if (decimal.scale() > 12 || decimal.precision() - decimal.scale() > 26) {
                    throw new IllegalArgumentException("amount exceeds NUMERIC(38,12) contract");
                }
                if (state == ValueState.EXPLICIT_ZERO && decimal.signum() != 0) {
                    throw new IllegalArgumentException("explicit zero must equal 0");
                }
                if (state == ValueState.ESTIMATED && (estimateMethodRef == null || estimateMethodRef.isBlank())) {
                    throw new IllegalArgumentException("estimate requires method");
                }
                decimal = decimal.stripTrailingZeros();
            } else if (decimal != null) {
                throw new IllegalArgumentException("unknown/not applicable cannot contain a numeric value");
            }
            if (state != ValueState.ESTIMATED && estimateMethodRef != null) {
                throw new IllegalArgumentException("estimate method only valid for estimates");
            }
        }

        public static EconomicValue present(String amount) {
            return new EconomicValue(ValueState.PRESENT, new BigDecimal(amount), null);
        }
        public static EconomicValue explicitZero() {
            return new EconomicValue(ValueState.EXPLICIT_ZERO, BigDecimal.ZERO, null);
        }
        public static EconomicValue missing() {
            return new EconomicValue(ValueState.MISSING, null, null);
        }
        public static EconomicValue notApplicable() {
            return new EconomicValue(ValueState.NOT_APPLICABLE, null, null);
        }
        public static EconomicValue estimated(String amount, String method) {
            return new EconomicValue(ValueState.ESTIMATED, new BigDecimal(amount), method);
        }
        public boolean numeric() {
            return decimal != null;
        }
    }

    // Half-open intervals [startInclusive, endExclusive). AS_OF records a date, not a flow.
    public record TemporalReference(TimeKind kind, LocalDate startInclusive, LocalDate endExclusive) {
        public TemporalReference {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(startInclusive);
            Objects.requireNonNull(endExclusive);
            if (!endExclusive.isAfter(startInclusive)) {
                throw new IllegalArgumentException("end must be later than start");
            }
            if (kind == TimeKind.AS_OF && !endExclusive.equals(startInclusive.plusDays(1))) {
                throw new IllegalArgumentException("AS_OF must designate exactly one calendar date");
            }
        }
        public static TemporalReference asOf(LocalDate date) {
            return new TemporalReference(TimeKind.AS_OF, date, date.plusDays(1));
        }
        public static TemporalReference interval(LocalDate start, LocalDate endExclusive) {
            return new TemporalReference(TimeKind.INTERVAL, start, endExclusive);
        }
    }

    public record FactKey(
            UUID organizationId, UUID economicEntityId, UUID businessScopeId,
            String metricCode, String metricVersion, TemporalReference time,
            ReportingBasis reportingBasis, String unitCode, String currencyCode,
            SortedMap<String, String> dimensions
    ) {
        public FactKey {
            Objects.requireNonNull(organizationId);
            Objects.requireNonNull(economicEntityId);
            Objects.requireNonNull(businessScopeId);
            Objects.requireNonNull(time);
            Objects.requireNonNull(reportingBasis);
            if (metricCode == null || !metricCode.matches("[A-Z][A-Z0-9_]{1,127}")) {
                throw new IllegalArgumentException("invalid metric code");
            }
            if (metricVersion == null || metricVersion.isBlank()) throw new IllegalArgumentException("metric version");
            if (unitCode == null || unitCode.isBlank()) throw new IllegalArgumentException("unit code");
            if (currencyCode != null && !currencyCode.matches("[A-Z]{3}")) throw new IllegalArgumentException("currency");
            TreeMap<String, String> sorted = new TreeMap<>(Objects.requireNonNull(dimensions));
            for (var e : sorted.entrySet()) {
                if (e.getKey().isBlank() || e.getValue() == null || e.getValue().isBlank()) {
                    throw new IllegalArgumentException("invalid dimension");
                }
            }
            dimensions = Collections.unmodifiableSortedMap(sorted);
        }
    }

    public record FactAssertion(
            UUID id, FactKey key, EconomicValue value, UUID sourceRevisionId, Instant recordedAt
    ) {
        public FactAssertion {
            Objects.requireNonNull(id);
            Objects.requireNonNull(key);
            Objects.requireNonNull(value);
            Objects.requireNonNull(sourceRevisionId);
            Objects.requireNonNull(recordedAt);
        }
    }

    public record FactSetRevision(
            UUID id, UUID organizationId, UUID parentRevisionId,
            RevisionStatus status, Map<FactKey, UUID> selectedAssertions, Instant approvedAt
    ) {
        public FactSetRevision {
            Objects.requireNonNull(id);
            Objects.requireNonNull(organizationId);
            Objects.requireNonNull(status);
            Objects.requireNonNull(selectedAssertions);
            if (status == RevisionStatus.APPROVED && approvedAt == null) {
                throw new IllegalArgumentException("approved revision needs timestamp");
            }
            if (status != RevisionStatus.APPROVED && approvedAt != null) {
                throw new IllegalArgumentException("approval timestamp only on approved revision");
            }
            selectedAssertions = Map.copyOf(selectedAssertions);
            if (selectedAssertions.keySet().stream().anyMatch(k -> !k.organizationId().equals(organizationId))) {
                throw new IllegalArgumentException("mixed organizations");
            }
        }

        public static FactSetRevision approved(UUID id, UUID organizationId, UUID parentId,
                                               Collection<FactAssertion> assertions, Instant approvedAt) {
            Map<FactKey, UUID> selected = new HashMap<>();
            for (FactAssertion a : assertions) {
                if (!organizationId.equals(a.key().organizationId())) {
                    throw new IllegalArgumentException("cross-tenant assertion");
                }
                if (selected.putIfAbsent(a.key(), a.id()) != null) {
                    throw new IllegalArgumentException("multiple assertions for same fact key; reconcile first");
                }
            }
            return new FactSetRevision(id, organizationId, parentId, RevisionStatus.APPROVED, selected, approvedAt);
        }
    }

    public record Scenario(
            UUID id, UUID baseFactSetRevisionId, UUID assumptionRevisionId,
            Map<String, BigDecimal> overrides
    ) {
        public Scenario {
            Objects.requireNonNull(id);
            Objects.requireNonNull(baseFactSetRevisionId);
            Objects.requireNonNull(assumptionRevisionId);
            overrides = Map.copyOf(Objects.requireNonNull(overrides));
            if (overrides.values().stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("null assumption");
            }
        }
    }
}
