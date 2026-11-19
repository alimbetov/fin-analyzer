package kz.fin.formlib.calc.fact;

import java.util.Comparator;
import java.util.List;
import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * Recomputes every pilot F02 candidate/observation SHA rather than trusting
 * an arbitrary record created in Java. DB source recheck is still mandatory
 * inside future FACT-1.B TX-Q; this pure check is not a proof of INGEST-2 I/O.
 */
final class F02CandidateIntegrity {
  private F02CandidateIntegrity(){}

  static void verify(CandidateFactSet candidate) {
    if(candidate==null||candidate.canonicalizationVersion()!=1
        ||candidate.sources().size()!=1||candidate.observations().size()!=3)
      throw new IllegalArgumentException("SOURCE_BINDING_MISMATCH");
    FactScope scope=candidate.scope();
    if(!"F02_TEST_ONLY".equals(scope.datasetCode())
        ||!"M".equals(scope.periodType())||!"KZT".equals(scope.currencyCode())
        ||!"PERIOD_ONLY".equals(scope.coverage())
        ||!"MANAGEMENT".equals(scope.reportingBasis())
        ||!"SYNTHETIC".equals(scope.dataSpace()))
      throw new IllegalArgumentException("SOURCE_BINDING_MISMATCH");
    final YearMonth month;
    try{month=YearMonth.parse(scope.periodCode());}
    catch(RuntimeException e){throw new IllegalArgumentException("SOURCE_BINDING_MISMATCH",e);}
    if(!scope.periodStart().equals(month.atDay(1))
        ||!scope.periodEndExclusive().equals(month.plusMonths(1).atDay(1)))
      throw new IllegalArgumentException("SOURCE_BINDING_MISMATCH");
    PinnedSourceMember source=candidate.sources().getFirst();
    if(!"F02".equals(source.formCode())||!"PRIMARY".equals(source.sourceRole()))
      throw new IllegalArgumentException("SOURCE_BINDING_MISMATCH");
    String expectedSource=FactDigest.sha("FIN-FACT-SOURCE-SET-V1",source.memberDigest());
    if(!expectedSource.equals(candidate.sourceSetSha256()))
      throw new IllegalArgumentException("SOURCE_BINDING_MISMATCH");
    String dimension=FactDigest.sha("FIN-FACT-EMPTY-DIMENSIONS-V1");
    List<TypedObservation> obs=candidate.observations();
    if(!obs.stream().map(TypedObservation::metricCode).toList().equals(
        List.of("F02.cogs","F02.netRevenue","F02.operatingExpenses")))
      throw new IllegalArgumentException("SOURCE_BINDING_MISMATCH");
    String[] fields={"cogs","netRevenue","operatingExpenses"};
    String[] members=new String[8];
    members[0]=scope.stableScopeKey();
    members[1]=expectedSource;
    members[2]=candidate.mappingBundleSha256();
    members[3]=candidate.descriptorSha256();
    members[4]="1";
    for(int i=0;i<obs.size();i++){
      TypedObservation o=obs.get(i);
      String field=fields[i];
      if(!field.equals(o.sourceFieldCode())||!source.revisionId().equals(o.sourceRevisionId())
          ||!"SINGLE".equals(o.sourceRowKey())||!"TEST_ONLY_F02_V1".equals(o.metricContractVersion())
          ||!scope.periodStart().equals(o.periodStart())
          ||!scope.periodEndExclusive().equals(o.periodEndExclusive())
          ||!"PRESENT".equals(o.valueState())||!"KZT".equals(o.unitCode())
          ||!"KZT".equals(o.currencyCode())||!dimension.equals(o.dimensionDigest()))
        throw new IllegalArgumentException("SOURCE_BINDING_MISMATCH");
      String key=FactDigest.sha("FIN-FACT-OBSERVATION-KEY-V1",
          scope.stableScopeKey(),source.revisionId().toString(),"SINGLE",
          field,o.metricCode(),o.metricContractVersion(),dimension,
          scope.periodStart().toString(),scope.periodEndExclusive().toString());
      BigDecimal value=o.value();
      String canonical=value.signum()==0?"0":value.stripTrailingZeros().toPlainString();
      String hash=FactDigest.sha("FIN-FACT-OBSERVATION-VALUE-V1",
          key,canonical,"NUMERIC","PRESENT","KZT","KZT");
      if(!key.equals(o.observationKeySha256())||!hash.equals(o.valueSha256()))
        throw new IllegalArgumentException("SOURCE_BINDING_MISMATCH");
      members[5+i]=FactDigest.sha("FIN-FACT-OBSERVATION-MEMBER-V1",
          o.metricCode(),o.sourceFieldCode(),o.sourceRevisionId().toString(),
          key,hash);
    }
    if(!FactDigest.sha("FIN-FACT-CANDIDATE-V1",members)
        .equals(candidate.candidateFactsetSha256()))
      throw new IllegalArgumentException("SOURCE_BINDING_MISMATCH");
  }
}
