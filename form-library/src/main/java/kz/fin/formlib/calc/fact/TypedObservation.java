package kz.fin.formlib.calc.fact;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** One immutable observed source measure; no derived values or implicit conversions. */
public record TypedObservation(
    LocalDate periodStart, LocalDate periodEndExclusive, UUID sourceRevisionId,
    String sourceRowKey, String sourceFieldCode, String metricCode,
    String metricContractVersion, BigDecimal value, String unitCode,
    String currencyCode, String valueState, String dimensionDigest,
    String observationKeySha256, String valueSha256) {

  public TypedObservation {
    if (periodStart == null || periodEndExclusive == null
        || !periodStart.isBefore(periodEndExclusive) || sourceRevisionId == null
        || value == null || value.scale() < 0 || value.scale() > 12
        || value.precision() > 38 || value.signum() < 0) {
      throw new IllegalArgumentException("FACT_OBSERVATION_VALUE_INVALID");
    }
    FactDigest.required(sourceRowKey, "FACT_ROW_KEY_REQUIRED");
    FactDigest.required(sourceFieldCode, "FACT_FIELD_REQUIRED");
    FactDigest.required(metricCode, "FACT_METRIC_REQUIRED");
    FactDigest.required(metricContractVersion, "FACT_METRIC_VERSION_REQUIRED");
    FactDigest.required(unitCode, "FACT_UNIT_REQUIRED");
    FactDigest.required(currencyCode, "FACT_CURRENCY_REQUIRED");
    if (!"PRESENT".equals(valueState)) {
      throw new IllegalArgumentException("FACT_VALUE_STATE_INVALID");
    }
    FactDigest.requireSha(dimensionDigest, "FACT_DIMENSION_SHA_INVALID");
    FactDigest.requireSha(observationKeySha256, "FACT_OBSERVATION_KEY_SHA_INVALID");
    FactDigest.requireSha(valueSha256, "FACT_VALUE_SHA_INVALID");
  }
}
