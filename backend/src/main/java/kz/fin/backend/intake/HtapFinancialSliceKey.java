package kz.fin.backend.intake;

import kz.fin.backend.domain.EconomicKernel;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * W0/W1.2.A immutable *logical* financial slice selector.
 * Not a physical partition key, authorization decision, or calculation permit.
 *
 * A source's form/version is verified against approved_source_revision by the
 * caller's trusted resolver. Mapping SHA/version are server-controlled policy
 * identities; having a syntactically valid SHA is not proof of approval.
 */
public record HtapFinancialSliceKey(
        UUID organizationId,
        UUID economicEntityId,
        UUID businessScopeId,
        UUID publishedFactSetRevisionId,
        String publishedSnapshotSha256,
        UUID sourceRevisionId,
        String formCode,
        String formVersion,
        String dataSpace,
        String reportingBasis,
        String metricCode,
        String metricVersion,
        EconomicKernel.TimeKind timeKind,
        ReportingPeriodV5.Type periodType,
        ReportingPeriodV5.Coverage coverage,
        String periodCode,
        String calendarId,
        String calendarVersion,
        String unitCode,
        String currencyCode,
        String dimensionHash,
        String mappingVersion,
        String mappingSha256
) {
    public HtapFinancialSliceKey {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(economicEntityId, "economicEntityId");
        Objects.requireNonNull(businessScopeId, "businessScopeId");
        Objects.requireNonNull(publishedFactSetRevisionId, "publishedFactSetRevisionId");
        Objects.requireNonNull(sourceRevisionId, "sourceRevisionId");
        Objects.requireNonNull(timeKind, "timeKind");
        Objects.requireNonNull(periodType, "periodType");
        Objects.requireNonNull(coverage, "coverage");
        publishedSnapshotSha256 = sha(publishedSnapshotSha256, "publishedSnapshotSha256");
        mappingSha256 = sha(mappingSha256, "mappingSha256");
        dimensionHash = sha(dimensionHash, "dimensionHash");
        formCode = code(formCode, "formCode");
        formVersion = code(formVersion, "formVersion");
        metricCode = code(metricCode, "metricCode");
        metricVersion = code(metricVersion, "metricVersion");
        mappingVersion = code(mappingVersion, "mappingVersion");
        unitCode = code(unitCode, "unitCode");
        dataSpace = code(dataSpace, "dataSpace");
        reportingBasis = code(reportingBasis, "reportingBasis");
        if (!java.util.Set.of("ACTUAL", "PLAN", "FORECAST", "SCENARIO", "SYNTHETIC")
                .contains(dataSpace))
            throw new IllegalArgumentException("FIN_HTAP_DATA_SPACE_UNKNOWN");
        if (!java.util.Set.of("SEPARATE", "CONSOLIDATED", "MANAGEMENT")
                .contains(reportingBasis))
            throw new IllegalArgumentException("FIN_HTAP_REPORTING_BASIS_UNKNOWN");
        calendarId = code(calendarId, "calendarId");
        calendarVersion = code(calendarVersion, "calendarVersion");
        if (currencyCode != null && !currencyCode.matches("[A-Z]{3}"))
            throw new IllegalArgumentException("FIN_HTAP_CURRENCY_INVALID");
        if ("MONEY".equals(unitCode) != (currencyCode != null))
            throw new IllegalArgumentException("FIN_HTAP_UNIT_CURRENCY_CONFLICT");
        var resolved = ReportingPeriodV5.resolve(periodType, periodCode, coverage);
        if (!resolved.calendarId().equals(calendarId) ||
                !resolved.calendarVersion().equals(calendarVersion))
            throw new IllegalArgumentException("FIN_HTAP_CALENDAR_VERSION_UNQUALIFIED");
        if (periodType == ReportingPeriodV5.Type.AS_OF
                && timeKind != EconomicKernel.TimeKind.AS_OF)
            throw new IllegalArgumentException("FIN_HTAP_TIME_KIND_MISMATCH");
        if (periodType != ReportingPeriodV5.Type.AS_OF
                && timeKind == EconomicKernel.TimeKind.AS_OF) {
            // Monthly closing STOCK is a future *separate qualified* policy,
            // not an implicit INTERVAL-to-AS_OF conversion.
            throw new IllegalArgumentException("FIN_HTAP_CLOSING_POLICY_NOT_QUALIFIED");
        }
    }

    public ReportingPeriodV5.Period sourcePeriod() {
        return ReportingPeriodV5.resolve(periodType, periodCode, coverage);
    }

    public LocalDate startInclusive() { return sourcePeriod().startInclusive(); }
    public LocalDate endExclusive() { return sourcePeriod().endExclusive(); }

    private static String sha(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("FIN_HTAP_SHA256_INVALID_" + name);
        return value;
    }

    private static String code(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 128
                || !value.equals(value.strip()))
            throw new IllegalArgumentException("FIN_HTAP_CODE_INVALID_" + name);
        return value;
    }
}
