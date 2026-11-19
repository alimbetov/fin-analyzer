package kz.fin.formlib.calc.fact;

import java.time.LocalDate;
import java.util.UUID;

/** Full qualified economic/business scope; never derive from client-supplied tenant shortcuts. */
public record FactScope(
    UUID organizationId, UUID economicEntityId, UUID businessScopeId, UUID projectId,
    String datasetCode, String dataSpace, String coverage, String reportingBasis,
    String currencyCode, String periodType, String periodCode,
    LocalDate periodStart, LocalDate periodEndExclusive) {

  public FactScope {
    if (organizationId == null || economicEntityId == null || businessScopeId == null
        || projectId == null || periodStart == null || periodEndExclusive == null
        || !periodStart.isBefore(periodEndExclusive)) {
      throw new IllegalArgumentException("FACT_SCOPE_INVALID");
    }
    FactDigest.required(datasetCode, "FACT_DATASET_REQUIRED");
    FactDigest.required(dataSpace, "FACT_DATA_SPACE_REQUIRED");
    FactDigest.required(coverage, "FACT_COVERAGE_REQUIRED");
    FactDigest.required(reportingBasis, "FACT_REPORTING_BASIS_REQUIRED");
    FactDigest.required(currencyCode, "FACT_CURRENCY_REQUIRED");
    FactDigest.required(periodType, "FACT_PERIOD_TYPE_REQUIRED");
    FactDigest.required(periodCode, "FACT_PERIOD_CODE_REQUIRED");
  }

  public String stableScopeKey() {
    return FactDigest.sha("FIN-FACT-SCOPE-V1",
        organizationId.toString(), economicEntityId.toString(),
        businessScopeId.toString(), projectId.toString(), datasetCode,
        dataSpace, coverage, reportingBasis, currencyCode, periodType, periodCode,
        periodStart.toString(), periodEndExclusive.toString());
  }
}
