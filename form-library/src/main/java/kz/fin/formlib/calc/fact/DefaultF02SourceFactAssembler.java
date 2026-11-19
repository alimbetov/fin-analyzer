package kz.fin.formlib.calc.fact;

import kz.fin.formlib.calc.ingest.FormFactCandidateExtractor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** FACT-1.A1: pure F02 TEST_ONLY source->candidate conversion, fail closed. */
@Component
public final class DefaultF02SourceFactAssembler implements F02SourceFactAssembler {
  private static final String DATASET = "F02_TEST_ONLY";
  private static final String ROW_KEY = "SINGLE";

  @Override
  public CandidateFactSet assemble(
      FormFactCandidateExtractor.Projection p, F02MappingDescriptor descriptor) {
    if (p == null || descriptor == null
        || !"SOURCE_SHA_REVERIFIED".equals(p.sourceIntegrityState())
        || !"UNQUALIFIED_SOURCE_PROJECTION".equals(p.qualificationState())
        || !"F02".equals(p.formCode()) || !"SINGLE_RECORD".equals(p.contentMode())
        || p.instanceId() == null || p.revisionId() == null
        || !descriptor.formVersionId().equals(p.formVersionId())
        || !descriptor.schemaSha256().equals(p.sourceSchemaSha256())
        || !descriptor.sourceMappingSha256().equals(p.sourceMappingSha256())) {
      throw new IllegalArgumentException("F02_SOURCE_BINDING_MISMATCH");
    }
    FactDigest.requireSha(p.sourceSha256Claim(), "F02_SOURCE_SHA_INVALID");
    FactDigest.requireSha(p.rowOrderSha256(), "F02_ROW_ORDER_SHA_INVALID");
    if (p.scope() == null || p.numeric() == null || p.attributes() == null
        || p.evidence() == null || !p.attributes().isEmpty()
        || !p.evidence().isEmpty() || p.numeric().size() != 3) {
      throw new IllegalArgumentException("F02_SOURCE_SHAPE_INVALID");
    }
    var s = p.scope();
    if (!"M".equals(s.periodType()) || !"KZT".equals(s.currencyCode())
        || !"PERIOD_ONLY".equals(s.coverage())
        || !"MANAGEMENT".equals(s.reportingBasis())
        || !"SYNTHETIC".equals(s.dataSpace())) {
      throw new IllegalArgumentException("F02_PILOT_SCOPE_FORBIDDEN");
    }
    YearMonth month;
    try {
      if (s.periodCode() == null || !s.periodCode().matches("[0-9]{4}-[0-9]{2}")) {
        throw new IllegalArgumentException("F02_PERIOD_INVALID");
      }
      month = YearMonth.parse(s.periodCode());
    } catch (java.time.DateTimeException ex) {
      throw new IllegalArgumentException("F02_PERIOD_INVALID", ex);
    }
    if (!month.atDay(1).equals(s.periodStart())
        || !month.plusMonths(1).atDay(1).equals(s.periodEndExclusive())) {
      throw new IllegalArgumentException("F02_PERIOD_RANGE_MISMATCH");
    }
    var scope = new FactScope(s.organizationId(), s.economicEntityId(),
        s.businessScopeId(), s.projectId(), DATASET, s.dataSpace(),
        s.coverage(), s.reportingBasis(), s.currencyCode(), s.periodType(),
        s.periodCode(), s.periodStart(), s.periodEndExclusive());

    Map<String, BigDecimal> values = new TreeMap<>();
    for (var n : p.numeric()) {
      if (n == null || n.rowId() != null || !"MONEY".equals(n.fieldType())
          || !"PRESENT".equals(n.valueState()) || n.numericValue() == null
          || n.numericValue().signum() < 0 || n.numericValue().scale() < 0
          || n.numericValue().scale() > 12 || n.numericValue().precision() > 38
          || values.putIfAbsent(n.sourceFieldCode(), n.numericValue()) != null) {
        throw new IllegalArgumentException("F02_NUMERIC_CONTRACT_INVALID");
      }
    }
    if (!values.keySet().equals(java.util.Set.of(
        "netRevenue", "cogs", "operatingExpenses"))) {
      throw new IllegalArgumentException("F02_FIELD_SET_INVALID");
    }

    var source = new PinnedSourceMember(
        p.revisionId(), p.instanceId(), p.formVersionId(), p.formCode(),
        p.sourceSha256Claim(), p.rowOrderSha256(), p.sourceSchemaSha256(),
        p.sourceMappingSha256(), "PRIMARY");
    List<PinnedSourceMember> sources = List.of(source);
    String sourceSet = FactDigest.sha("FIN-FACT-SOURCE-SET-V1",
        source.memberDigest()); // Exactly one source in F02 v1.
    String dimensions = FactDigest.sha("FIN-FACT-EMPTY-DIMENSIONS-V1");
    var observations = new ArrayList<TypedObservation>();
    for (var field : descriptor.fields()) {
      BigDecimal amount = values.get(field.sourceFieldCode());
      String canonicalAmount = amount.signum() == 0 ? "0"
          : amount.stripTrailingZeros().toPlainString();
      String observationKey = FactDigest.sha("FIN-FACT-OBSERVATION-KEY-V1",
          scope.stableScopeKey(), p.revisionId().toString(), ROW_KEY,
          field.sourceFieldCode(), field.metricCode(),
          descriptor.metricContractVersion(), dimensions,
          scope.periodStart().toString(), scope.periodEndExclusive().toString());
      String valueSha = FactDigest.sha("FIN-FACT-OBSERVATION-VALUE-V1",
          observationKey, canonicalAmount, "NUMERIC", field.valueState(),
          "KZT", "KZT");
      observations.add(new TypedObservation(scope.periodStart(),
          scope.periodEndExclusive(), p.revisionId(), ROW_KEY,
          field.sourceFieldCode(), field.metricCode(),
          descriptor.metricContractVersion(), amount, "KZT", "KZT",
          field.valueState(), dimensions, observationKey, valueSha));
    }
    observations.sort(Comparator.comparing(TypedObservation::metricCode));
    String[] identity = new String[observations.size() + 5];
    identity[0] = scope.stableScopeKey();
    identity[1] = sourceSet;
    identity[2] = descriptor.mappingBundleSha256();
    identity[3] = descriptor.descriptorSha256();
    identity[4] = "1";
    for (int i = 0; i < observations.size(); i++) {
      var o = observations.get(i);
      identity[5 + i] = FactDigest.sha("FIN-FACT-OBSERVATION-MEMBER-V1",
          o.metricCode(), o.sourceFieldCode(), o.sourceRevisionId().toString(),
          o.observationKeySha256(), o.valueSha256());
    }
    String candidateSha = FactDigest.sha("FIN-FACT-CANDIDATE-V1", identity);
    return new CandidateFactSet(scope, sources, observations,
        descriptor.mappingBundleSha256(), descriptor.descriptorSha256(),
        sourceSet, candidateSha, 1);
  }
}
